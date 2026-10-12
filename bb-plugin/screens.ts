import type { BbPluginApi, PluginAgentToolContext, PluginAgentToolResult } from "@get-bb/plugin-sdk";
import type { MinecraftWorlds } from "./minecraft.js";
import { isAbsolutePath, pathStyle } from "./paths.js";
import { ApiError, describe, object } from "./protocol.js";
import { saveScreenSnapshot } from "./images.js";

// Java times out its own calls first (open ~15s, CDP ~30s). This only catches a game that never answers.
const CALL_TIMEOUT_MS = 60_000;

/** Screen tools run in Minecraft's client. Java owns access, leases and screen errors; this adds paths, screenshots and transport errors. */
export function screenTools(bb: BbPluginApi, worlds: MinecraftWorlds) {
  async function filePath(threadId: string, target: string) {
    const thread = await bb.sdk.threads.get({ threadId });
    const environment = thread.environmentId ? await bb.sdk.environments.get({ environmentId: thread.environmentId }) : null;
    const { primaryHostId } = await bb.sdk.system.config();
    if (environment && environment.hostId !== primaryHostId)
      throw new ApiError("unavailable", "Screens can only open files on the computer running Minecraft.");
    if (isAbsolutePath(target)) return target;
    if (!environment?.path || !isAbsolutePath(environment.path))
      throw new ApiError("invalid_request", "Use an absolute file path; your working directory is unknown.");
    return pathStyle(environment.path).resolve(environment.path, target);
  }

  return async function screen(tool: string, input: unknown, ctx: PluginAgentToolContext): Promise<PluginAgentToolResult> {
    const timeout = AbortSignal.timeout(CALL_TIMEOUT_MS);
    // A timeout aborts like a cancellation, so Java is told to cancel the request too.
    const signal = AbortSignal.any([ctx.signal, timeout]);
    let result: PluginAgentToolResult;
    try {
      let args = object(input);
      const { live, agent } = await abortable(() => worlds.caller(ctx.threadId, true), signal);
      if (tool === "screen_open" && args.kind === "file" && typeof args.target === "string")
        args = { ...args, target: await abortable(() => filePath(ctx.threadId, args.target as string), signal) };
      // toolCallback checks the signal first, so nothing is sent after a timeout.
      result = (await worlds.toolCallback(live, { ...ctx, signal }, "screen", {
        agentId: agent.agentId,
        tool,
        arguments: args,
      })) as PluginAgentToolResult;
    } catch (error) {
      // The bridge answers 504 (no code) when its own wait ends, or 503 when interrupted; either way the call may still run.
      const unknown = (timeout.aborted && !ctx.signal.aborted) || (error instanceof ApiError
        && (error.httpStatus === 504 || error.nativeCode === "interrupted_outcome_unknown"));
      const code = unknown ? "world_unreachable"
        : error instanceof ApiError ? error.nativeCode ?? error.code : "failed";
      if (code === "world_disconnected" || code === "world_session_changed")
        return failure("unavailable", "Minecraft isn't running this world right now.");
      if (code === "world_unreachable")
        return failure("timeout", "Minecraft didn't answer in time. The outcome is unknown, so check the screen's state before acting again.");
      return failure(code, describe(error));
    }
    if (tool !== "screen_screenshot") return result;
    // Java has answered, so a failure here is about the image, never a timeout.
    try {
      return await saveScreenSnapshot(bb, ctx, result);
    } catch (error) {
      return failure(error instanceof ApiError ? error.code : "failed", describe(error));
    }
  };
}

/** Stops waiting on abort; the lookup itself has no side effects. */
function abortable<T>(lookup: () => Promise<T>, signal: AbortSignal): Promise<T> {
  signal.throwIfAborted();
  return new Promise((resolve, reject) => {
    const abort = () => reject(signal.reason);
    signal.addEventListener("abort", abort, { once: true });
    lookup().then(resolve, reject).finally(() => signal.removeEventListener("abort", abort));
  });
}

/** Same shape as Java's screen errors, which also carry details such as `candidates` or `leased`. */
function failure(code: string, message: string): PluginAgentToolResult {
  return { content: [{ type: "text", text: JSON.stringify({ error: { code, message } }) }], isError: true };
}
