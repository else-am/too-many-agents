// A BB terminal on a Minecraft screen. Java decides which terminal this page may reach; the URL id is only a hint.
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "./terminal.css";

type Session = {
  id: string;
  title: string;
  status: "starting" | "running" | "exited" | "disconnected";
  exitCode: number | null;
  cols: number;
  rows: number;
};
type Output = {
  chunks: { seq: number; dataBase64: string }[];
  nextSeq: number;
  truncated: boolean;
  status: Session["status"];
  exitCode: number | null;
};
type Failure = { code: number; message: string };

declare global {
  interface Window {
    __screen?: boolean;
    cefQuery?(query: {
      request: string;
      persistent?: boolean;
      onSuccess(response: string): void;
      onFailure(code: number, message: string): void;
    }): number;
    cefQueryCancel?(id: number): void;
  }
}

const FONT = '"Pixel Code"';
// Small and narrow panels need denser text. Keep Pixel Code's native 9px size as the floor.
const fontSize = () => Math.round(Math.max(9, Math.min(18, Math.min(innerWidth, innerHeight) / 40)));
const ACTIVE_POLL = 100;
const HIDDEN_POLL = 1000;
const MAX_RETRY = 4000;
// Native input is also checked in Java. Parsing output must never send terminal replies as human input.
const USER_INPUT_WINDOW = 900;
const INPUT_CHUNK = 64 * 1024;
const INPUT_LIMIT = 64 * 1024;

const host = document.getElementById("terminal")!;
const status = document.getElementById("status")!;
const pending = new Map<number, () => void>();
let stopped = false;
let session: Session | undefined;
let nextSeq: number | undefined;
let wake: (() => void) | undefined;
let lastUserInput = -Infinity;
let noticeTimer: number | undefined;
let writingOutput = false;
let userEvent: Event | undefined;

const query = <T>(op: string, args: object = {}) =>
  new Promise<T>((resolve, reject) => {
    if (stopped || !window.cefQuery) return reject({ code: 0, message: "Terminal bridge unavailable" });
    let id: number | undefined;
    let settled = false;
    const finish = () => {
      if (settled) return false;
      settled = true;
      clearTimeout(timer);
      if (id !== undefined) pending.delete(id);
      return true;
    };
    const cancel = () => {
      if (!finish()) return;
      if (id !== undefined) window.cefQueryCancel?.(id);
      reject({ code: 0, message: "Terminal request timed out or view closed" });
    };
    const timer = window.setTimeout(cancel, 30000);
    try {
      id = window.cefQuery({
        request: "screen-terminal:" + JSON.stringify({ op, ...args }),
        onSuccess: (response) => {
          if (!finish()) return;
          try {
            resolve(JSON.parse(response) as T);
          } catch {
            reject({ code: 0, message: "Malformed terminal response" });
          }
        },
        onFailure: (code, message) => {
          if (finish()) reject({ code, message } satisfies Failure);
        },
      });
      if (!settled) pending.set(id, cancel);
    } catch (error) {
      finish();
      reject(error);
    }
  });

const sleep = (ms: number) =>
  new Promise<void>((resolve) => {
    const timer = setTimeout(done, ms);
    function done() {
      clearTimeout(timer);
      if (wake === done) wake = undefined;
      resolve();
    }
    wake = done;
  });

const bytes = (base64: string) => Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
const base64 = (data: Uint8Array) => {
  let text = "";
  for (let i = 0; i < data.length; i += 0x8000)
    text += String.fromCharCode(...data.subarray(i, i + 0x8000));
  return btoa(text);
};

const setStatus = (text: string) => {
  status.textContent = text;
  status.hidden = !text;
};
const notice = (text: string) => {
  clearTimeout(noticeTimer);
  setStatus(text);
  noticeTimer = window.setTimeout(showSession, 4000);
};
function showSession() {
  noticeTimer = undefined;
  if (!session || session.status === "running") return setStatus("");
  if (session.status === "starting") return setStatus("Starting");
  if (session.status === "exited")
    return setStatus(session.exitCode == null ? "Closed" : `Closed - exit ${session.exitCode}`);
  setStatus("Disconnected");
}
const failed = (error: unknown) => {
  const { code, message } = error as Failure;
  clearTimeout(noticeTimer);
  noticeTimer = undefined;
  setStatus(code === 401 || /unauthori[sz]ed|access/i.test(message ?? "") ? "Waiting for terminal access" : message || "Terminal unavailable");
};
const live = () => session?.status === "running" || session?.status === "starting";
const updateSession = (next: Session) => {
  session = next;
  document.title = next.title || "Terminal";
  if (!noticeTimer) showSession();
};

