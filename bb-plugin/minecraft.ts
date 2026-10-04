import { randomUUID } from "node:crypto";
import type { BbPluginApi } from "@get-bb/plugin-sdk";
import { ApiError, PROTOCOL, object, type Session, type Identity, type Agent } from "./protocol.js";

const START_EXPIRY_MS = 10_000;

export function minecraftWorlds(bb: BbPluginApi) {
  const sessions = new Map<string, Session>();
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
        }),
        signal: signal ?? AbortSignal.timeout(120_000),
      });
    } catch (error) {
      if (signal?.aborted || (error instanceof Error && error.name === "AbortError")) throw error;
      // Minecraft is gone without detaching; it attaches again if it is still running.
      if (sessions.get(live.worldId) === live) sessions.delete(live.worldId);
      throw new ApiError("world_disconnected", "Minecraft world is disconnected");
    }
    const body = object(await response.json(), "Minecraft response");
    if (!response.ok || body.ok !== true) {
      const error = body.error && typeof body.error === "object" ? object(body.error) : undefined;
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
    if (!agent) throw new ApiError("access_denied", "This thread is not the body's conversation.");
    return { live, agent };
  }

  function attach(live: Session) {
    const previous = sessions.get(live.worldId);
    sessions.set(live.worldId, live);
    return !previous || previous.worldSessionId !== live.worldSessionId;
  }

  function detach(worldId: string, worldSessionId: string) {
    if (sessions.get(worldId)?.worldSessionId === worldSessionId) sessions.delete(worldId);
  }

  bb.onDispose(() => {
    sessions.clear();
    identities.clear();
  });
  return {
    session,
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
