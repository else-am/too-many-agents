// Exercises the real worker/WASM boundary. This does not claim Minecraft conformance.
import assert from 'node:assert/strict';
import { runScript } from '../bb-plugin/scripting/runner.mjs';

const bootstrap = `
  const bot = {
    async record(value) { return JSON.parse(await __mcRequest('record', JSON.stringify(value))); }
  };
`;
const calls = [];
const result = await runScript({
  source: `for (let i = 0; i < 3; i++) await bot.record(i); return 'finished';`,
  bootstrap,
  onRequest: async (operation, value) => { calls.push([operation, value]); return value; },
});
assert.deepEqual(calls, [['record', 0], ['record', 1], ['record', 2]]);
assert.equal(result.value, 'finished');
assert.equal(result.requests, 3);
console.log('PASS: one script completes three awaited host operations in order');

const isolation = await runScript({
  source: `return [typeof process, typeof require, typeof fetch, typeof __mcRequest.constructor('return this')().process];`,
  onRequest: async () => { throw new Error('unexpected request'); },
});
assert.deepEqual(isolation.value, ['undefined', 'undefined', 'undefined', 'undefined']);
console.log('PASS: guest JavaScript has no Node process, module loader, or network API');

await assert.rejects(runScript({
  source: 'while (true) {}', cpuSliceMs: 50, timeoutMs: 2000,
  onRequest: async () => { throw new Error('unexpected request'); },
}), /interrupt|CPU|execution/i);
console.log('PASS: a synchronous infinite loop is interrupted');

await assert.rejects(runScript({
  source: 'await new Promise(() => {});', timeoutMs: 150,
  onRequest: async () => { throw new Error('unexpected request'); },
}), /deadline/i);
console.log('PASS: a permanently pending promise expires');

const abort = new AbortController();
let started;
const didStart = new Promise(resolve => { started = resolve; });
let operationCancelled = false;
const pending = runScript({
  bootstrap, source: `await bot.record('pending'); await bot.record('must not start');`, signal: abort.signal,
  onRequest: (_operation, _value, signal) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => { operationCancelled = true; reject(signal.reason); }, { once: true });
    started();
  }),
});
const rejected = assert.rejects(pending, /cancelled/i);
await didStart;
abort.abort(new Error('User cancelled script'));
await rejected;
assert.equal(operationCancelled, true);
console.log('PASS: cancellation reaches the host operation and stops subsequent steps');

await assert.rejects(runScript({
  bootstrap, source: `bot.record('unawaited'); return 'not done';`,
  onRequest: (_operation, _value, signal) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), { once: true });
  }),
}), /unawaited/i);
console.log('PASS: outstanding operations cannot be reported as successful completion');

await assert.rejects(runScript({
  source: `return 'x'.repeat(100000);`, maxOutputBytes: 1024,
  onRequest: async () => null,
}), /output/i);
console.log('PASS: oversized output is rejected');

const updates = await runScript({
  bootstrap: `
    const received = [];
    let finishUpdates;
    const readyUpdates = new Promise(resolve => { finishUpdates = resolve; });
    function __mcUpdate(payload) {
      const update = JSON.parse(payload);
      received.push(update.sequence);
      if (update.sequence === 3) finishUpdates();
    }
  `,
  source: 'await readyUpdates; return received;',
  onRequest: async () => { throw new Error('Updates must not become guest requests'); },
  onUpdates: async send => { for (let sequence = 1; sequence <= 3; sequence++) await send({ sequence }); },
});
assert.deepEqual(updates.value, [1, 2, 3]);
assert.equal(updates.requests, 0);
assert.equal(updates.updates, 3);
console.log('PASS: ordered host updates resolve guest event promises without guest polling');

await assert.rejects(runScript({
  bootstrap: 'function __mcUpdate() { while (true) {} }',
  source: 'await new Promise(() => {});', cpuSliceMs: 50, timeoutMs: 2000,
  onRequest: async () => null,
  onUpdates: async send => { await send({ tick: 1 }); },
}), /interrupt|CPU|execution/i);
console.log('PASS: event handlers cannot escape the guest CPU limit');

const updateAbort = new AbortController();
let updateStarted;
const updateDidStart = new Promise(resolve => { updateStarted = resolve; });
let feedStopped = false;
const updateRun = runScript({
  bootstrap: 'function __mcUpdate() {}', source: 'await new Promise(() => {});',
  signal: updateAbort.signal, onRequest: async () => null,
  onUpdates: async (send, signal) => {
    await send({ tick: 1 });
    updateStarted();
    await new Promise(resolve => signal.addEventListener('abort', () => { feedStopped = true; resolve(); }, { once: true }));
  },
});
const updateRejected = assert.rejects(updateRun, /cancelled/i);
await updateDidStart;
updateAbort.abort(new Error('User cancelled updates'));
await updateRejected;
assert.equal(feedStopped, true);
console.log('PASS: cancelling the script also stops its state feed');

const timers = await runScript({
  source: `
    const seen = [];
    const cancelled = setTimeout(() => seen.push('cancelled'), 10);
    clearTimeout(cancelled);
    await new Promise(resolve => setTimeout((a, b) => { seen.push(a + b); resolve(); }, 20, 2, 3));
    await new Promise(resolve => { const id = setInterval(() => {
      seen.push('interval'); if (seen.length === 3) { clearInterval(id); resolve(); }
    }, 2); });
    return seen;
  `,
  onRequest: async () => { throw new Error('Timers must not become bridge requests'); },
});
assert.deepEqual(timers.value, [5, 'interval', 'interval']);
assert.equal(timers.requests, 0);
console.log('PASS: guest timers preserve arguments, cancellation and repeated callbacks without bridge requests');

await assert.rejects(runScript({
  source: 'await new Promise(resolve => setTimeout(() => { while (true) {} }, 0));',
  cpuSliceMs: 50, timeoutMs: 2000, onRequest: async () => null,
}), /interrupt|CPU|execution/i);
console.log('PASS: timer callbacks cannot escape the guest CPU limit');

await assert.rejects(runScript({
  source: 'for (let i = 0; i < 2000; i++) setTimeout(() => {}, 100000); await new Promise(() => {});',
  onRequest: async () => null,
}), /timer.*limit/i);
console.log('PASS: pending guest timers are bounded');