const term = new Terminal({
  fontFamily: `${FONT}, monospace`,
  fontSize: fontSize(),
  fontWeight: "normal",
  fontWeightBold: "normal",
  lineHeight: 1,
  letterSpacing: 0,
  cursorBlink: true,
  scrollback: 5000,
  drawBoldTextInBrightColors: true,
  // OSC 8 links stay inert: no prompt, no navigation.
  linkHandler: { activate: () => {}, allowNonHttpProtocols: false },
  theme: {
    background: "#101113",
    foreground: "#d6d5cf",
    cursor: "#e8e6df",
    cursorAccent: "#101113",
    selectionBackground: "#3a4a63",
    black: "#1f2023",
    red: "#c9504b",
    green: "#71a35b",
    yellow: "#c9a23a",
    blue: "#4e7fc0",
    magenta: "#a36cb6",
    cyan: "#3f9ea4",
    white: "#c4c3bd",
    brightBlack: "#5d5f64",
    brightRed: "#e8706a",
    brightGreen: "#8fc879",
    brightYellow: "#e7c463",
    brightBlue: "#74a2e0",
    brightMagenta: "#c58fd6",
    brightCyan: "#62c5cb",
    brightWhite: "#f3f2ee",
  },
});
const fit = new FitAddon();
term.loadAddon(fit);
// Terminal output cannot read or write the clipboard.
term.parser.registerOscHandler(52, () => true);

// Cmd-C on macOS is a browser copy that xterm already handles. Elsewhere Ctrl-C stays a signal and
// Ctrl-Shift-C/V copy and paste.
const mac = /Mac/.test(navigator.platform);
term.attachCustomKeyEventHandler((event) => {
  // GLFW supplies the actual character separately. Let keypress handle printable keys so Space,
  // shifted punctuation and keyboard layouts use that character rather than the physical key name.
  if (event.type === "keydown" && event.key.length === 1 && !event.ctrlKey && !event.altKey && !event.metaKey)
    return false;
  if (mac || !event.ctrlKey || !event.shiftKey || event.altKey || event.metaKey) return true;
  const key = event.key.toLowerCase();
  if (key === "c") {
    if (event.type === "keydown" && term.hasSelection()) document.execCommand("copy");
    event.preventDefault();
    return false;
  }
  return key !== "v";
});

const marks = ["keydown", "keypress", "keyup", "beforeinput", "input", "paste", "compositionend", "mousedown", "mouseup", "mousemove", "wheel", "touchstart", "touchend"];
const markUser = (event: Event) => {
  if (!event.isTrusted) return;
  lastUserInput = performance.now();
  // A real key/paste can arrive between xterm's asynchronous parsing batches.
  userEvent = event;
};
for (const type of marks) host.addEventListener(type, markUser, { capture: true, passive: true });

let inputQueue: Uint8Array[] = [];
let sendingInput = false;
let queuedBytes = 0;
const send = (data: Uint8Array) => {
  if (stopped || (writingOutput && (!userEvent || userEvent.eventPhase === Event.NONE)) || !live() || performance.now() - lastUserInput > USER_INPUT_WINDOW) return;
  if (queuedBytes + data.length > INPUT_LIMIT) {
    notice("Input too large - paste at most 64 KiB at a time");
    return;
  }
  queuedBytes += data.length;
  inputQueue.push(data);
  if (!sendingInput) void flushInput();
};
async function flushInput() {
  sendingInput = true;
  while (inputQueue.length && !stopped) {
    const size = inputQueue.reduce((sum, part) => sum + part.length, 0);
    const all = new Uint8Array(size);
    let offset = 0;
    for (const part of inputQueue) all.set(part, offset), (offset += part.length);
    inputQueue = [];
    for (let i = 0; i < all.length && !stopped; i += INPUT_CHUNK) {
      try {
        const part = all.subarray(i, i + INPUT_CHUNK);
        await query("input", { dataBase64: base64(part) });
        if (!stopped) queuedBytes -= part.length;
      } catch (error) {
        // An input whose outcome is unknown is never resent.
        inputQueue = [];
        queuedBytes = 0;
        if (!stopped) notice("Input delivery uncertain - remaining input discarded");
        break;
      }
    }
  }
  sendingInput = false;
}
const encoder = new TextEncoder();
term.onData((data) => send(encoder.encode(data)));
term.onBinary((data) => send(Uint8Array.from(data, (c) => c.charCodeAt(0) & 0xff)));

