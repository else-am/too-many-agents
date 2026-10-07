import { randomUUID } from "node:crypto";
import type { BbPluginApi } from "@get-bb/plugin-sdk";
import { imageUploads } from "./images.js";
import type { MinecraftWorlds } from "./minecraft.js";
import { agentSpeech } from "./speech.js";
import { chatNotifications } from "./notifications.js";
import { describe, type ObjectValue, type Session, type SpawnOptions, type Thread } from "./protocol.js";

function activity(thread: Thread, hasPendingInteraction: boolean): "wants_you" | "working" | "idle" {
  if (hasPendingInteraction || thread.status === "error" ||
      thread.latestAttentionAt > (thread.lastReadAt ?? 0)) return "wants_you";
  if (["active", "pending", "starting", "stopping"].includes(thread.status)) return "working";
  return "idle";
}

/** Translate native BB state once, into the view and physical state Minecraft needs. */
export function minecraftThreads(bb: BbPluginApi, worlds: MinecraftWorlds) {
  const withImages = imageUploads(bb);
  const speech = agentSpeech(bb);
  chatNotifications(bb, worlds, speech);
  const pending = new Map<string, Promise<void>>();
  let disposed = false;
  const environments = new Map<string, { until: number; value: ReturnType<typeof readEnvironment> }>();

  async function readEnvironment(environmentId: string) {
    const [environment, status] = await Promise.all([
      bb.sdk.environments.get({ environmentId }),
      bb.sdk.environments.status({ environmentId }),
    ]);
    const isGitRepo = status.outcome === "available";
    return {
      id: environment.id, path: environment.path,
      isWorktree: environment.isWorktree && isGitRepo,
      isGitRepo,
      branchName: isGitRepo ? status.workspace.branch.currentBranch : null,
    };
  }

  function environmentFor(thread: Thread) {
    const id = thread.environmentId;
    if (!id) return null;
    const cached = environments.get(id);
    if (cached && cached.until > Date.now()) return cached.value;
    // Share one live workspace read across threads and streaming events for five seconds.
    const value = readEnvironment(id);
    environments.set(id, { until: Date.now() + 5_000, value });
    return value;
  }

  async function read(thread: Thread) {
    const threadId = thread.id;
    const [executionOptions, interactions, queuedMessages, environment] = await Promise.all([
      bb.sdk.threads.defaultExecutionOptions({ threadId }),
      bb.sdk.threads.interactions.list({ threadId }),
      bb.sdk.threads.queuedMessages.list({ threadId }),
      environmentFor(thread)?.catch(() => null) ?? null,
    ]);
    const hasPendingInteraction = interactions.some((interaction) => interaction.status === "pending");
    const queuedWork = queuedMessages.some((message) => message.failureReason != null)
      ? "failed" : queuedMessages.length ? "waiting" : "none";
    const active = ["active", "pending", "starting", "stopping"].includes(thread.status);
    return {
      thread,
      environment,
      executionOptions,
      interactions,
      queuedMessages,
      parentThreadId: thread.parentThreadId ?? "",
      status: thread.status,
      hasPendingInteraction,
      latestAttentionAt: thread.latestAttentionAt,
      lastReadAt: thread.lastReadAt,
      queuedWork,
      activity: activity(thread, hasPendingInteraction),
      canSteer: active,
      conversationArchived: thread.archivedAt != null,
      taskTitle: thread.title ?? thread.titleFallback ?? "",
      providerId: thread.providerId,
      // A bubble is decoration; failing to read it must not look like a lost connection.
      speech: thread.deletedAt != null || thread.archivedAt != null
        ? null : await speech.speech(thread, interactions).catch(() => null),
    };
  }

  function sync(live: Session, agentId: string, threadId: string): Promise<void> {
    const previous = pending.get(threadId) ?? Promise.resolve();
    const next = previous
      .catch(() => undefined)
      .then(async () => {
        if (disposed) return;
        let update;
        let thread: Thread | undefined;
        try {
          thread = await bb.sdk.threads.get({ threadId });
          const view = await read(thread);
          update = {
            view: thread.deletedAt != null ? { ...view, status: "disconnected", error: "This conversation is unavailable in BB." } : view,
            state:
              view.thread.deletedAt != null
                ? "disconnected"
                : view.conversationArchived
                  ? "suspended"
                  : "present",
            running: view.thread.status === "active",
            projectId: view.thread.projectId,
          };
        } catch (error) {
          // A missing conversation leaves its saved binding intact, just like an offline BB.
          update = { state: "disconnected", view: { error: describe(error) } };
        }
        if (!disposed) await worlds.callback(live, "body.sync", { agentId, threadId, ...update });
      });
    pending.set(threadId, next);
    void next
      .finally(() => {
        if (pending.get(threadId) === next) pending.delete(threadId);
      })
      .catch(() => undefined);
    return next;
  }

  async function start(
    live: Session,
    agentId: string,
    minecraftAccess: boolean,
    spawn: SpawnOptions,
  ) {
    const nonce = randomUUID();
    const created = await withImages(spawn.projectId, spawn as unknown as ObjectValue, async (prepared) => {
      // Uploads can fail without starting a thread; record the nonce only at the mutation boundary.
      await worlds.callback(live, "body.begin", { agentId, nonce });
      return bb.sdk.threads.spawn({
        ...(prepared as unknown as SpawnOptions),
        pluginMetadata: { worldId: live.worldId, agentId, minecraftAccess, nonce },
      });
    });
    worlds.identities.set(created.id, { worldId: live.worldId, agentId });
    await worlds.callback(live, "body.bind", { agentId, threadId: created.id, nonce });
    await sync(live, agentId, created.id);
    return created;
  }

  async function changed(threadId: string) {
    const identity = await worlds.identify(threadId);
    if (!identity) return;
    let live;
    try {
      live = worlds.session(identity.worldId);
    } catch {
      return;
    }
    await sync(live, identity.agentId, threadId);
  }

  async function bind(threadId: string, worldId?: string) {
    const metadata = await bb.sdk.threads.getPluginMetadata({ threadId });
    if (
      typeof metadata.nonce !== "string" ||
      typeof metadata.worldId !== "string" ||
      typeof metadata.agentId !== "string"
    )
      return;
    if (worldId && metadata.worldId !== worldId) return;
    let live;
    try {
      live = worlds.session(metadata.worldId);
    } catch {
      return;
    }
    worlds.identities.set(threadId, { worldId: metadata.worldId, agentId: metadata.agentId });
    // Java accepts only the nonce it saved before this start.
    await worlds.callback(live, "body.bind", {
      agentId: metadata.agentId,
      threadId,
      nonce: metadata.nonce,
    });
    await sync(live, metadata.agentId, threadId);
  }

  async function reconnect(worldId: string) {
    for (const archived of [false, true]) {
      for (let offset = 0; ; offset += 100) {
        const rows = await bb.sdk.threads.list({
          originPluginId: bb.pluginId,
          archived,
          includeHidden: true,
          limit: 100,
          offset,
        });
        await Promise.all(rows.map((thread) => bind(thread.id, worldId)));
        if (rows.length < 100) break;
      }
    }
  }

  bb.events.on("thread.created", ({ thread }) => bind(thread.id));
  bb.events.on("thread.deleted", ({ thread }) => {
    speech.forget(thread.id);
  });
  for (const event of [
    "thread.active",
    "thread.idle",
    "thread.failed",
    "thread.archived",
    "thread.unarchived",
    "thread.deleted",
    "interaction.pending",
  ] as const)
    bb.events.on(event, ({ thread }) => changed(thread.id));
  bb.events.on("experimental_thread.events", ({ thread }) => changed(thread.id));
  for (const event of ["message.queued", "message.dispatched", "message.cancelled"] as const)
    bb.events.on(event, ({ entry }) => changed(entry.threadId));
  bb.onDispose(() => {
    disposed = true;
    environments.clear();
  });
  return { sync, start, withImages, reconnect };
}
export type MinecraftThreads = ReturnType<typeof minecraftThreads>;
