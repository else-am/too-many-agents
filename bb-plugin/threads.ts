import { randomUUID } from "node:crypto";
import type { BbPluginApi } from "@get-bb/plugin-sdk";
import { imageUploads } from "./images.js";
import type { MinecraftWorlds } from "./minecraft.js";
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
  const pending = new Map<string, Promise<void>>();
  let disposed = false;

  async function read(thread: Thread) {
    const threadId = thread.id;
    const [executionOptions, interactions, queuedMessages] = await Promise.all([
      bb.sdk.threads.defaultExecutionOptions({ threadId }),
      bb.sdk.threads.interactions.list({ threadId }),
      bb.sdk.threads.queuedMessages.list({ threadId }),
    ]);
    // These sidebar fields are absent from threads.get in the current SDK.
    let row;
    if (thread.deletedAt == null) for (let offset = 0; ; offset += 100) {
      const rows = await bb.sdk.threads.list({ projectId: thread.projectId,
        archived: thread.archivedAt != null, includeHidden: true, limit: 100, offset });
      row = rows.find((item) => item.id === threadId);
      if (row || rows.length < 100) break;
    }
    const hasPendingInteraction = row?.hasPendingInteraction ?? interactions.length > 0;
    const active = ["active", "pending", "starting", "stopping"].includes(thread.status);
    return {
      thread,
      executionOptions,
      interactions,
      queuedMessages,
      parentThreadId: thread.parentThreadId ?? "",
      status: thread.status,
      hasPendingInteraction,
      latestAttentionAt: thread.latestAttentionAt,
      lastReadAt: thread.lastReadAt,
      queuedWork: row?.queuedWork ?? "none",
      activity: activity(thread, hasPendingInteraction),
      canSteer: active,
      conversationArchived: thread.archivedAt != null,
      taskTitle: thread.title ?? "",
      providerId: thread.providerId,
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
            view,
            state:
              view.thread.deletedAt != null
                ? "deleted"
                : view.conversationArchived
                  ? "suspended"
                  : "present",
            running: view.thread.status === "active",
            projectId: view.thread.projectId,
          };
        } catch (error) {
          // Disconnection must never be mistaken for deletion.
          update =
            !thread && error instanceof Error && "status" in error && error.status === 404
              ? { state: "deleted" }
              : { view: { error: describe(error) } };
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
  });
  return { sync, start, withImages, reconnect };
}
export type MinecraftThreads = ReturnType<typeof minecraftThreads>;
