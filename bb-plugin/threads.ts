import type { BbPluginApi } from "@get-bb/plugin-sdk";
import { imageUploads } from "./images.js";
import type { MinecraftWorlds } from "./minecraft.js";
import {
  ApiError,
  describe,
  type ObjectValue,
  type Session,
  type SpawnOptions,
} from "./protocol.js";

export function minecraftThreads(bb: BbPluginApi, worlds: MinecraftWorlds) {
  const withImages = imageUploads(bb);

  async function read(threadId: string, includeTimeline = false) {
    const thread = await bb.sdk.threads.get({ threadId });
    const [executionOptions, interactions, queuedMessages, timeline] = await Promise.all([
      bb.sdk.threads.defaultExecutionOptions({ threadId }),
      bb.sdk.threads.interactions.list({ threadId }),
      bb.sdk.threads.queuedMessages.list({ threadId }),
      includeTimeline ? bb.sdk.threads.timeline({ threadId }) : undefined,
    ]);
    return {
      thread,
      executionOptions,
      interactions,
      queuedMessages,
      ...(includeTimeline ? { timeline } : {}),
    };
  }

  async function snapshot(threadId: string) {
    try {
      return await read(threadId);
    } catch (error) {
      // Only a definite missing-thread response permits deleting a body association.
      const missing = error instanceof Error && "status" in error && error.status === 404;
      return missing ? { deleted: true } : { error: describe(error) };
    }
  }

  async function start(
    live: Session,
    agentId: string,
    minecraftAccess: boolean,
    nonce: string,
    request: ObjectValue,
  ) {
    request.environment ??= { type: "project-default" };
    const spawn = request as unknown as SpawnOptions;
    // The in-game project-folder choice needs the machine which owns that folder.
    if (
      spawn.environment.type === "provider" &&
      spawn.environment.environmentProviderId === "project-checkout" &&
      !spawn.environment.machine
    ) {
      const project = await bb.sdk.projects.get({ projectId: spawn.projectId });
      const source = project.sources.find((row) => row.isDefault) ?? project.sources[0];
      if (!source) throw new ApiError("project_folder_missing", "This project has no folder");
      const [existing] = await bb.sdk.environments.list({
        projectId: spawn.projectId,
        environmentProviderId: "project-checkout",
        hostId: source.hostId,
        path: source.path,
      });
      spawn.environment = existing
        ? { type: "reuse", environmentId: existing.id }
        : {
            ...spawn.environment,
            machine: { type: "existing", hostId: source.hostId },
          };
    }
    const created = await withImages(spawn.projectId, request, (prepared) =>
      bb.sdk.threads.spawn({
        ...(prepared as unknown as SpawnOptions),
        origin: "plugin",
        originPluginId: bb.pluginId,
        pluginMetadata: {
          worldId: live.worldId,
          agentId,
          minecraftAccess,
          nonce,
        },
      }),
    );
    worlds.identities.set(created.id, { worldId: live.worldId, agentId });
    await worlds.callback(live, "changed", {
      agentId,
      threadId: created.id,
      bind: true,
      nonce,
    });
    return created;
  }

  async function changed(threadId: string) {
    const identity = await worlds.identify(threadId);
    if (!identity) return;
    let live: Session;
    try {
      live = worlds.session(identity.worldId);
    } catch {
      return;
    }
    await worlds.callback(live, "changed", {
      agentId: identity.agentId,
      threadId,
    });
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
    worlds.identities.set(threadId, {
      worldId: metadata.worldId,
      agentId: metadata.agentId,
    });
    // Java accepts only the nonce it saved before requesting this thread.
    await worlds.callback(live, "changed", {
      agentId: metadata.agentId,
      threadId,
      bind: true,
      nonce: metadata.nonce,
    });
  }

  async function reconnect(worldId: string) {
    // Recover a start whose response/event was lost, without repeating creation.
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
  ] as const) {
    bb.events.on(event, ({ thread }) => changed(thread.id));
  }
  bb.events.on("experimental_thread.events", ({ thread }) => changed(thread.id));
  for (const event of ["message.queued", "message.dispatched", "message.cancelled"] as const) {
    bb.events.on(event, ({ entry }) => changed(entry.threadId));
  }

  return { read, snapshot, start, withImages, reconnect };
}

export type MinecraftThreads = ReturnType<typeof minecraftThreads>;
