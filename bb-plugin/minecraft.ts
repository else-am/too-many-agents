import { randomUUID } from "node:crypto";
import type { BbPluginApi } from "@get-bb/plugin-sdk";
import { ApiError, PROTOCOL, describe, notFound, object, type Session, type Identity, type Agent } from "./protocol.js";
import { bbInstanceId } from "./discovery.js";

const START_EXPIRY_MS = 10_000;
const PACKAGED = "minecraft";
const busy = (thread: { status: string }) => ["active", "pending", "starting", "stopping"].includes(thread.status);

function connectionRefused(error: unknown): boolean {
  if (!(error instanceof Error)) return false;
  if (error instanceof AggregateError) return error.errors.length > 0 && error.errors.every(connectionRefused);
  return (error as NodeJS.ErrnoException).code === "ECONNREFUSED" || connectionRefused(error.cause);
}

export function minecraftWorlds(bb: BbPluginApi) {
  const instanceId = bbInstanceId(bb);
  const lifetime = new AbortController();
  const sessions = new Map<string, Session>();
  const seen = new Map<string, number>();
  const adopting = new Set<string>();
  // Thread metadata is writable by others, so it only names a candidate. Java checks each thread against its own record.
  const identities = new Map<string, Identity | null>();
  // Each world's own BB project, as last reported by its game; its agents have Minecraft access.
  const worldProjects = new Map<string, string>();

  function session(worldId: string): Session {
    const live = sessions.get(worldId);
    if (!live) throw new ApiError("world_disconnected", "Minecraft world is disconnected");
    return live;
  }
  async function identify(threadId: string, fresh = false): Promise<Identity | null> {
    if (!fresh && identities.has(threadId)) return identities.get(threadId)!;
    const metadata = await bb.sdk.threads.getPluginMetadata({ threadId });
    const identity =
      typeof metadata.worldId === "string" && typeof metadata.agentId === "string"
        ? { worldId: metadata.worldId, agentId: metadata.agentId }
        : null;
    identities.set(threadId, identity);
    return identity;
  }
  async function callback(
    live: Session,
    op: string,
    args: Record<string, unknown>,
    signal?: AbortSignal,
    onState?: (snapshot: unknown) => Promise<void>,
  ): Promise<unknown> {
    let response: Response;
    try {
      response = await fetch(live.callbackUrl, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${live.callbackToken}`,
        },
        body: JSON.stringify({
          protocol: PROTOCOL,
          op,
          worldId: live.worldId,
          worldSessionId: live.worldSessionId,
          requestId: randomUUID(),
          expiresAt: Date.now() + START_EXPIRY_MS,
          ...args,
          bbInstanceId: instanceId,
          connectionId: live.connectionId,
        }),
        signal: AbortSignal.any([lifetime.signal, signal ?? AbortSignal.timeout(120_000)]),
      });
    } catch (error) {
      if (signal?.aborted || (error instanceof Error && error.name === "AbortError")) throw error;
      // A timeout or reset is not proof that the game stopped.
      if (connectionRefused(error)) {
        forget(live);
        throw new ApiError("world_disconnected", "Minecraft world is disconnected");
      }
      throw new ApiError("world_unreachable", "Minecraft did not answer; its connection state is unknown.");
    }
    if (onState && response.ok) {
      if (!response.body || !response.headers.get('content-type')?.startsWith('application/x-ndjson'))
        throw new Error('Minecraft did not open a state stream');
      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8', { fatal: true });
      let pending = '';
      let frameBytes = 0;
      let ended = false;
      try {
        while (!ended) {
          const { value, done } = await reader.read();
          if (done) throw new Error('Minecraft state stream ended without confirmation');
          for (let start = 0; start < value.length;) {
            const newline = value.indexOf(10, start);
            const end = newline < 0 ? value.length : newline;
            frameBytes += end - start;
            if (frameBytes > 8 * 1024 * 1024) throw new Error('Minecraft state frame exceeds 8 MiB');
            pending += decoder.decode(value.subarray(start, end), { stream: newline < 0 });
            start = end + 1;
            if (newline < 0) continue;
            const line = pending;
            pending = '';
            frameBytes = 0;
            if (!line) continue;
            const frame = object(JSON.parse(line), 'Minecraft state frame');
            if (frame.type === 'state') await onState(frame.snapshot);
            else if (frame.type === 'error') throw new Error(String(frame.message));
            else if (frame.type === 'end') { ended = true; break; }
            else throw new Error('Unknown Minecraft state frame');
          }
        }
      } finally { await reader.cancel().catch(() => undefined); }
      return null;
    }
    const body = object(await response.json(), "Minecraft response");
    if (!response.ok || body.ok !== true) {
      const error = body.error && typeof body.error === "object" ? object(body.error) : undefined;
      if (response.status === 409 && error?.code === "world_session_changed") forget(live);
      throw new ApiError(
        "minecraft_action_failed",
        error && typeof error.message === "string"
          ? error.message
          : `Minecraft returned HTTP ${response.status}`,
        typeof error?.code === "string" ? error.code : undefined,
        response.status,
      );
    }
    return body.result;
  }
  /** A world call made for a tool: BB's abort becomes a single cancel for this request. */
  async function toolCallback(
    live: Session,
    ctx: { threadId: string; signal: AbortSignal },
    op: string,
    args: Record<string, unknown>,
    onState?: (snapshot: unknown) => Promise<void>,
  ): Promise<unknown> {
    ctx.signal.throwIfAborted();
    const requestId = randomUUID();
    const controller = new AbortController();
    const abort = () => {
      controller.abort(new Error("Tool call cancelled"));
      void callback(
        live,
        "cancel",
        { cancelRequestId: requestId },
        AbortSignal.timeout(2_000),
      ).catch(() => undefined);
    };
    ctx.signal.addEventListener("abort", abort, { once: true });
    try {
      return await callback(
        live,
        op,
        { ...args, requestId, threadId: ctx.threadId },
        controller.signal,
        onState,
      );
    } finally {
      ctx.signal.removeEventListener("abort", abort);
    }
  }
  async function agents(live: Session): Promise<Agent[]> {
    return object(await callback(live, "agents", {}), "agents").agents as unknown as Agent[];
  }

  /** Screen tools need no body, so they may allow a removed one. */
  async function caller(threadId: string, allowRemoved = false) {
    // Conversations can move between plugin instances, so tool calls reread ownership.
    const identity = await identify(threadId, true);
    if (!identity) {
      const owner = await ownerOf(threadId);
      throw new ApiError("access_denied", owner && owner !== bb.pluginId
        ? `This conversation moved to Minecraft plugin ${owner}. Its tools load when the thread next starts; run bb thread stop ${threadId} if it still offers these old tools.`
        : "This thread has no Minecraft body.");
    }
    const live = session(identity.worldId);
    const all = await agents(live);
    const agent = all.find((row) => row.agentId === identity.agentId && row.threadId === threadId);
    if (!agent || (agent.removed && !allowRemoved) || agent.archived)
      throw new ApiError("access_denied", "This thread is not the body's conversation.");
    return { live, agent };
  }

  async function attach(live: Session) {
    const previous = sessions.get(live.worldId);
    if (previous && previous.connectionId !== live.connectionId) {
      // A copied world, or this game before it reconnected. callback forgets the
      // old session only when its game definitively refuses or no longer listens.
      await callback(previous, "agents", {}, AbortSignal.timeout(3000)).catch(() => undefined);
      if (sessions.get(live.worldId) === previous)
        throw new ApiError("world_in_use", "Another Minecraft game is using this world with this plugin. Close it first.");
    }
    sessions.set(live.worldId, live);
    seen.set(live.worldId, Date.now());
    return !previous || previous.worldSessionId !== live.worldSessionId || previous.connectionId !== live.connectionId;
  }

  function detach(worldId: string, worldSessionId: string, connectionId: string) {
    if (sessions.get(worldId)?.worldSessionId === worldSessionId && sessions.get(worldId)?.connectionId === connectionId) { sessions.delete(worldId); seen.delete(worldId); }
  }

  function forget(live: Session) {
    if (sessions.get(live.worldId) === live) { sessions.delete(live.worldId); seen.delete(live.worldId); }
  }

  /** Only a confirmed absent/deleted BB thread permits local cleanup without ownership. */
  async function threadMissing(threadId: string) {
    try { return (await bb.sdk.threads.get({ threadId })).deletedAt != null; }
    catch (error) { if (notFound(error)) return true; throw error; }
  }

  async function ownership(live: Session, agent: Agent): Promise<"owned" | "foreign" | "missing"> {
    let identity;
    try { identity = await identify(agent.threadId, true); }
    catch (error) {
      if (notFound(error) && await threadMissing(agent.threadId)) return "missing";
      throw error;
    }
    if (identity?.worldId === live.worldId && identity.agentId === agent.agentId) return "owned";
    return await threadMissing(agent.threadId) ? "missing" : "foreign";
  }

  async function owns(live: Session, agent: Agent) {
    return await ownership(live, agent) === "owned";
  }

  // The packaged namespace records every Minecraft conversation's owner: its own
  // identity, or `owner` naming the instance that holds it, even an uninstalled one.
  async function ownerOf(threadId: string) {
    const registry = await bb.sdk.threads.getPluginMetadata({ threadId, pluginId: PACKAGED });
    return typeof registry.owner === "string" ? registry.owner : typeof registry.worldId === "string" ? PACKAGED : null;
  }

  /** Records a conversation this instance created. */
  async function register(threadId: string) {
    const owner = await ownerOf(threadId);
    if (owner && owner !== bb.pluginId) throw new Error(`Conversation ${threadId} is already owned by ${owner}`);
    if (bb.pluginId !== PACKAGED)
      await bb.sdk.threads.updatePluginMetadata({ threadId, pluginId: PACKAGED, set: { owner: bb.pluginId } });
  }

  /**
   * Keeps a body's conversation current with this plugin instance, between turns.
   * A conversation from another instance moves here, so a world keeps its
   * conversations whether it opens with the packaged plugin or a development
   * client: Java's record must name the thread and the owner must not have the
   * world open. `adoptedFrom` marks an unfinished move; a retry completes it.
   * Release a moved conversation's session so its tools use the new namespace.
   */
  async function adopt(threadId: string) {
    // World sync and the stop's own idle event can arrive together.
    if (adopting.has(threadId)) return;
    adopting.add(threadId);
    try { await adoptIdle(threadId); }
    finally { adopting.delete(threadId); }
  }

  async function adoptIdle(threadId: string) {
    const own = await bb.sdk.threads.getPluginMetadata({ threadId });
    const owner = await ownerOf(threadId);
    let owned = typeof own.worldId === "string" && typeof own.agentId === "string";
    // Removing and reinstalling this same instance can clear its namespace while
    // the packaged registry and Java's saved body still identify its conversation.
    if (!owned && owner === bb.pluginId && typeof own.adoptedFrom !== "string") {
      const body = await bodyFor(threadId, own);
      if (!body) return;
      const identity = { worldId: body.live.worldId, agentId: body.agentId };
      await bb.sdk.threads.updatePluginMetadata({ threadId, set: identity });
      Object.assign(own, identity);
      identities.set(threadId, identity);
      owned = true;
    }
    let source = typeof own.adoptedFrom === "string" ? own.adoptedFrom : null;
    if (owned && !source && (!owner || owner === bb.pluginId)) {
      if (!(await bodyFor(threadId, own))) return;
      if (!owner) await register(threadId); // created before the registry existed
      return;
    }
    source ??= owner;
    if (!source || source === bb.pluginId || !/^minecraft(-[a-z0-9]+)*$/.test(source)) return;
    // An unfinished move may resume only while the registry still names either participant.
    if (owner && owner !== source && owner !== bb.pluginId) return;
    const previous = await bb.sdk.threads.getPluginMetadata({ threadId, pluginId: source });
    const body = await bodyFor(threadId, owned ? own : previous);
    if (!body) return;
    if (typeof previous.worldId === "string" && previous.worldId !== body.live.worldId
        || typeof previous.agentId === "string" && previous.agentId !== body.agentId) return;
    // Check every instance, not just the old owner: two clients opening copies
    // of the same world must not both take its conversations from an offline owner.
    const plugins = (await bb.sdk.plugins.list()).plugins;
    const others = new Set([source, ...plugins.map((plugin) => plugin.id)
      .filter((id) => /^minecraft(-[a-z0-9]+)*$/.test(id))]);
    others.delete(bb.pluginId);
    for (const id of others) if (await hasWorld(id, body.live.worldId)) return;
    if (sessions.get(body.live.worldId) !== body.live || busy(await bb.sdk.threads.get({ threadId }))) return;
    // Copy before clearing: an interrupted move leaves both names, never neither.
    await bb.sdk.threads.updatePluginMetadata({ threadId,
      set: { worldId: body.live.worldId, agentId: body.agentId, adoptedFrom: source }, remove: ["owner"] });
    identities.set(threadId, { worldId: body.live.worldId, agentId: body.agentId });
    const identity = ["worldId", "agentId", "nonce"];
    if (source === PACKAGED)
      await bb.sdk.threads.updatePluginMetadata({ threadId, pluginId: PACKAGED, set: { owner: bb.pluginId }, remove: identity });
    else {
      await bb.sdk.threads.updatePluginMetadata({ threadId, pluginId: source, remove: identity });
      if (bb.pluginId !== PACKAGED)
        await bb.sdk.threads.updatePluginMetadata({ threadId, pluginId: PACKAGED, set: { owner: bb.pluginId } });
    }
    // Keep the move pending until its old tool session has been released.
    if (busy(await bb.sdk.threads.get({ threadId }))) return;
    await bb.sdk.threads.stop({ threadId });
    await bb.sdk.threads.updatePluginMetadata({ threadId, remove: ["adoptedFrom"] });
  }

  /** Java's record in a world attached here must name the thread; an uninstalled owner may have left no metadata. */
  async function bodyFor(threadId: string, previous: Record<string, unknown>) {
    for (const live of sessions.values()) {
      if (typeof previous.worldId === "string" && previous.worldId !== live.worldId) continue;
      const row = (await agents(live)).find((agent) => agent.threadId === threadId);
      if (row && (previous.agentId === undefined || previous.agentId === row.agentId)) return { live, agentId: row.agentId };
    }
    return null;
  }

  /** Whether another plugin instance may have this world open; only "not installed or not running" is definite. */
  async function hasWorld(pluginId: string, worldId: string) {
    try {
      const response = await fetch(new URL(`/api/v1/plugins/${pluginId}/http/v1/setup`, bb.server.loopbackBaseUrl), { signal: AbortSignal.timeout(10_000) });
      if (response.status === 404 || response.status === 503) return false;
      const games = response.ok ? object(await response.json()).games : undefined;
      return !Array.isArray(games) || games.some((game) => object(game).worldId === worldId);
    } catch {
      return true;
    }
  }

  async function activeGames(probe = false) {
    // A stopped JVM cannot keep blocking updates. Probe old sessions so a slow
    // world sync does not make a still-running game look disconnected.
    await Promise.all([...sessions.values()].map(async live => {
      if (!probe && Date.now() - (seen.get(live.worldId) ?? 0) < 30_000) return;
      try { await callback(live, "agents", {}, AbortSignal.timeout(3000)); }
      catch { /* callback removes only definitively disconnected sessions; unknown games still block replacement. */ }
    }));
    return [...sessions.values()].map(live => ({ worldId: live.worldId }));
  }

  // A conversation that was mid-turn during a world sync moves once it is idle.
  bb.events.on("thread.idle", ({ thread }) => {
    if (sessions.size)
      void adopt(thread.id).catch((error) => bb.log.warn(`Could not move conversation ${thread.id}: ${describe(error)}`));
  });

  bb.onDispose(() => {
    lifetime.abort();
    sessions.clear();
    seen.clear();
    identities.clear();
    worldProjects.clear();
  });
  return {
    session,
    activeGames,
    worldProjects,
    attach,
    detach,
    identify,
    owns,
    ownership,
    threadMissing,
    adopt,
    register,
    caller,
    agents,
    callback,
    toolCallback,
    identities,
  };
}

export type MinecraftWorlds = ReturnType<typeof minecraftWorlds>;
