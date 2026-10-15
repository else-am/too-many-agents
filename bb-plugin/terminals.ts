import type { BbPluginApi } from "@get-bb/plugin-sdk";
import { isAbsolutePath } from "./paths.js";
import { ApiError, string, type Agent, type ObjectValue, type Session } from "./protocol.js";

const MAX_COLS = 500;
const MAX_ROWS = 200;
const MAX_INPUT_BYTES = 64 * 1024;
// BB keeps the latest chunks within both bounds and sets `truncated` when it skips any. Its chunks are at most 64 KiB.
const MAX_OUTPUT_BYTES = 256 * 1024;
const MAX_OUTPUT_CHUNKS = 512;
const MAX_TERMINALS = 64;
const MAX_WORLDS = 64;
const BASE64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

/**
 * Terminal screens for the Java game API. BB owns each session's lifetime: closing a screen only stops polling it.
 * A world connection may read, type into or resize only terminals it created or was granted through `access`.
 * `live` throws once the world's connection changes, so a reply to an older connection never grants anything.
 */
export function terminalScreens(bb: BbPluginApi) {
  const worlds = new Map<string, { worldSessionId: string; connectionId: string; ids: Set<string> }>();

  function current(session: Session) {
    const entry = worlds.get(session.worldId);
    return entry?.worldSessionId === session.worldSessionId && entry.connectionId === session.connectionId ? entry.ids : null;
  }

  function grant(live: () => Session, started: Session, terminalId: string) {
    const session = live();
    if (session.worldId !== started.worldId || session.worldSessionId !== started.worldSessionId
        || session.connectionId !== started.connectionId)
      throw new ApiError("world_session_changed", "Minecraft world session changed");
    let ids = current(session);
    if (!ids) {
      ids = new Set();
      worlds.delete(session.worldId);
      if (worlds.size >= MAX_WORLDS) worlds.delete(worlds.keys().next().value!);
      worlds.set(session.worldId, { worldSessionId: session.worldSessionId, connectionId: session.connectionId, ids });
    }
    ids.delete(terminalId);
    // Forget the oldest grant; its screen can ask for access again.
    if (ids.size >= MAX_TERMINALS) ids.delete(ids.values().next().value!);
    ids.add(terminalId);
  }

  function grantedId(live: () => Session, data: ObjectValue) {
    noAgent(data);
    const terminalId = string(data.terminalId, "terminalId");
    if (!current(live())?.has(terminalId))
      throw new ApiError("terminal_not_granted", "This world hasn't opened that terminal.");
    return terminalId;
  }

  async function localHost() {
    const { primaryHostId } = await bb.sdk.system.config();
    if (!primaryHostId) throw new ApiError("terminal_not_local", "BB has no local computer for terminals.");
    return primaryHostId;
  }

  async function threadEnvironment(threadId: string) {
    return (await bb.sdk.threads.get({ threadId })).environmentId;
  }

  return {
    /** A player's new shell in a local folder. Never retried: a failed create may still have started one. */
    async create(live: () => Session, data: ObjectValue) {
      const started = { ...live() };
      noAgent(data);
      const cwd = string(data.cwd, "cwd");
      if (!isAbsolutePath(cwd)) throw new ApiError("invalid_request", "cwd must be an absolute path");
      const session = await bb.sdk.terminals.create({
        ...size(data),
        scope: { kind: "host_path", hostId: await localHost(), cwd },
        start: { mode: "shell" },
      });
      grant(live, started, session.id);
      return session;
    },

    /** Players may open any terminal on this computer; an agent only one in its own thread's environment. */
    async access(live: () => Session, data: ObjectValue, agent: Agent | undefined) {
      const started = { ...live() };
      if (data.agentId !== undefined && !agent)
        throw new ApiError("agent_missing", "A Minecraft agent is required");
      const session = await bb.sdk.terminals.get({ terminalId: string(data.terminalId, "terminalId") });
      if (session.hostId !== await localHost())
        throw new ApiError("terminal_not_local", "Screens can only show terminals on the computer running Minecraft.");
      if (agent) {
        const environmentId = agent.threadId ? await threadEnvironment(agent.threadId) : null;
        // Machine-path terminals have neither an environment nor a thread, so they never match.
        const terminalEnvironment = session.environmentId
          ?? (session.threadId ? await threadEnvironment(session.threadId) : null);
        if (!environmentId || terminalEnvironment !== environmentId)
          throw new ApiError("terminal_not_in_environment", "That terminal isn't in this agent's environment.");
      }
      grant(live, started, session.id);
      return session;
    },

    /**
     * Output chunks with `seq >= sinceSeq` (inclusive, default 0), bounded to the latest ones that fit. Poll again with
     * `nextSeq`, BB's next unwritten seq. `truncated` means earlier output in that range was skipped or discarded.
     */
    async output(live: () => Session, data: ObjectValue) {
      const terminalId = grantedId(live, data);
      const sinceSeq = data.sinceSeq === undefined || data.sinceSeq === null ? undefined : count(data.sinceSeq, "sinceSeq");
      const [session, output] = await Promise.all([
        bb.sdk.terminals.get({ terminalId }),
        bb.sdk.terminals.output({
          terminalId, tailBytes: MAX_OUTPUT_BYTES, limitChunks: MAX_OUTPUT_CHUNKS,
          ...(sinceSeq === undefined ? {} : { sinceSeq }),
        }),
      ]);
      const bytes = output.chunks.reduce((total, chunk) => total + Buffer.byteLength(chunk.dataBase64, "base64"), 0);
      if (output.chunks.length > MAX_OUTPUT_CHUNKS || bytes > MAX_OUTPUT_BYTES)
        throw new ApiError("terminal_output_too_large", "BB returned more terminal output than requested.");
      return { session, output };
    },

    /** Never retried: the shell may already have received the bytes. */
    async input(live: () => Session, data: ObjectValue) {
      const terminalId = grantedId(live, data);
      const dataBase64 = string(data.dataBase64, "dataBase64");
      if (!BASE64.test(dataBase64) || Buffer.byteLength(dataBase64, "base64") > MAX_INPUT_BYTES)
        throw new ApiError("invalid_request", `dataBase64 must be base64 of at most ${MAX_INPUT_BYTES} bytes`);
      return bb.sdk.terminals.input({ terminalId, dataBase64 });
    },

    async resize(live: () => Session, data: ObjectValue) {
      return bb.sdk.terminals.resize({ terminalId: grantedId(live, data), ...size(data) });
    },
  };
}

/** Only `access` acts for an agent; every other operation is the player's screen. */
function noAgent(data: ObjectValue) {
  if (data.agentId !== undefined) throw new ApiError("invalid_request", "agentId is only accepted by terminal.access");
}

function size(data: ObjectValue) {
  const cols = count(data.cols, "cols");
  const rows = count(data.rows, "rows");
  if (cols < 2 || cols > MAX_COLS || rows < 1 || rows > MAX_ROWS)
    throw new ApiError("invalid_request", `Terminal size must be 2-${MAX_COLS} columns and 1-${MAX_ROWS} rows`);
  return { cols, rows };
}

function count(value: unknown, label: string) {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0)
    throw new ApiError("invalid_request", `${label} must be a non-negative integer`);
  return value;
}
