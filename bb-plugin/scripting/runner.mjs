import { Worker } from 'node:worker_threads';

// This boundary accepts only JSON. The worker has no bridge credentials, and
// the caller must validate every operation against the current body/session.
export async function runScript({
  source, bootstrap = '', onRequest, signal, timeoutMs = 120_000,
  cpuSliceMs = 250, maxOutputBytes = 64 * 1024, initial = null,
  workerUrl = new URL('./worker.mjs', import.meta.url),
}) {
  if (typeof source !== 'string' || Buffer.byteLength(source) > 256 * 1024)
    throw new Error('Script source must be a string of at most 256 KiB');
  if (typeof bootstrap !== 'string' || typeof onRequest !== 'function')
    throw new Error('Invalid script runner configuration');
  for (const [name, value, maximum] of [
    ['timeoutMs', timeoutMs, 300_000], ['cpuSliceMs', cpuSliceMs, 1000],
    ['maxOutputBytes', maxOutputBytes, 1024 * 1024],
  ]) {
    if (!Number.isSafeInteger(value) || value < 1 || value > maximum)
      throw new Error(`Invalid ${name}`);
  }
  signal?.throwIfAborted();
  const controller = new AbortController();
  const started = performance.now();
  const pending = new Set();
  const logs = [];
  let requests = 0;
  let completedRequests = 0;
  let outputBytes = 0;
  let finished = false;
  const worker = new Worker(workerUrl, {
    workerData: { source, bootstrap, cpuSliceMs, maxOutputBytes, initial: JSON.stringify(initial) },
    resourceLimits: { maxOldGenerationSizeMb: 128, stackSizeMb: 4 },
  });
  let timer;
  let abort;
  try {
    return await new Promise((resolve, reject) => {
      const finish = (error, value) => {
        if (finished) return;
        finished = true;
        const stats = { requests, completedRequests, elapsedMs: Math.round(performance.now() - started), logs };
        // Stopping local execution is not evidence that a world mutation was undone.
        const outstandingRequests = pending.size;
        controller.abort(error ?? new Error('Script completed'));
        if (error) {
          Object.assign(error, stats, { outstandingRequests });
          reject(error);
        } else resolve({ value, ...stats });
      };
      abort = () => finish(signal.reason instanceof Error ? signal.reason : new Error('Script cancelled'));
      signal?.addEventListener('abort', abort, { once: true });
      if (signal?.aborted) abort();
      timer = setTimeout(() => finish(new Error('Script deadline exceeded')), timeoutMs);
      worker.on('error', error => finish(error));
      worker.on('exit', code => {
        if (!finished) finish(new Error(`Script worker exited before completion (${code})`));
      });
      worker.on('message', message => {
        if (finished) return;
        try {
          if (message.type === 'request') {
            const { id, operation, payload } = message;
            if (id !== requests + 1 || pending.size >= 32 || requests >= 10_000 ||
                typeof operation !== 'string' || operation.length > 128 ||
                typeof payload !== 'string' || Buffer.byteLength(payload) > 1024 * 1024)
              throw new Error('Invalid or excessive script request');
            const arguments_ = JSON.parse(payload);
            requests++;
            pending.add(id);
            Promise.resolve().then(() => {
              controller.signal.throwIfAborted();
              return onRequest(operation, arguments_, controller.signal);
            }).then(value => {
              if (finished) return;
              const payload = JSON.stringify(value ?? null);
              if (Buffer.byteLength(payload) > 8 * 1024 * 1024)
                throw new Error('Script response exceeds 8 MiB');
              completedRequests++;
              pending.delete(id);
              worker.postMessage({ type: 'response', id, payload });
            }).catch(error => {
              if (finished) return;
              pending.delete(id);
              worker.postMessage({ type: 'response', id, error: String(error?.message ?? error).slice(0, 4096) });
            });
          } else if (message.type === 'log') {
            if (typeof message.text !== 'string') throw new Error('Invalid script log');
            outputBytes += Buffer.byteLength(message.text);
            if (outputBytes > maxOutputBytes || logs.length >= 1000) throw new Error('Script output limit exceeded');
            logs.push(message.text);
          } else if (message.type === 'result') {
            if (pending.size) throw new Error('Script ended with unawaited host operations');
            if (typeof message.payload !== 'string' ||
                outputBytes + Buffer.byteLength(message.payload) > maxOutputBytes)
              throw new Error('Script output limit exceeded');
            finish(null, JSON.parse(message.payload));
          } else if (message.type === 'error') {
            finish(new Error(String(message.message).slice(0, 4096)));
          } else throw new Error('Invalid script worker message');
        } catch (error) { finish(error); }
      });
    });
  } finally {
    clearTimeout(timer);
    if (abort) signal?.removeEventListener('abort', abort);
    await worker.terminate();
  }
}
