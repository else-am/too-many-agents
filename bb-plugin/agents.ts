import type { BbPluginApi } from "@get-bb/plugin-sdk";
import type { MinecraftWorlds } from "./minecraft.js";
import type { MinecraftThreads } from "./threads.js";
import type { MinecraftProjects } from "./projects.js";
import {
  ApiError,
  object,
  string,
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
  "behaviors",
  "stationId",
  "minecraftAccess",
]);
function split(request: ObjectValue) {
  const settings: ObjectValue = {},
    draft: ObjectValue = {};
  for (const [key, value] of Object.entries(request)) {
    if (value == null || value === "" || key === "initialTask" || key === "color") continue;
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

  // One record per role; serialize writes so creates and updates cannot race.
  let roleWrites: Promise<unknown> = Promise.resolve();
  const roleKey = (name: string) => `role:${name}`;
  function roleName(value: unknown) {
    const name = string(value, "role name");
    if (name !== name.trim() || name.length > 80 || /[\u0000-\u001f]/.test(name))
      throw new ApiError("invalid_role", "Role name must contain 1–80 printable characters.");
    return name;
  }
  async function roleGet(name: string) {
    const role = await bb.storage.kv.get<ObjectValue>(roleKey(roleName(name)));
    if (!role) throw new ApiError("role_missing", `Unknown role: ${name}`);
    return role;
  }
  async function roleList() {
    const keys = await bb.storage.kv.list("role:");
    const rows = await Promise.all(keys.sort().map(key => bb.storage.kv.get<ObjectValue>(key)));
    return rows.filter((row): row is ObjectValue => row != null);
  }
  async function roleSave(live: Session, value: unknown, mode: "replace" | "create" | "update" = "replace") {
    let role = object(value, "role");
    const name = roleName(role.name);
    const write = roleWrites.catch(() => undefined).then(async () => {
      const previous = await bb.storage.kv.get<ObjectValue>(roleKey(name));
      if (mode === "create" && previous) throw new ApiError("role_exists", `Role already exists: ${name}`);
      if (mode === "update" && !previous) throw new ApiError("role_missing", `Unknown role: ${name}`);
      if (mode === "update") role = {
        ...previous!, ...role,
        bb: { ...object(previous!.bb), ...object(role.bb ?? {}) },
        body: { ...object(previous!.body), ...object(role.body ?? {}) },
      };
      if (mode === "create") role = { instructions: "", bb: {}, body: {}, ...role };
      if (Object.keys(role).some(key => !["name", "instructions", "bb", "body"].includes(key)) ||
          typeof role.instructions !== "string" || role.instructions.length > 64_000)
        throw new ApiError("invalid_role", "Use name, instructions (up to 64000 characters), bb and body only.");
      const choices = object(role.bb, "role.bb");
      object(role.body, "role.body");
      for (const [key, value] of Object.entries(choices)) {
        if (!["providerId", "model", "reasoningLevel", "serviceTier", "permissionMode", "worktree"].includes(key) ||
            (key === "worktree" ? typeof value !== "boolean" : typeof value !== "string" || !value.trim()))
          throw new ApiError("invalid_role", `Invalid role.bb field: ${key}`);
      }
      if (choices.reasoningLevel && !["none", "low", "medium", "high", "xhigh", "max", "ultra", "ultracode"].includes(String(choices.reasoningLevel)))
        throw new ApiError("invalid_role", "Invalid reasoningLevel");
      if (choices.permissionMode && !["accept-edits", "auto", "full"].includes(String(choices.permissionMode)))
        throw new ApiError("invalid_role", "Invalid permissionMode");
      await worlds.callback(live, "body.validate", { settings: role.body });
      await bb.storage.kv.set(roleKey(name), role);
    });
    roleWrites = write;
    await write;
    return roleGet(name);
  }
  async function roleDelete(name: string) {
    const write = roleWrites.catch(() => undefined).then(() => bb.storage.kv.delete(roleKey(roleName(name))));
    roleWrites = write;
    await write;
  }
  async function roleCapture(live: Session, agent: Agent | undefined, request: ObjectValue) {
    const role = { ...object(request.role) };
    if (agent?.threadId) {
      const thread = await bb.sdk.threads.get({ threadId: agent.threadId });
      const execution = await bb.sdk.threads.defaultExecutionOptions({ threadId: agent.threadId });
      const environment = thread.environmentId
        ? await bb.sdk.environments.get({ environmentId: thread.environmentId }) : undefined;
      role.bb = {
        providerId: thread.providerId,
        model: execution?.model,
        reasoningLevel: execution?.reasoningLevel,
        permissionMode: execution?.permissionMode,
        serviceTier: execution?.serviceTier,
        worktree: environment?.isWorktree === true,
      } as ObjectValue;
      // Omit absent optional execution fields.
      role.bb = Object.fromEntries(Object.entries(role.bb as ObjectValue).filter(([, value]) => value != null && value !== ""));
    }
    if (role.instructions === undefined) {
      const existing = await bb.storage.kv.get<ObjectValue>(roleKey(roleName(role.name)));
      role.instructions = existing?.instructions ?? "";
    }
    return roleSave(live, role);
  }

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
      const instructions = await bb.storage.kv.get<string>(`instructions:${agent.agentId}`);
      if (instructions && Array.isArray(request.input))
        request.input = [inputText(instructions + "\n\n"), ...request.input];
      const selection = await projects.selection(live, {
        ...agent.draft,
        ...request,
      } as Partial<SpawnOptions>);
      const thread = await threads.start(
        live,
        agent.agentId,
        agent.minecraftAccess,
        selection as SpawnOptions,
      );
      await bb.storage.kv.delete(`instructions:${agent.agentId}`);
      return thread;
    } finally {
      starting.delete(agent.agentId);
    }
  }

  async function create(
    live: Session,
    request: ObjectValue,
    caller?: { threadId: string; signal: AbortSignal },
  ) {
    const role = request.role ? await roleGet(string(request.role, "role")) : undefined;
    const explicit = Object.fromEntries(Object.entries(request).filter(([, value]) => value !== undefined));
    request = { ...(role ? object(role.body) : {}), ...(role ? object(role.bb) : {}), ...explicit };
    const { worktree, role: _role, roleInstructions, ...choicesRequest } = request;
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
    const instructions = roleInstructions ?? role?.instructions;
    if (typeof instructions === "string" && instructions)
      await bb.storage.kv.set(`instructions:${agent.agentId}`, instructions);
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
            ? `\n\n[minecraft user context]\n${Object.entries(pointing).map(([key, value]) => `${key}: ${typeof value === "string" ? value : JSON.stringify(value)}`).join("\n")}\n[/minecraft user context]`
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
  return { get, create, send, settings, archive, sync, roleGet, roleList, roleSave, roleDelete, roleCapture };
}
export type MinecraftAgents = ReturnType<typeof minecraftAgents>;
