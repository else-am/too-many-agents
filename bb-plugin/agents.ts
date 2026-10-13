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
const bodyFields = new Set(["name", "body", "mode", "behavior", "behaviors"]);
const executionFields = ["providerId", "model", "reasoningLevel", "serviceTier", "permissionMode"] as const;
const permissionModes = ["accept-edits", "auto", "full"];
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

  async function get(live: Session, agentId: string) {
    const agent = (await worlds.agents(live)).find((row) => row.agentId === agentId);
    if (!agent) throw new ApiError("agent_missing", "This Minecraft body is unavailable");
    return agent;
  }

  /** BB's own defaults for a project: its saved choices, else the default provider's default model. */
  async function projectDefaults(projectId: string): Promise<ObjectValue> {
    const saved = await bb.sdk.projects.defaultExecutionOptions({ projectId });
    const configured = saved?.providerId || (await bb.sdk.system.config()).generalSettings.defaultProviderId;
    const catalog = await bb.sdk.system.executionOptions(configured ? { providerId: configured } : {});
    const providerId = configured || catalog.providers.find((row) => row.available)?.id;
    if (!providerId) throw new ApiError("provider_unavailable", "No BB provider is available.");
    const model = [...catalog.models, ...catalog.selectedOnlyModels].find((row) => row.model === saved?.model)
      ?? catalog.models.find((row) => row.isDefault);
    return {
      providerId,
      ...(saved?.model || model ? { model: saved?.model || model!.model } : {}),
      ...(saved?.reasoningLevel || model ? { reasoningLevel: saved?.reasoningLevel || model!.defaultReasoningEffort } : {}),
      serviceTier: saved?.serviceTier || "default",
      permissionMode: saved?.permissionMode || "auto",
    };
  }

  /** The UI's starting choices for a project: BB's defaults, then the choices of its last ordinary UI agent. */
  async function draftDefaults(projectId: string) {
    const remembered = await bb.storage.kv.get<ObjectValue>(`last-execution:${projectId}`);
    return { ...(await projectDefaults(projectId)), ...remembered };
  }

  /** Catch invalid pinned or explicit models before a station is claimed. BB remains authoritative at start. */
  async function validateExecution(choices: Partial<SpawnOptions>) {
    if (!choices.providerId) return;
    const catalog = await bb.sdk.system.executionOptions({
      providerId: choices.providerId,
      ...(choices.environment?.type === "reuse" ? { environmentId: choices.environment.environmentId } : {}),
    });
    const provider = catalog.providers.find((row) => row.id === choices.providerId);
    if (!provider?.available || catalog.modelLoadError)
      throw new ApiError("provider_unavailable", catalog.modelLoadError?.detail ?? "The selected provider is unavailable.");
    const models = [...catalog.models, ...catalog.selectedOnlyModels];
    const model = choices.model ? models.find((row) => row.model === choices.model) : models.find((row) => row.isDefault);
    if (!model) throw new ApiError("invalid_model", `Unknown model ${choices.model ?? "(default)"} for ${choices.providerId}.`);
    choices.model = model.model;
    choices.reasoningLevel ??= model.defaultReasoningEffort;
    choices.serviceTier ||= "default";
    choices.permissionMode ??= "auto";
    if (model.supportedReasoningEfforts.length && !model.supportedReasoningEfforts.some((row) => row.reasoningEffort === choices.reasoningLevel))
      throw new ApiError("invalid_reasoning_level", `The model does not support ${choices.reasoningLevel} reasoning.`);
    const tiers = model.supportedServiceTiers ?? provider.serviceTiers ?? [];
    if (choices.serviceTier && choices.serviceTier !== "default" && !tiers.some((row) => row.id === choices.serviceTier))
      throw new ApiError("invalid_service_tier", "The model does not support this service tier.");
    if (choices.permissionMode && !provider.capabilities.permissionModes.includes(choices.permissionMode))
      throw new ApiError("invalid_permission_mode", "The provider does not support this permission mode.");
  }

  async function station(live: Session, idOrName: string) {
    const world = await projects.metadata(live);
    const rows = (Array.isArray(world.stations) ? world.stations : []).map((row) => object(row));
    const found = rows.find((row) => row.id === idOrName) ?? rows.find((row) => typeof row.name === "string" && row.name.toLowerCase() === idOrName.trim().toLowerCase());
    if (!found) throw new ApiError("station_missing", `No station is named ${idOrName}.`);
    if (!found.projectId)
      throw new ApiError("station_without_project", `Station ${found.name} has no project yet.`);
    return found;
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
      await validateExecution(selection);
      const thread = await threads.start(live, agent.agentId, selection as SpawnOptions);
      await bb.storage.kv.delete(`instructions:${agent.agentId}`);
      // Remember what BB accepted, so the next ordinary agent in this project starts with it.
      if (await bb.storage.kv.get<boolean>(`remember:${agent.agentId}`)) {
        const execution = await bb.sdk.threads.defaultExecutionOptions({ threadId: thread.id });
        if (execution)
          await bb.storage.kv.set(`last-execution:${selection.projectId}`, {
            providerId: thread.providerId,
            model: execution.model,
            reasoningLevel: execution.reasoningLevel,
            serviceTier: execution.serviceTier,
            permissionMode: execution.permissionMode,
          });
        await bb.storage.kv.delete(`remember:${agent.agentId}`);
      }
      return thread;
    } finally {
      starting.delete(agent.agentId);
    }
  }

  /**
   * A stationed body takes the station's project, body, behaviors, instructions and pinned BB choices; BB's project
   * defaults fill the rest. Other children inherit their parent's execution; independent spawns use project defaults.
   * Explicit choices win, and an agent caller can never grant a stronger permission mode than its own.
   */
  async function create(
    live: Session,
    request: ObjectValue,
    caller?: { threadId: string; signal: AbortSignal },
  ) {
    for (const key of ["role", "minecraftAccess", "notifyInChat"])
      if (request[key] != null)
        throw new ApiError("invalid_request", `${key} is no longer a spawn choice. Minecraft access follows the project.`);
    const { worktree, stationId, ...choicesRequest } = request;
    const atStation = typeof stationId === "string" && stationId ? await station(live, stationId) : undefined;
    if (atStation) {
      if (choicesRequest.projectId && choicesRequest.projectId !== atStation.projectId)
        throw new ApiError("station_project", `Station ${atStation.name} belongs to another project.`);
      choicesRequest.projectId = atStation.projectId;
    }
    const { settings, draft } = split(choicesRequest);
    const selected = await projects.selection(
      live,
      draft as Partial<SpawnOptions>,
      typeof worktree === "boolean" ? worktree : undefined,
    );
    const { input, prompt, ...choices } = selected as SpawnOptions & ObjectValue;
    const explicit = choices.permissionMode;
    const parent = !atStation && choices.parentThreadId
      ? await bb.sdk.threads.get({ threadId: choices.parentThreadId }) : undefined;
    const defaults: ObjectValue = parent
      ? { ...(await bb.sdk.threads.defaultExecutionOptions({ threadId: parent.id })), providerId: parent.providerId }
      : await projectDefaults(selected.projectId);
    for (const key of executionFields)
      if (!choices[key] && atStation?.[key]) choices[key] = atStation[key] as never;
    // A different provider must choose its own model/effort/tier rather than inherit another provider's values.
    const sameProvider = !choices.providerId || choices.providerId === defaults.providerId;
    for (const key of executionFields)
      if (!choices[key] && defaults[key] && (sameProvider || key === "permissionMode" || key === "providerId"))
        choices[key] = defaults[key] as never;
    if (atStation && settings.behaviors === undefined) settings.behaviors = atStation.behaviors;
    if (caller) {
      const own = (await bb.sdk.threads.defaultExecutionOptions({ threadId: caller.threadId }))?.permissionMode;
      if (!own) throw new ApiError("caller_permissions_unavailable", "Cannot read the caller's current permission mode.");
      if (explicit && permissionModes.indexOf(explicit) > permissionModes.indexOf(own))
        throw new ApiError("permission_exceeds_caller", "A spawned agent cannot exceed the caller's BB permission mode.");
      if (!choices.permissionMode || permissionModes.indexOf(choices.permissionMode) > permissionModes.indexOf(own))
        choices.permissionMode = own;
    }
    await validateExecution(choices as Partial<SpawnOptions>);
    const args = {
      settings,
      projectId: selected.projectId,
      ...(atStation ? { stationId: atStation.id as string } : {}),
      draft: choices as ObjectValue,
    };
    const agent = (
      caller
        ? await worlds.toolCallback(live, caller, "body.create", {
            ...args,
            agentId: (await worlds.caller(caller.threadId)).agent.agentId,
          })
        : await worlds.callback(live, "body.create", args)
    ) as Agent;
    if (typeof atStation?.instructions === "string" && atStation.instructions)
      await bb.storage.kv.set(`instructions:${agent.agentId}`, atStation.instructions);
    // Only the player's ordinary agents shape the next draft's choices.
    if (!caller && !atStation && !choices.parentThreadId) await bb.storage.kv.set(`remember:${agent.agentId}`, true);
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
    for (const key of ["role", "minecraftAccess", "notifyInChat", "stationId"])
      if (request[key] != null) throw new ApiError("invalid_request", `${key} is not an agent setting.`);
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
  return { get, create, send, settings, archive, sync, draftDefaults, projectDefaults, station };
}
export type MinecraftAgents = ReturnType<typeof minecraftAgents>;
