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
