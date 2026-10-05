import type { BbPluginApi } from "@get-bb/plugin-sdk";
import type { MinecraftWorlds } from "./minecraft.js";
import type { MinecraftThreads } from "./threads.js";
import type { MinecraftProjects } from "./projects.js";
import {
  ApiError,
  object,
  type Agent,
  type Args,
  type ObjectValue,
  type Session,
  type Sdk,
  type SpawnOptions,
} from "./protocol.js";

// The UI sends body choices alongside BB choices; keep that translation out of Java.
const bodyFields = new Set([
  "name",
  "body",
  "mode",
  "cheats",
  "color",
  "behaviors",
  "stationId",
  "minecraftAccess",
]);
function split(request: ObjectValue) {
  const settings: ObjectValue = {},
    draft: ObjectValue = {};
  for (const [key, value] of Object.entries(request)) {
    if (value == null || value === "" || key === "initialTask") continue;
    (bodyFields.has(key) ? settings : draft)[key] = value;
  }
  return { settings, draft };
}
const inputText = (text: string) => ({ type: "text" as const, text, mentions: [] });

/** One orchestration path for the Minecraft UI and the embodied-agent CLI. */
export function minecraftAgents(
  bb: BbPluginApi,
  worlds: MinecraftWorlds,
  threads: MinecraftThreads,
  projects: MinecraftProjects,
) {
  const starting = new Set<string>();

  async function get(live: Session, agentId: string) {
    const agent = (await worlds.agents(live)).find((row) => row.agentId === agentId);
    if (!agent) throw new ApiError("agent_missing", "This Minecraft body is unavailable");
    return agent;
  }

  async function start(live: Session, agent: Agent, message: ObjectValue) {
    if (starting.has(agent.agentId))
      throw new ApiError("already_starting", "This body's conversation is already starting");
    starting.add(agent.agentId);
    try {
      const { mode: _mode, ...request } = message;
      const selection = await projects.selection(live, {
        ...agent.draft,
        ...request,
      } as Partial<SpawnOptions>);
      return await threads.start(
        live,
        agent.agentId,
        agent.minecraftAccess,
        selection as SpawnOptions,
      );
    } finally {
      starting.delete(agent.agentId);
    }
  }

  async function create(
    live: Session,
    request: ObjectValue,
    caller?: { threadId: string; signal: AbortSignal },
  ) {
    const { worktree, ...choicesRequest } = request;
    const { settings, draft } = split(choicesRequest);
    const selected = await projects.selection(
      live,
      draft as Partial<SpawnOptions>,
      typeof worktree === "boolean" ? worktree : undefined,
    );
    const { input, prompt, ...choices } = selected as SpawnOptions;
    const args = {
      settings,
      projectId: selected.projectId,
      minecraftAccess: settings.minecraftAccess !== false,
      draft: choices,
    };
    const agent = (
      caller
        ? await worlds.toolCallback(live, caller, "body.create", {
            ...args,
            agentId: (await worlds.caller(caller.threadId)).agent.agentId,
          })
        : await worlds.callback(live, "body.create", args)
    ) as Agent;
    caller?.signal.throwIfAborted();
    const task = typeof request.initialTask === "string" ? request.initialTask : prompt;
    const first = input ?? (task ? [inputText(task)] : []);
    const thread = first.length ? await start(live, agent, { input: first }) : undefined;
    return {
      agentId: agent.agentId,
      name: agent.name,
      body: agent.body,
      threadId: thread?.id ?? "",
      thread,
    };
  }

  async function send(live: Session, agent: Agent, message: ObjectValue) {
    const pointing = message.pointing ? object(message.pointing) : {};
    const input: ObjectValue[] = [
      inputText(
        String(message.text ?? "") +
          (Object.keys(pointing).length
            ? `\nMinecraft pointing context: ${JSON.stringify(pointing)}`
            : ""),
      ),
    ];
    if (Array.isArray(message.images))
      for (const path of message.images) input.push({ type: "localImage", path });
    const modes = new Set(["auto", "queue-if-active", "steer-if-active", "start", "steer"]);
    const delivery = String(message.delivery ?? "");
    const send: ObjectValue = {
      input,
      mode: modes.has(delivery) ? delivery : delivery === "queue" ? "queue-if-active" : "auto",
    };
    for (const key of ["model", "reasoningLevel", "serviceTier", "permissionMode"])
      if (message[key]) send[key] = message[key];
    if (!agent.threadId) return start(live, agent, send);
    return threads.withImages(agent.projectId, send, (prepared) =>
      bb.sdk.threads.send({
        ...prepared,
        threadId: agent.threadId,
      } as Args<Sdk["threads"]["send"]>),
    );
  }

  async function settings(live: Session, agent: Agent, request: ObjectValue) {
    const { settings, draft: patch } = split(request);
    if (agent.threadId && (patch.permissionMode || patch.serviceTier))
      throw new ApiError(
        "composer_setting",
        "Choose permissions and speed in the message composer; BB saves them when you send.",
      );
    if (Object.keys(settings).length)
      await worlds.callback(live, "body.settings", { agentId: agent.agentId, settings });
    if (Object.keys(patch).length) {
      if (agent.threadId)
        await bb.sdk.threads.update({ ...patch, threadId: agent.threadId } as Args<
          Sdk["threads"]["update"]
        >);
      else await worlds.callback(live, "body.draft", { agentId: agent.agentId, patch });
    }
  }

  async function archive(live: Session, agent: Agent, archived: boolean) {
    if (agent.threadId) {
      await (archived
        ? bb.sdk.threads.archive({ threadId: agent.threadId })
        : bb.sdk.threads.unarchive({ threadId: agent.threadId }));
      await threads.sync(live, agent.agentId, agent.threadId);
    } else
      await worlds.callback(live, "body.sync", {
        agentId: agent.agentId,
        threadId: "",
        state: archived ? "suspended" : "present",
        running: false,
      });
  }

  async function sync(live: Session) {
    const rows = await projects.list(live);
    await Promise.all(
      (await worlds.agents(live))
        .filter((agent) => agent.threadId)
        .map((agent) => threads.sync(live, agent.agentId, agent.threadId)),
    );
    return { projects: rows };
  }
  return { get, create, send, settings, archive, sync };
}
export type MinecraftAgents = ReturnType<typeof minecraftAgents>;
