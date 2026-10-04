import type { BbPluginApi } from "@get-bb/plugin-sdk";
import type { MinecraftAgents } from "./agents.js";
import type { MinecraftProjects } from "./projects.js";
import type { MinecraftWorlds } from "./minecraft.js";
import type { MinecraftThreads } from "./threads.js";
import { chatAssets } from "./chat-assets.js";
import {
  ApiError,
  PROTOCOL,
  MAX_BODY_BYTES,
  describe,
  loopbackUrl,
  object,
  optional,
  string,
  uuid,
  type Args,
  type ObjectValue,
  type Sdk,
} from "./protocol.js";

/** The Java UI adapter. BB validates and executes the native operations. */
export function registerGameApi(
  bb: BbPluginApi,
  worlds: MinecraftWorlds,
  threads: MinecraftThreads,
  agents: MinecraftAgents,
  projects: MinecraftProjects,
) {
  const chat = chatAssets(bb);
  async function dispatch(data: ObjectValue): Promise<unknown> {
    const op = string(data.op, "op");
    const live = () => {
      const session = worlds.session(uuid(data.worldId, "worldId"));
      if (session.worldSessionId !== data.worldSessionId)
        throw new ApiError("world_session_changed", "Minecraft world session changed");
      return session;
    };
    const agent =
      typeof data.agentId === "string" ? await agents.get(live(), data.agentId) : undefined;
    const requireAgent = () => {
      if (!agent) throw new ApiError("agent_missing", "A Minecraft agent is required");
      return agent;
    };
    const threadId = () => string(agent?.threadId ?? data.threadId, "threadId");
    switch (op) {
      case "hello":
        return {
          protocol: PROTOCOL,
          pluginId: bb.pluginId,
          runtimeVersion: (await bb.sdk.system.version()).currentVersion,
        };
      case "catalog":
        return bb.sdk.providers.models(
          optional(data, "providerId", "hostId", "environmentId") as Args<
            Sdk["providers"]["models"]
          >,
        );
      case "world.sync":
        return agents.sync(live());
      case "agent.create":
        return agents.create(live(), object(data.request));
      case "agent.message":
        return agents.send(live(), requireAgent(), object(data.message));
      case "agent.settings":
        return agents.settings(live(), requireAgent(), object(data.settings));
      case "agent.queue.steer":
        return bb.sdk.threads.queuedMessages.send({
          threadId: threadId(),
          queuedMessageId: string(data.messageId, "messageId"),
          mode: "steer",
        });
      case "agent.queue.cancel":
        return bb.sdk.threads.queuedMessages.delete({
          threadId: threadId(),
          queuedMessageId: string(data.messageId, "messageId"),
        });
      case "system.config":
        return bb.sdk.system.config();
      case "system.defaultProvider.set": {
        if (data.providerId !== null) string(data.providerId, "providerId");
        const config = await bb.sdk.system.config();
        await bb.sdk.system.updateGeneralSettings({
          ...config.generalSettings,
          defaultProviderId: data.providerId as string | null,
        });
        return bb.sdk.system.config();
      }
      case "project.executionOptions": {
        const world = await projects.metadata(live());
        const id =
          !data.projectId || data.projectId === "minecraft" ? world.worldProjectId : data.projectId;
        return id
          ? bb.sdk.projects.defaultExecutionOptions({ projectId: string(id, "projectId") })
          : {};
      }
      case "project.creationOptions":
        return projects.creationOptions(live(), string(data.projectId, "projectId"));
      case "usage":
        return bb.sdk.system.usageLimits(optional(data, "providerId", "hostId"));
      case "backend.status":
        return {
          protocol: PROTOCOL,
          runtimeVersion: (await bb.sdk.system.version()).currentVersion,
          providers: await bb.sdk.providers.catalog(),
        };
      case "project.create": {
        const { primaryHostId } = await bb.sdk.system.config();
        if (!primaryHostId)
          throw new ApiError("host_unavailable", "BB local host daemon is unavailable");
        return bb.sdk.projects.create({
          name: string(data.name, "name"),
          source: { type: "local_path", hostId: primaryHostId, path: string(data.folder, "folder") },
        });
      }
      case "project.configure":
        return bb.sdk.projects.update({
          projectId: string(data.projectId, "projectId"),
          name: string(data.name, "name"),
        });
      case "project.remove":
        return bb.sdk.projects.delete({
          projectId: string(data.projectId, "projectId"),
        });
      // Attaching is idempotent; Java repeats it to notice a restarted plugin.
      case "session.attach": {
        if (data.protocol !== PROTOCOL)
          throw new ApiError(
            "protocol_mismatch",
            "Minecraft plugin protocol changed; rebuild and restart the mod.",
          );
        const worldId = uuid(data.worldId, "worldId");
        const joined = worlds.attach({
          worldId,
          worldSessionId: uuid(data.worldSessionId, "worldSessionId"),
          callbackUrl: loopbackUrl(data.callbackUrl),
          callbackToken: string(data.callbackToken, "callbackToken"),
        });
        if (joined) await threads.reconnect(worldId);
        return { protocol: PROTOCOL };
      }
      case "session.detach": {
        worlds.detach(uuid(data.worldId, "worldId"), string(data.worldSessionId, "worldSessionId"));
        return {};
      }
      case "agent.markRead":
        return bb.sdk.threads.markRead({ threadId: threadId() });
      case "agent.stop":
        return agent && !agent.threadId ? {} : bb.sdk.threads.stop({ threadId: threadId() });
      case "agent.archive":
        return agents.archive(live(), requireAgent(), data.archived === true);
      case "timeline":
        return bb.sdk.threads.timeline({
          ...(data.query === undefined ? {} : object(data.query, "query")),
          threadId: threadId(),
        } as Args<Sdk["threads"]["timeline"]>);
      case "chat.asset":
        return data.kind === "mermaid"
          ? chat.diagram(string(data.source, "source"))
          : chat.image(threadId(), string(data.source, "source"));
      case "chat.open":
        return chat.open(threadId(), string(data.target, "target"));
      case "timeline.summary":
        return bb.sdk.threads.timelineTurnSummaryDetails({
          ...object(data.query, "query"),
          threadId: threadId(),
        } as Args<Sdk["threads"]["timelineTurnSummaryDetails"]>);
      case "interaction.resolve":
        return bb.sdk.threads.interactions.resolve({
          threadId: threadId(),
          interactionId: string(data.interactionId, "interactionId"),
          resolution: data.resolution,
        } as Args<Sdk["threads"]["interactions"]["resolve"]>);
      case "interaction.respond":
        return bb.sdk.threads.interactions.respond({
          threadId: threadId(),
          interactionId: string(data.interactionId, "interactionId"),
          value: data.value!,
        });
      case "interaction.cancel":
        return bb.sdk.threads.interactions.cancel({
          threadId: threadId(),
          interactionId: string(data.interactionId, "interactionId"),
        });
      default:
        throw new ApiError("unknown_operation", `Unknown operation ${op}`);
    }
  }
  // BB's default route check refuses web pages, as BB's own API does; other local programs can already use that API.
  bb.http.route("POST", "/v1/rpc", async (context) => {
    try {
      if (Number(context.req.header("content-length") ?? 0) > MAX_BODY_BYTES)
        throw new ApiError("request_too_large", "Request exceeds 1 MiB");
      const raw = await context.req.text();
      if (Buffer.byteLength(raw) > MAX_BODY_BYTES)
        throw new ApiError("request_too_large", "Request exceeds 1 MiB");
      return context.json({
        ok: true,
        result: await dispatch(object(JSON.parse(raw))),
      });
    } catch (error) {
      return context.json(
        {
          ok: false,
          error: {
            code: error instanceof ApiError ? error.code : "bb_error",
            message: describe(error),
          },
        },
        error instanceof ApiError ? 409 : 502,
      );
    }
  });
}
