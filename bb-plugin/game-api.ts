import type { BbPluginApi } from "@get-bb/plugin-sdk";
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
) {
  const chat = chatAssets(bb);
  async function dispatch(data: ObjectValue): Promise<unknown> {
    const op = string(data.op, "op");
    const threadId = () => string(data.threadId, "threadId");
    const args = () => ({ ...object(data.args, "args"), threadId: threadId() });
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
      case "projects":
        return bb.sdk.projects.list({ includePersonal: true });
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
      case "project.executionOptions":
        return bb.sdk.projects.defaultExecutionOptions({
          projectId: string(data.projectId, "projectId"),
        });
      case "environment.providers":
        return bb.sdk.environments.listProviders(optional(data, "projectId", "hostId"));
      case "usage":
        return bb.sdk.system.usageLimits(optional(data, "providerId", "hostId"));
      case "backend.status":
        return {
          protocol: PROTOCOL,
          runtimeVersion: (await bb.sdk.system.version()).currentVersion,
          providers: await bb.sdk.providers.catalog(),
        };
      case "project.create": {
        const source = object(data.source, "source");
        if (source.hostId === "local" || source.hostId === undefined) {
          const config = await bb.sdk.system.config();
          if (!config.primaryHostId)
            throw new ApiError("host_unavailable", "BB local host daemon is unavailable");
          source.hostId = config.primaryHostId;
        }
        return bb.sdk.projects.create({
          name: string(data.name, "name"),
          source,
        } as Args<Sdk["projects"]["create"]>);
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
      case "project.source.update":
        return bb.sdk.projects.sources.update({
          projectId: string(data.projectId, "projectId"),
          sourceId: string(data.sourceId, "sourceId"),
          type: "local_path",
          path: string(data.path, "path"),
        });
      // Attaching is idempotent; Java repeats it to notice a restarted plugin.
      case "session.attach": {
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
      case "threads.read": {
        if (!Array.isArray(data.threadIds))
          throw new ApiError("invalid_request", "threadIds must be an array");
        const ids = data.threadIds.map((id) => string(id, "threadId"));
        const rows = await Promise.all(ids.map((id) => threads.snapshot(id)));
        return Object.fromEntries(ids.map((id, index) => [id, rows[index]]));
      }
      case "agent.read":
        return threads.read(threadId(), true);
      case "agent.start": {
        return threads.start(
          worlds.session(uuid(data.worldId, "worldId")),
          uuid(data.agentId, "agentId"),
          data.minecraftAccess === true,
          uuid(data.nonce, "nonce"),
          object(data.spawn, "spawn"),
        );
      }
      case "agent.send": {
        const id = threadId();
        const send = object(data.send, "send");
        if (
          !Array.isArray(send.input) ||
          !send.input.some(
            (value) =>
              value &&
              typeof value === "object" &&
              !Array.isArray(value) &&
              value.type === "localImage",
          )
        )
          return bb.sdk.threads.send({ ...send, threadId: id } as Args<Sdk["threads"]["send"]>);
        const thread = await bb.sdk.threads.get({ threadId: id });
        return threads.withImages(thread.projectId, send, (prepared) =>
          bb.sdk.threads.send({ ...prepared, threadId: id } as Args<Sdk["threads"]["send"]>),
        );
      }
      case "agent.update":
        return bb.sdk.threads.update({
          ...object(data.patch, "patch"),
          threadId: threadId(),
        } as Args<Sdk["threads"]["update"]>);
      case "agent.markRead":
        return bb.sdk.threads.markRead({ threadId: threadId() });
      case "agent.stop":
        return bb.sdk.threads.stop({ threadId: threadId() });
      case "agent.archive":
        return bb.sdk.threads.archive({ threadId: threadId() });
      case "agent.unarchive":
        return bb.sdk.threads.unarchive({ threadId: threadId() });
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
      case "queue.create":
        return bb.sdk.threads.queuedMessages.create(
          args() as Args<Sdk["threads"]["queuedMessages"]["create"]>,
        );
      case "queue.update":
        return bb.sdk.threads.queuedMessages.update(
          args() as Args<Sdk["threads"]["queuedMessages"]["update"]>,
        );
      case "queue.delete":
        return bb.sdk.threads.queuedMessages.delete(
          args() as Args<Sdk["threads"]["queuedMessages"]["delete"]>,
        );
      case "queue.send":
        return bb.sdk.threads.queuedMessages.send(
          args() as Args<Sdk["threads"]["queuedMessages"]["send"]>,
        );
      case "queue.reorder":
        return bb.sdk.threads.queuedMessages.reorder(
          args() as Args<Sdk["threads"]["queuedMessages"]["reorder"]>,
        );
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
