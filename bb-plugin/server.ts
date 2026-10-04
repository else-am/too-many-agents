import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import type { BbPluginApi, PluginAgentToolContext, PluginAgentToolResult } from "@get-bb/plugin-sdk";

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
type ObjectValue = { [key: string]: Json };
/** An attached Minecraft world and where to reach it. */
interface Session { worldId: string; worldSessionId: string; callbackUrl: string; callbackToken: string }
/** Minecraft's own agent record; Java is its only owner. */
interface Agent {
  agentId: string;
  name: string;
  threadId: string;
  parentAgentId: string;
  projectId: string;
  minecraftAccess: boolean;
  settings: ObjectValue;
}
interface Identity { worldId: string; agentId: string }
class ApiError extends Error {
  constructor(readonly code: string, message: string) { super(message); }
}
const PROTOCOL = 2;
const START_EXPIRY_MS = 10_000;
const MAX_BODY_BYTES = 1024 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
function object(value: unknown, label = "arguments"): ObjectValue {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new ApiError("invalid_request", `${label} must be an object`);
  return value as ObjectValue;
}
function string(value: unknown, label: string): string {
  if (typeof value !== "string" || !value.trim()) throw new ApiError("invalid_request", `${label} is required`);
  return value;
}
function uuid(value: unknown, label: string): string {
  const id = string(value, label);
  if (!UUID.test(id)) throw new ApiError("invalid_request", `${label} must be a UUID`);
  return id;
}
function optional(data: ObjectValue, ...keys: string[]): ObjectValue {
  return Object.fromEntries(keys.filter(key => typeof data[key] === "string").map(key => [key, data[key]!]));
}
function toolError(message: string): PluginAgentToolResult {
  return { content: [{ type: "text", text: message }], isError: true };
}
function describe(error: unknown): string { return error instanceof Error ? error.message : String(error); }
function loopbackUrl(value: unknown): string {
  const url = new URL(string(value, "callbackUrl"));
  if (url.protocol !== "http:" || !["127.0.0.1", "[::1]"].includes(url.hostname) || url.username || url.password || url.search || url.hash)
    throw new ApiError("invalid_request", "callbackUrl must be literal loopback HTTP without credentials or query");
  return url.href;
}
const schema = (properties: Record<string, unknown>, required: string[] = []) => ({ type: "object", properties, required, additionalProperties: false });
const textField = { type: "string", minLength: 1 };
const agentTarget = { agentId: textField };
const THREAD_UPDATE_FIELDS = new Set(["model", "reasoningLevel", "parentThreadId", "sectionId", "title", "visibility"]);
function threadUpdate(value: unknown): ObjectValue {
  const patch = object(value, "patch");
  for (const key of Object.keys(patch)) if (!THREAD_UPDATE_FIELDS.has(key))
    throw new ApiError("invalid_request", `BB cannot update ${key} on an existing thread. Permission mode and service tier can be selected on the next message.`);
  return patch;
}
type Sdk = BbPluginApi["sdk"];
type Args<F extends (...args: never[]) => unknown> = Parameters<F>[0];