let sentSize = "";
let resizing = false;
const syncSize = async () => {
  if (resizing || !live()) return;
  const size = `${term.cols}x${term.rows}`;
  if (size === sentSize || (session && size === `${session.cols}x${session.rows}`)) return void (sentSize = size);
  resizing = true;
  sentSize = size;
  try {
    updateSession(await query<Session>("resize", { cols: term.cols, rows: term.rows }));
  } catch {
    // Not retried; the next real size change sends again.
  }
  resizing = false;
  if (!stopped && `${term.cols}x${term.rows}` !== sentSize) void syncSize();
};
const fitGrid = () => {
  const size = fontSize();
  if (term.options.fontSize !== size) term.options.fontSize = size;
  const dimensions = fit.proposeDimensions();
  if (dimensions) term.resize(Math.max(2, Math.min(500, dimensions.cols)), Math.max(1, Math.min(200, dimensions.rows)));
};
let fitTimer: number | undefined;
const refit = () => {
  clearTimeout(fitTimer);
  fitTimer = window.setTimeout(() => {
    if (stopped) return;
    fitGrid();
    void syncSize();
  }, 60);
};
const observer = new ResizeObserver(refit);

// Chromium's page script normally reports the focused textarea; report it here if that script hasn't run.
const reportEditing = (editing: boolean) => {
  if (!window.__screen && window.cefQuery)
    window.cefQuery({ request: "screen-editing:" + (editing ? 1 : 0), onSuccess() {}, onFailure() {} });
};

const writeOutput = (output: Output) =>
  new Promise<void>((resolve) => {
    const chunks = output.chunks.filter((chunk) => nextSeq === undefined || chunk.seq >= nextSeq);
    if (nextSeq !== undefined && (output.truncated || (chunks.length && chunks[0].seq > nextSeq)))
      notice("Some output was skipped");
    else if (nextSeq === undefined && output.truncated) notice("Showing recent output");
    nextSeq = Math.max(nextSeq ?? 0, output.nextSeq);
    if (!chunks.length) return resolve();
    const parts = chunks.map((chunk) => bytes(chunk.dataBase64));
    const all = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
    let offset = 0;
    for (const part of parts) all.set(part, offset), (offset += part.length);
    writingOutput = true;
    term.write(all, () => {
      writingOutput = false;
      resolve();
    });
  });

async function poll() {
  let retry = 250;
  let authorized = false;
  while (!stopped) {
    try {
      if (!authorized) {
        updateSession(await query<Session>("state"));
        authorized = true;
        void syncSize();
      }
      const { session: next, output } = await query<{ session: Session; output: Output }>(
        "output",
        nextSeq === undefined ? {} : { sinceSeq: nextSeq },
      );
      if (stopped) return;
      await writeOutput(output);
      updateSession(next);
      retry = 250;
      if (output.chunks.length) continue;
      if (session?.status === "exited") return;
      await sleep(document.hidden || !live() ? HIDDEN_POLL : ACTIVE_POLL);
    } catch (error) {
      if (stopped) return;
      // Reads re-authorize through state; the output position is kept.
      authorized = false;
      failed(error);
      await sleep(retry);
      retry = Math.min(retry * 2, MAX_RETRY);
    }
  }
}

const onVisibility = () => !document.hidden && wake?.();
const stop = () => {
  if (stopped) return;
  stopped = true;
  inputQueue = [];
  queuedBytes = 0;
  wake?.();
  for (const cancel of pending.values()) cancel();
  pending.clear();
  observer.disconnect();
  clearTimeout(fitTimer);
  clearTimeout(noticeTimer);
  document.removeEventListener("visibilitychange", onVisibility);
  for (const type of marks) host.removeEventListener(type, markUser, { capture: true });
  reportEditing(false);
  term.dispose();
};

addEventListener("pagehide", stop, { once: true });

async function start() {
  setStatus("Connecting");
  // Measure cells with the real font, or the grid is sized for the fallback.
  await document.fonts.load(`${fontSize()}px ${FONT}`).catch(() => undefined);
  if (stopped) return;
  term.open(host);
  term.textarea?.addEventListener("focus", () => reportEditing(true));
  term.textarea?.addEventListener("blur", () => reportEditing(false));
  fitGrid();
  observer.observe(host);
  document.addEventListener("visibilitychange", onVisibility);
  term.focus();
  void poll();
}

void start();
