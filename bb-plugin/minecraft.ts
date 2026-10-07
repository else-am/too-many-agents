import { randomUUID } from "node:crypto";
import type { BbPluginApi } from "@get-bb/plugin-sdk";
import { ApiError, PROTOCOL, object, type Session, type Identity, type Agent } from "./protocol.js";
import { bbInstanceId } from "./discovery.js";

const START_EXPIRY_MS = 10_000;

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
  // Thread metadata is writable by others, so it only names a candidate. Java checks each thread against its own record.
  const identities = new Map<string, Identity | null>();

  function session(worldId: string): Session {
    const live = sessions.get(worldId);
    if (!live) throw new ApiError("world_disconnected", "Minecraft world is disconnected");
    return live;
  }
  async function identify(threadId: string): Promise<Identity | null> {
    if (identities.has(threadId)) return identities.get(threadId)!;
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
      const decoder = new TextDecoder();
      let pending = '';
      let ended = false;
      try {
        while (!ended) {
          const { value, done } = await reader.read();
          if (done) throw new Error('Minecraft state stream ended without confirmation');
          pending += decoder.decode(value, { stream: true });
          if (pending.length > 8 * 1024 * 1024) throw new Error('Minecraft state frame exceeds 8 MiB');
          let newline: number;
          while ((newline = pending.indexOf('\n')) >= 0) {
            const line = pending.slice(0, newline);
            pending = pending.slice(newline + 1);
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

  async function caller(threadId: string) {
    const identity = await identify(threadId);
    if (!identity) throw new ApiError("access_denied", "This thread has no Minecraft body.");
    const live = session(identity.worldId);
    const all = await agents(live);
    const agent = all.find((row) => row.agentId === identity.agentId && row.threadId === threadId);
    if (!agent || agent.removed || agent.archived)
      throw new ApiError("access_denied", "This thread is not the body's conversation.");
    return { live, agent };
  }

  async function validationSession(threadId?: string) {
    if (threadId && await identify(threadId)) return (await caller(threadId)).live;
    if (sessions.size !== 1) throw new ApiError("world_required", "Open one Minecraft world, or run from an embodied thread, to validate a role.");
    return sessions.values().next().value!;
  }

  function attach(live: Session) {
    const previous = sessions.get(live.worldId);
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

  bb.onDispose(() => {
    lifetime.abort();
    sessions.clear();
    seen.clear();
    identities.clear();
  });
  return {
    session,
    activeGames,
    validationSession,
    attach,
    detach,
    identify,
    caller,
    agents,
    callback,
    toolCallback,
    identities,
  };
}

export type MinecraftWorlds = ReturnType<typeof minecraftWorlds>;