export default async function minecraft(bb: BbPluginApi) {
  const sessions = new Map<string, Session>();
  // Thread metadata is writable by others, so it only names a candidate. Java checks each thread against its own record.
  const identities = new Map<string, Identity | null>();
  // Threads this plugin is creating, so an interrupted spawn can still be bound when BB reports the thread.
  const spawning = new Map<string, Identity>();
  const waiters = new Map<string, Set<() => void>>();

  function session(worldId: string): Session {
    const live = sessions.get(worldId);
    if (!live) throw new ApiError("world_disconnected", "Minecraft world is disconnected");
    return live;
  }
  async function identify(threadId: string): Promise<Identity | null> {
    if (identities.has(threadId)) return identities.get(threadId)!;
    const metadata = await bb.sdk.threads.getPluginMetadata({ threadId });
    const identity = typeof metadata.worldId === "string" && typeof metadata.agentId === "string" ? { worldId: metadata.worldId, agentId: metadata.agentId } : null;
    identities.set(threadId, identity);
    return identity;
  }
  async function callback(live: Session, op: string, args: Record<string, unknown>, signal?: AbortSignal): Promise<unknown> {
    let response: Response;
    try {
      response = await fetch(live.callbackUrl, {
        method: "POST",
        headers: { "content-type": "application/json", authorization: `Bearer ${live.callbackToken}` },
        body: JSON.stringify({ protocol: PROTOCOL, op, worldId: live.worldId, worldSessionId: live.worldSessionId,
          requestId: randomUUID(), expiresAt: Date.now() + START_EXPIRY_MS, ...args }),
        signal: signal ?? AbortSignal.timeout(120_000),
      });
    } catch (error) {
      if (signal?.aborted || error instanceof Error && error.name === "AbortError") throw error;
      // Minecraft is gone without detaching; it attaches again if it is still running.
      if (sessions.get(live.worldId) === live) sessions.delete(live.worldId);
      throw new ApiError("world_disconnected", "Minecraft world is disconnected");
    }
    const body = object(await response.json(), "Minecraft response");
    if (!response.ok || body.ok !== true) {
      const error = body.error && typeof body.error === "object" ? object(body.error) : undefined;
      throw new ApiError("minecraft_action_failed", error && typeof error.message === "string" ? error.message : `Minecraft returned HTTP ${response.status}`);
    }
    return body.result;
  }
  /** A world call made for a tool: BB's abort becomes a single cancel for this request. */
  async function toolCallback(live: Session, ctx: PluginAgentToolContext, op: string, args: Record<string, unknown>): Promise<unknown> {
    ctx.signal.throwIfAborted();
    const requestId = randomUUID();
    const controller = new AbortController();
    const abort = () => {
      controller.abort(new Error("Tool call cancelled"));
      void callback(live, "cancel", { cancelRequestId: requestId }, AbortSignal.timeout(2_000)).catch(() => undefined);
    };
    ctx.signal.addEventListener("abort", abort, { once: true });
    try {
      return await callback(live, op, { ...args, requestId, threadId: ctx.threadId }, controller.signal);
    } finally { ctx.signal.removeEventListener("abort", abort); }
  }
  async function agents(live: Session): Promise<Agent[]> {
    return (object(await callback(live, "agents", {}), "agents").agents as unknown as Agent[]);
  }
  async function read(threadId: string, includeTimeline = false) {
    const [thread, executionOptions, interactions, queuedMessages, timeline] = await Promise.all([
      bb.sdk.threads.get({ threadId }), bb.sdk.threads.defaultExecutionOptions({ threadId }), bb.sdk.threads.interactions.list({ threadId }),
      bb.sdk.threads.queuedMessages.list({ threadId }), includeTimeline ? bb.sdk.threads.timeline({ threadId }) : undefined,
    ]);
    return { thread, executionOptions, interactions, queuedMessages, ...(includeTimeline ? { timeline } : {}) };
  }
  async function defaultProjectId(): Promise<string> {
    const personal = (await bb.sdk.projects.list({ includePersonal: true })).find(project => project.kind === "personal");
    if (!personal) throw new ApiError("project_missing", "BB has no Personal project");
    return personal.id;
  }
  /** Start an agent's BB thread. A spawn is never repeated; Java learns the thread even if this request is lost. */
  async function startThread(live: Session, agentId: string, minecraftAccess: boolean, spawn: ObjectValue, parentThreadId?: string) {
    const projectId = typeof spawn.projectId === "string" && spawn.projectId ? spawn.projectId : await defaultProjectId();
    const environment = spawn.environment ? object(spawn.environment, "environment") : { type: "project-default" };
    // Threads working in the project's own folder share its one environment, on the machine that holds it.
    if (environment.environmentProviderId === "project-checkout") {
      const project = (await bb.sdk.projects.list({ includePersonal: true })).find(row => row.id === projectId);
      const source = project?.sources.find(row => row.isDefault) ?? project?.sources[0];
      if (!source) throw new ApiError("project_folder_missing", "This project has no folder");
      const [existing] = await bb.sdk.environments.list({ projectId, environmentProviderId: "project-checkout", hostId: source.hostId, path: source.path });
      Object.assign(environment, existing ? { type: "reuse", environmentId: existing.id, environmentProviderId: undefined }
        : { machine: { type: "existing", hostId: source.hostId }, inputs: {} });
    }
    const nonce = randomUUID();
    spawning.set(nonce, { worldId: live.worldId, agentId });
    try {
      const created = await bb.sdk.threads.spawn({
        ...spawn, projectId, environment, origin: "plugin", originPluginId: bb.pluginId,
        pluginMetadata: { worldId: live.worldId, agentId, minecraftAccess, nonce },
        ...(parentThreadId ? { parentThreadId } : {}),
      } as unknown as Args<Sdk["threads"]["spawn"]>);
      identities.set(created.id, { worldId: live.worldId, agentId });
      await callback(live, "changed", { agentId, threadId: created.id, bind: true }).catch(() => undefined);
      return created;
    } finally { spawning.delete(nonce); }
  }

  async function dispatch(data: ObjectValue): Promise<unknown> {
    const op = string(data.op, "op");
    const threadId = () => string(data.threadId, "threadId");
    const args = () => ({ ...object(data.args, "args"), threadId: threadId() });
    switch (op) {
      case "hello": return { protocol: PROTOCOL, pluginId: bb.pluginId, runtimeVersion: (await bb.sdk.system.version()).currentVersion };
      case "catalog": return bb.sdk.providers.models(optional(data, "providerId", "hostId", "environmentId") as Args<Sdk["providers"]["models"]>);
      case "projects": return bb.sdk.projects.list({ includePersonal: true });
      case "system.config": return bb.sdk.system.config();
      case "system.defaultProvider.set": {
        if (data.providerId !== null) string(data.providerId, "providerId");
        const config = await bb.sdk.system.config();
        await bb.sdk.system.updateGeneralSettings({ ...config.generalSettings, defaultProviderId: data.providerId as string | null });
        return bb.sdk.system.config();
      }
      case "project.executionOptions": return bb.sdk.projects.defaultExecutionOptions({ projectId: string(data.projectId, "projectId") });
      case "environment.providers": return bb.sdk.environments.listProviders(optional(data, "projectId", "hostId"));
      case "usage": return bb.sdk.system.usageLimits(optional(data, "providerId", "hostId"));
      case "backend.status": return { protocol: PROTOCOL, runtimeVersion: (await bb.sdk.system.version()).currentVersion, providers: await bb.sdk.providers.catalog() };
      case "project.create": {
        const source = object(data.source, "source");
        if (source.hostId === "local" || source.hostId === undefined) {
          const config = await bb.sdk.system.config();
          if (!config.primaryHostId) throw new ApiError("host_unavailable", "BB local host daemon is unavailable");
          source.hostId = config.primaryHostId;
        }
        return bb.sdk.projects.create({ name: string(data.name, "name"), source } as Args<Sdk["projects"]["create"]>);
      }
      case "project.configure": return bb.sdk.projects.update({ projectId: string(data.projectId, "projectId"), name: string(data.name, "name") });
      case "project.remove": return bb.sdk.projects.delete({ projectId: string(data.projectId, "projectId") });
      case "project.source.update": return bb.sdk.projects.sources.update({ projectId: string(data.projectId, "projectId"),
        sourceId: string(data.sourceId, "sourceId"), type: "local_path", path: string(data.path, "path") });
      // Attaching is idempotent; Java repeats it to notice a restarted plugin.
      case "session.attach": {
        const worldId = uuid(data.worldId, "worldId");
        sessions.set(worldId, { worldId, worldSessionId: uuid(data.worldSessionId, "worldSessionId"),
          callbackUrl: loopbackUrl(data.callbackUrl), callbackToken: string(data.callbackToken, "callbackToken") });
        return { protocol: PROTOCOL };
      }
      case "session.detach": {
        const live = sessions.get(uuid(data.worldId, "worldId"));
        if (live?.worldSessionId === data.worldSessionId) sessions.delete(live.worldId);
        return {};
      }
      case "threads.read": {
        if (!Array.isArray(data.threadIds)) throw new ApiError("invalid_request", "threadIds must be an array");
        const ids = data.threadIds.map(id => string(id, "threadId"));
        const rows = await Promise.all(ids.map(id => read(id).catch(error => ({ error: describe(error) }))));
        return Object.fromEntries(ids.map((id, index) => [id, rows[index]]));
      }
      case "agent.read": return read(threadId(), true);
      case "agent.start": {
        const parentThreadId = typeof data.parentThreadId === "string" && data.parentThreadId ? data.parentThreadId : undefined;
        return startThread(session(uuid(data.worldId, "worldId")), uuid(data.agentId, "agentId"), data.minecraftAccess === true, object(data.spawn, "spawn"), parentThreadId);
      }
      case "agent.send": return bb.sdk.threads.send({ ...object(data.send, "send"), threadId: threadId() } as Args<Sdk["threads"]["send"]>);
      case "agent.update": return bb.sdk.threads.update({ ...threadUpdate(data.patch), threadId: threadId() } as Args<Sdk["threads"]["update"]>);
      case "agent.markRead": return bb.sdk.threads.markRead({ threadId: threadId() });
      case "agent.stop": return bb.sdk.threads.stop({ threadId: threadId() });
      case "agent.archive": return bb.sdk.threads.archive({ threadId: threadId() });
      case "agent.unarchive": return bb.sdk.threads.unarchive({ threadId: threadId() });
      case "timeline": return bb.sdk.threads.timeline({ ...(data.query === undefined ? {} : object(data.query, "query")), threadId: threadId() } as Args<Sdk["threads"]["timeline"]>);
      case "timeline.summary": return bb.sdk.threads.timelineTurnSummaryDetails({ ...object(data.query, "query"), threadId: threadId() } as Args<Sdk["threads"]["timelineTurnSummaryDetails"]>);
      case "interaction.resolve": return bb.sdk.threads.interactions.resolve({ threadId: threadId(), interactionId: string(data.interactionId, "interactionId"), resolution: data.resolution } as Args<Sdk["threads"]["interactions"]["resolve"]>);
      case "interaction.respond": return bb.sdk.threads.interactions.respond({ threadId: threadId(), interactionId: string(data.interactionId, "interactionId"), value: data.value! });
      case "interaction.cancel": return bb.sdk.threads.interactions.cancel({ threadId: threadId(), interactionId: string(data.interactionId, "interactionId") });
      case "queue.create": return bb.sdk.threads.queuedMessages.create(args() as Args<Sdk["threads"]["queuedMessages"]["create"]>);
      case "queue.update": return bb.sdk.threads.queuedMessages.update(args() as Args<Sdk["threads"]["queuedMessages"]["update"]>);
      case "queue.delete": return bb.sdk.threads.queuedMessages.delete(args() as Args<Sdk["threads"]["queuedMessages"]["delete"]>);
      case "queue.send": return bb.sdk.threads.queuedMessages.send(args() as Args<Sdk["threads"]["queuedMessages"]["send"]>);
      case "queue.reorder": return bb.sdk.threads.queuedMessages.reorder(args() as Args<Sdk["threads"]["queuedMessages"]["reorder"]>);
      default: throw new ApiError("unknown_operation", `Unknown operation ${op}`);
    }
  }
  // BB's default route check refuses web pages, as BB's own API does; other local programs can already use that API.
  bb.http.route("POST", "/v1/rpc", async context => {
    try {
      if (Number(context.req.header("content-length") ?? 0) > MAX_BODY_BYTES) throw new ApiError("request_too_large", "Request exceeds 1 MiB");
      const raw = await context.req.text();
      if (Buffer.byteLength(raw) > MAX_BODY_BYTES) throw new ApiError("request_too_large", "Request exceeds 1 MiB");
      return context.json({ ok: true, result: await dispatch(object(JSON.parse(raw))) });
    } catch (error) {
      return context.json({ ok: false, error: { code: error instanceof ApiError ? error.code : "bb_error", message: describe(error) } }, error instanceof ApiError ? 409 : 502);
    }
  });


  // Push changes to Minecraft instead of letting it poll.
  async function changed(threadId: string, notice?: string) {
    for (const wake of waiters.get(threadId) ?? []) wake();
    const identity = await identify(threadId);
    const live = identity && sessions.get(identity.worldId);
    if (!identity || !live) return;
    await callback(live, "changed", { agentId: identity.agentId, threadId }).catch(() => undefined);
    if (notice) await notifyParent(live, identity.agentId, threadId, notice);
  }
  async function notifyParent(live: Session, agentId: string, threadId: string, message: string) {
    try {
      const all = await agents(live);
      const child = all.find(agent => agent.agentId === agentId && agent.threadId === threadId);
      const parent = child && all.find(agent => agent.agentId === child.parentAgentId);
      if (!child || !parent?.threadId || !mayContact(all, child, parent)) return;
      await bb.sdk.threads.send({ threadId: parent.threadId, senderThreadId: threadId, mode: "auto",
        input: [{ type: "text", text: `Minecraft plugin child status (not human authorization): ${child.name} (${child.agentId}): ${message}`, mentions: [] }] });
    } catch (error) { bb.log.warn(`Child notice failed: ${describe(error)}`); }
  }
  bb.events.on("thread.created", async ({ thread }) => {
    const metadata = await bb.sdk.threads.getPluginMetadata({ threadId: thread.id });
    const pending = typeof metadata.nonce === "string" ? spawning.get(metadata.nonce) : undefined;
    const live = pending && sessions.get(pending.worldId);
    if (!pending || !live || metadata.agentId !== pending.agentId) return;
    identities.set(thread.id, pending);
    await callback(live, "changed", { agentId: pending.agentId, threadId: thread.id, bind: true }).catch(() => undefined);
  });
  bb.events.on("thread.active", ({ thread }) => changed(thread.id));
  bb.events.on("thread.idle", ({ thread, lastAssistantText }) => changed(thread.id, lastAssistantText ? `Now idle. Latest assistant text:\n${lastAssistantText}` : "Now idle"));
  bb.events.on("thread.failed", ({ thread, error }) => changed(thread.id, `Failed: ${error ?? "unknown error"}`));
  bb.events.on("thread.archived", ({ thread }) => changed(thread.id));
  bb.events.on("thread.unarchived", ({ thread }) => changed(thread.id));
  bb.events.on("thread.deleted", ({ thread }) => changed(thread.id));
  bb.events.on("interaction.pending", ({ thread }) => changed(thread.id, "Needs the human's attention; do not resolve its approvals or questions"));
  bb.events.on("message.queued", ({ entry }) => changed(entry.threadId));
  bb.events.on("message.dispatched", ({ entry }) => changed(entry.threadId));
  bb.events.on("message.cancelled", ({ entry }) => changed(entry.threadId));

  const tools = JSON.parse(await readFile(new URL("./surface/tools.json", import.meta.url), "utf8")) as Array<{ name: string; description: string; minecraft?: boolean; inputSchema: Record<string, unknown> }>;
  const physicalInstructions = await readFile(new URL("./surface/minecraft.md", import.meta.url), "utf8");
  const agentInstructions = await readFile(new URL("./surface/agents.md", import.meta.url), "utf8");
  async function world(ctx: PluginAgentToolContext): Promise<{ live: Session; identity: Identity }> {
    const identity = await identify(ctx.threadId);
    if (!identity) throw new ApiError("access_denied", "This thread has no Minecraft agent association");
    return { live: session(identity.worldId), identity };
  }
  /** The calling agent and its world's records, as Minecraft knows them. */
  async function caller(ctx: PluginAgentToolContext) {
    const { live, identity } = await world(ctx);
    const all = await agents(live);
    const me = all.find(agent => agent.agentId === identity.agentId);
    if (!me || me.threadId !== ctx.threadId) throw new ApiError("access_denied", "This thread is not the Minecraft agent's conversation");
    return { live, me, all };
  }
  const physicalTools = tools.filter(tool => tool.minecraft || tool.name === "notify_user");
  for (const tool of physicalTools) bb.agents.registerTool({
    name: tool.name, description: tool.description, parameters: tool.inputSchema,
    // Instructions on individual tools avoid the configure contribution's 4096-character limit.
    ...(tool.name === "minecraft_observe" ? { instructions: physicalInstructions.slice(0, 4096) } : {}),
    async execute(args: unknown, ctx: PluginAgentToolContext): Promise<PluginAgentToolResult> {
      try {
        const { live, identity } = await world(ctx);
        return await toolCallback(live, ctx, "tool", { agentId: identity.agentId, tool: tool.name, arguments: args }) as PluginAgentToolResult;
      } catch (error) { return toolError(`${describe(error)}. No automatic retry was attempted.`); }
    },
  });
  function mayContact(all: Agent[], from: Agent, to: Agent): boolean {
    const related = (a: Agent, b: Agent) => {
      const seen = new Set<string>();
      for (let current: Agent | undefined = a; current?.parentAgentId && !seen.has(current.agentId); current = all.find(agent => agent.agentId === current!.parentAgentId)) {
        seen.add(current.agentId);
        if (current.parentAgentId === b.agentId) return true;
      }
      return false;
    };
    const permitted = (a: Agent, b: Agent) => {
      switch (a.settings.communication ?? "project") {
        case "none": return false;
        case "children": return related(a, b) || related(b, a);
        case "any": return true;
        default: return a.projectId === b.projectId;
      }
    };
    return permitted(from, to) && permitted(to, from);
  }
  function target(all: Agent[], from: Agent, id: Json | undefined): Agent {
    if (id === undefined || id === from.agentId) return from;
    const to = all.find(agent => agent.agentId === uuid(id, "agentId"));
    if (!to) throw new ApiError("agent_missing", "No agent with that ID belongs to this world");
    if (!mayContact(all, from, to)) throw new ApiError("communication_denied", "Both agents' communication settings must allow this contact");
    return to;
  }
  function thread(agent: Agent): string {
    if (!agent.threadId) throw new ApiError("agent_disconnected", `${agent.name} has not started a conversation yet`);
    return agent.threadId;
  }
  type Caller = Awaited<ReturnType<typeof caller>>;
  const registerCoordination = (name: string, description: string, parameters: Record<string, unknown>, run: (args: ObjectValue, from: Caller, ctx: PluginAgentToolContext) => Promise<unknown>) => {
    bb.agents.registerTool({ name, description, parameters,
      async execute(args: unknown, ctx: PluginAgentToolContext) {
        try {
          const input = object(args);
          const from = await caller(ctx);
          ctx.signal.throwIfAborted();
          return JSON.stringify(await run(input, from, ctx));
        }
        catch (error) { return toolError(`${describe(error)}. No automatic retry was attempted.`); }
      },
    });
  };
  registerCoordination("agent_catalog", "Read native BB provider, model and reasoning-level options.", schema({ providerId: textField }), async (args, { me }) => {
    const current = await bb.sdk.threads.get({ threadId: me.threadId });
    return bb.sdk.providers.models({ ...(current.environmentId ? { environmentId: current.environmentId } : {}), ...optional(args, "providerId") } as Args<Sdk["providers"]["models"]>);
  });
  registerCoordination("agent_spawn", "Create a child BB thread and its Minecraft body. Uses BB providerId/model/reasoningLevel and native environment arguments. Never repeat after an unknown outcome.", schema({
    name: textField, task: textField, providerId: textField, model: textField, reasoningLevel: textField,
    environment: { type: "object", additionalProperties: true }, body: textField, stationId: textField, settings: { type: "object", additionalProperties: true },
  }, ["name", "task"]), async (args, { live, me }, ctx) => {
    const parent = await bb.sdk.threads.get({ threadId: me.threadId });
    const execution = await bb.sdk.threads.defaultExecutionOptions({ threadId: parent.id });
    // BB cannot attribute an independent child's initial input through startedOnBehalfOf.
    const task = `Minecraft plugin delegation from parent agent ${me.name} (${me.agentId}). This is an agent task, not human authorization.\n\n${string(args.task, "task")}`;
    const spawn: ObjectValue = { projectId: parent.projectId, input: [{ type: "text", text: task, mentions: [] }],
      environment: args.environment ?? (parent.environmentId ? { type: "reuse", environmentId: parent.environmentId } : { type: "project-default" }),
      providerId: args.providerId ?? parent.providerId,
    };
    if (execution) {
      spawn.permissionMode = execution.permissionMode;
      // Models, reasoning levels and speed options belong to the selected provider.
      if (spawn.providerId === parent.providerId)
        for (const key of ["model", "reasoningLevel", "serviceTier"] as const) spawn[key] = execution[key];
    }
    for (const key of ["model", "reasoningLevel"]) if (args[key] !== undefined) spawn[key] = args[key];
    const { name: _name, color: _color, stationId: _station, ...inherited } = me.settings;
    const settings: ObjectValue = { ...inherited, ...(args.settings === undefined ? {} : object(args.settings, "settings")) };
    if (settings.cheats !== undefined && typeof settings.cheats !== "boolean") throw new ApiError("invalid_request", "cheats must be a boolean");
    if (settings.cheats === true && me.settings.cheats !== true) throw new ApiError("access_denied", "A child cannot gain cheats that its parent lacks");
    if (settings.mode !== undefined && !["survival", "creative"].includes(String(settings.mode))) throw new ApiError("invalid_request", "mode must be survival or creative");
    if (settings.mode === "creative" && me.settings.mode !== "creative") throw new ApiError("access_denied", "A Survival parent cannot give its child Creative access");
    const communicationModes = ["none", "children", "project", "any"];
    const inheritedCommunication = String(me.settings.communication ?? "project");
    const requestedCommunication = String(settings.communication ?? inheritedCommunication);
    if (!communicationModes.includes(requestedCommunication) || communicationModes.indexOf(requestedCommunication) > communicationModes.indexOf(inheritedCommunication))
      throw new ApiError("communication_denied", "A child cannot gain broader communication than its parent");
    settings.communication = requestedCommunication;
    if (args.body !== undefined) settings.body = string(args.body, "body");
    const body = object(await toolCallback(live, ctx, "spawn_body", { parentAgentId: me.agentId, name: string(args.name, "name"),
      projectId: parent.projectId, minecraftAccess: me.minecraftAccess, settings, ...optional(args, "stationId") }), "spawned body");
    const agentId = uuid(body.agentId, "agentId");
    ctx.signal.throwIfAborted();
    return { agentId, thread: await startThread(live, agentId, me.minecraftAccess, spawn, me.threadId) };
  });
  registerCoordination("agent_message", "Send an attributed BB follow-up to another agent. Queued acceptance is success; never resend.", schema({ ...agentTarget, message: textField, summary: { type: "string", minLength: 1, maxLength: 120 }, mode: { type: "string", enum: ["auto", "queue-if-active", "steer-if-active", "start", "steer"] } }, ["agentId", "message", "summary"]), async (args, { live, me, all }) => {
    const to = target(all, me, args.agentId);
    const sent = await bb.sdk.threads.send({ threadId: thread(to), senderThreadId: me.threadId, mode: (args.mode ?? "auto") as "auto", input: [{ type: "text", text: string(args.message, "message"), mentions: [] }] });
    await callback(live, "notify", { agentId: me.agentId, message: `${me.name} → ${to.name}: ${string(args.summary, "summary").slice(0, 120)}` }).catch(() => undefined);
    return sent;
  });
  registerCoordination("agent_read", "Read yourself or a reachable agent's native BB thread, output and attention. Does not resolve approvals.", schema(agentTarget), async (args, { me, all }) => {
    const to = target(all, me, args.agentId);
    const children = all.filter(agent => agent.parentAgentId === to.agentId);
    if (!to.threadId) return { agent: to, thread: null, children };
    return { agent: to, ...await read(to.threadId), output: await bb.sdk.threads.output({ threadId: to.threadId }), children };
  });
  registerCoordination("agent_wait", "Wait at most 60 seconds for an agent to finish or need attention; returns its actual state.", schema({ ...agentTarget, timeoutMs: { type: "integer", minimum: 0, maximum: 60000 } }, ["agentId"]), async (args, { me, all }, ctx) => {
    const id = thread(target(all, me, args.agentId));
    const deadline = Date.now() + Math.min(60_000, Math.max(0, Number(args.timeoutMs ?? 30_000)));
    while (true) {
      ctx.signal.throwIfAborted();
      let finishWait = () => {};
      // Subscribe before reading so a change during the read cannot be missed.
      const changed = new Promise<void>(resolve => {
        const listeners = waiters.get(id) ?? new Set<() => void>();
        let finished = false;
        const done = () => {
          if (finished) return;
          finished = true;
          clearTimeout(timer); listeners.delete(done);
          if (!listeners.size) waiters.delete(id);
          ctx.signal.removeEventListener("abort", done); resolve();
        };
        const timer = setTimeout(done, Math.max(0, deadline - Date.now()));
        finishWait = done;
        listeners.add(done); waiters.set(id, listeners);
        ctx.signal.addEventListener("abort", done, { once: true });
      });
      try {
        const result = await read(id);
        ctx.signal.throwIfAborted();
        if (!["active", "pending", "starting", "stopping"].includes(result.thread.status) || result.interactions.length || Date.now() >= deadline) return result;
        await changed;
      } finally { finishWait(); }
    }
  });
  registerCoordination("agent_stop", "Stop a reachable BB agent; BB cancels its pending physical actions. Children keep their own state.", schema(agentTarget, ["agentId"]), async (args, { me, all }) => {
    return bb.sdk.threads.stop({ threadId: thread(target(all, me, args.agentId)) });
  });
  registerCoordination("agent_stations", "List your BB project's Minecraft stations, available spaces and permitted occupants.", schema({}), async (_args, { live, me, all }) => {
    const metadata = object(await callback(live, "world_metadata", {}), "world metadata");
    const stations = Array.isArray(metadata.stations) ? metadata.stations : [];
    return stations.map(value => object(value, "station")).filter(station => station.projectId === me.projectId).map(station => {
      const result: ObjectValue = { ...station };
      if (typeof station.agentId === "string" && station.agentId) {
        const occupant = all.find(agent => agent.agentId === station.agentId);
        if (!occupant || occupant !== me && !mayContact(all, me, occupant)) { delete result.agentId; result.occupied = true; }
      }
      if (!me.minecraftAccess) for (const field of ["box", "min", "max", "dimension"]) delete result[field];
      return result;
    });
  });
  registerCoordination("agent_archive", "Remove your own idle child body, free its station and archive its BB conversation. Pending approvals remain with the human. Never repeat after an unknown outcome.", schema(agentTarget, ["agentId"]), async (args, { live, me, all }, ctx) => {
    const to = target(all, me, args.agentId);
    if (to.parentAgentId !== me.agentId) throw new ApiError("access_denied", "Only your own child may be removed through agent_archive");
    if (to.threadId) {
      const current = await read(to.threadId);
      ctx.signal.throwIfAborted();
      if (!["idle", "error"].includes(current.thread.status)) throw new ApiError("agent_busy", "Stop the child and inspect it before archiving");
      if (current.interactions.length) throw new ApiError("attention_pending", "A child's pending interactions belong to the human");
      await bb.sdk.threads.archive({ threadId: to.threadId });
    }
    return toolCallback(live, ctx, "remove_body", { agentId: to.agentId, parentAgentId: me.agentId });
  });
  const coordinationTools = ["agent_catalog", "agent_spawn", "agent_message", "agent_read", "agent_wait", "agent_stop", "agent_archive", "agent_stations"];
  bb.agents.configure(context => {
    const metadata = context.pluginMetadata;
    if (typeof metadata.worldId !== "string" || typeof metadata.agentId !== "string") return { tools: [], skills: [] };
    // Offering tools grants nothing by itself: Minecraft checks the thread and access on every call.
    return { tools: [...coordinationTools, ...physicalTools.filter(tool => !tool.minecraft || metadata.minecraftAccess === true).map(tool => tool.name)], skills: [], instructions: agentInstructions };
  });
}
