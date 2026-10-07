export function runScript(options: {
  source: string;
  bootstrap?: string;
  initial?: unknown;
  workerUrl?: URL;
  onRequest(operation: string, value: unknown, signal: AbortSignal): Promise<unknown>;
  onUpdates?(send: (value: unknown) => Promise<void>, signal: AbortSignal): Promise<void>;
  signal?: AbortSignal;
  timeoutMs?: number;
  cpuSliceMs?: number;
  maxOutputBytes?: number;
}): Promise<{ value: unknown; requests: number; completedRequests: number; updates: number; elapsedMs: number; logs: string[] }>;
