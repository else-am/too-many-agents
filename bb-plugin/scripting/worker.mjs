import { parentPort, workerData } from 'node:worker_threads';
import { getQuickJS } from 'quickjs-emscripten';

const { source, bootstrap, cpuSliceMs, maxOutputBytes } = workerData;
const quickjs = await getQuickJS();
const runtime = quickjs.newRuntime();
runtime.setMemoryLimit(64 * 1024 * 1024);
runtime.setMaxStackSize(512 * 1024);
const vm = runtime.newContext();
const initial = vm.newString(workerData.initial);
vm.setProp(vm.global, '__mcInitial', initial);
initial.dispose();
let deadline = 0;
runtime.setInterruptHandler(() => performance.now() > deadline);
const clock = vm.newFunction('__mcNow', () => vm.newNumber(performance.now()));
vm.setProp(vm.global, '__mcNow', clock);
clock.dispose();
const pending = new Map();
let nextId = 0;
let main;
let updateHandler;
let nextUpdate = 1;
let ended = false;
let scheduled = false;
let outputBytes = 0;
const timers = new Map();
let nextTimer = 0;

function clearGuestTimer(id) {
  const timer = timers.get(id);
  if (!timer) return;
  clearTimeout(timer.host);
  timer.callback.dispose();
  for (const arg of timer.args) arg.dispose();
  timers.delete(id);
}

function fireTimer(id) {
  const timer = timers.get(id);
  if (ended || !timer) return;
  deadline = performance.now() + cpuSliceMs;
  let failure;
  // A callback may clear its own timer. Keep its active handles alive until
  // callFunction returns, independently of the timer's retained handles.
  const callback = timer.callback.dup();
  const args = timer.args.map(arg => arg.dup());
  try {
    const result = vm.callFunction(callback, vm.undefined, ...args);
    if (result.error) {
      failure = guestError(result.error);
      result.error.dispose();
    } else result.value.dispose();
  } catch (error) { failure = String(error.message ?? error); }
  finally { callback.dispose(); for (const arg of args) arg.dispose(); }
  if (!timer.repeat || failure) clearGuestTimer(id);
  else if (timers.has(id)) timer.host = setTimeout(() => fireTimer(id), timer.delay);
  if (failure) end({ type: 'error', message: failure });
  else schedulePump();
}

for (const [name, repeat] of [['setTimeout', false], ['setInterval', true]]) {
  const fn = vm.newFunction(name, (callback, delay, ...args) => {
    if (vm.typeof(callback) !== 'function') throw new TypeError('Timer callback must be a function');
    if (timers.size >= 1024 || nextTimer >= 10_000) throw new Error('Guest timer limit exceeded');
    const milliseconds = delay ? vm.getNumber(delay) : 0;
    const id = ++nextTimer;
    const timer = { callback: callback.dup(), args: args.map(arg => arg.dup()), repeat,
      delay: Number.isFinite(milliseconds) && milliseconds >= 1 && milliseconds <= 2147483647 ? Math.trunc(milliseconds) : 1 };
    timers.set(id, timer);
    timer.host = setTimeout(() => fireTimer(id), timer.delay);
    return vm.newNumber(id);
  });
  vm.setProp(vm.global, name, fn);
  fn.dispose();
}
for (const name of ['clearTimeout', 'clearInterval']) {
  const fn = vm.newFunction(name, id => { if (id) clearGuestTimer(vm.getNumber(id)); });
  vm.setProp(vm.global, name, fn);
  fn.dispose();
}

function end(message) {
  if (ended) return;
  ended = true;
  for (const id of timers.keys()) clearGuestTimer(id);
  parentPort.postMessage(message);
  // The parent terminates this worker, including its WASM memory. Dispose the
  // guest handles as well so normal completion checks ownership mistakes.
  for (const deferred of pending.values()) deferred.dispose();
  pending.clear();
  main?.dispose();
  updateHandler?.dispose();
  vm.dispose();
  runtime.dispose();
}

function guestError(handle) {
  const value = vm.dump(handle);
  return typeof value === 'object' && value !== null
    ? `${value.name ?? 'Error'}: ${value.message ?? 'Script failed'}` : String(value);
}

function pump() {
  if (ended) return;
  scheduled = false;
  deadline = performance.now() + cpuSliceMs;
  try {
    const jobs = runtime.executePendingJobs(100);
    if (jobs.error) {
      const message = guestError(jobs.error);
      jobs.error.dispose();
      end({ type: 'error', message });
      return;
    }
    const state = vm.getPromiseState(main);
    if (state.type === 'rejected') {
      const message = guestError(state.error);
      state.error.dispose();
      end({ type: 'error', message });
    } else if (state.type === 'fulfilled') {
      const payload = vm.getString(state.value);
      state.value.dispose();
      if (pending.size) throw new Error('Script ended with unawaited host operations');
      if (Buffer.byteLength(payload) + outputBytes > maxOutputBytes) throw new Error('Script output limit exceeded');
      end({ type: 'result', payload });
    } else if (runtime.hasPendingJob()) schedulePump();
  } catch (error) { end({ type: 'error', message: String(error.message ?? error) }); }
}

function schedulePump() {
  if (scheduled || ended) return;
  scheduled = true;
  setImmediate(pump);
}

const request = vm.newFunction('__mcRequest', (operationHandle, payloadHandle) => {
  if (vm.typeof(operationHandle) !== 'string' || vm.typeof(payloadHandle) !== 'string')
    throw new Error('Minecraft requests require string operation and JSON payload');
  const operation = vm.getString(operationHandle);
  const payload = vm.getString(payloadHandle);
  if (operation.length > 128 || Buffer.byteLength(payload) > 1024 * 1024 || pending.size >= 32 || nextId >= 10_000)
    throw new Error('Invalid or excessive script request');
  const deferred = vm.newPromise();
  const id = ++nextId;
  pending.set(id, deferred);
  parentPort.postMessage({ type: 'request', id, operation, payload });
  return deferred.handle;
});
vm.setProp(vm.global, '__mcRequest', request);
request.dispose();
const log = vm.newFunction('__mcLog', textHandle => {
  if (vm.typeof(textHandle) !== 'string') throw new Error('Invalid script log');
  const text = vm.getString(textHandle);
  outputBytes += Buffer.byteLength(text);
  if (outputBytes > maxOutputBytes) throw new Error('Script output limit exceeded');
  parentPort.postMessage({ type: 'log', text });
});
vm.setProp(vm.global, '__mcLog', log);
log.dispose();

parentPort.on('message', message => {
  if (ended) return;
  if (message.type === 'update') {
    deadline = performance.now() + cpuSliceMs;
    try {
      if (!updateHandler || message.id !== nextUpdate || typeof message.payload !== 'string' ||
          Buffer.byteLength(message.payload) > 8 * 1024 * 1024 || nextUpdate > 10_000)
        throw new Error('Invalid script update');
      const payload = vm.newString(message.payload);
      const result = vm.callFunction(updateHandler, vm.undefined, payload);
      payload.dispose();
      if (result.error) {
        const error = guestError(result.error);
        result.error.dispose();
        throw new Error(error);
      }
      result.value.dispose();
      parentPort.postMessage({ type: 'updated', id: nextUpdate++ });
      // Resume awaits resolved by this frame before receiving another frame.
      pump();
    } catch (error) { end({ type: 'error', message: String(error.message ?? error) }); }
    return;
  }
  if (message.type !== 'response') return;
  const deferred = pending.get(message.id);
  if (!deferred) return;
  deadline = performance.now() + cpuSliceMs;
  const value = message.error === undefined ? vm.newString(message.payload) : vm.newError(message.error);
  try {
    if (message.error === undefined) deferred.resolve(value);
    else deferred.reject(value);
  } finally {
    value.dispose();
    deferred.dispose();
    pending.delete(message.id);
  }
  schedulePump();
});

// Registry/class initialization is trusted setup, not agent execution. Give
// this larger fixed bundle its own bounded slice before applying the agent's
// smaller CPU limit; keep both under the same memory and wall-time limits.
deadline = performance.now() + 1000;
try {
  const setup = vm.evalCode(`
    const console = Object.freeze({
      log: (...values) => __mcLog(values.map(v => typeof v === 'string' ? v : JSON.stringify(v)).join(' ')),
    });
    ${bootstrap}
  `, 'minecraft-bootstrap.js');
  if (setup.error) {
    const message = guestError(setup.error);
    setup.error.dispose();
    throw new Error(message);
  }
  setup.value.dispose();
  if (workerData.updates) {
    updateHandler = vm.getProp(vm.global, '__mcUpdate');
    if (vm.typeof(updateHandler) !== 'function') throw new Error('Script bootstrap has no update handler');
  }
  // Capture serialization before guest code can replace the global function.
  // The returned promise contains a JSON string, never a host object or handle.
  deadline = performance.now() + cpuSliceMs;
  const result = vm.evalCode(`
    ((serialize) => (async () => serialize(await (async () => {
      ${source}
    })()) ?? 'null')())(JSON.stringify)
  `, 'minecraft-script.js');
  if (result.error) {
    const message = guestError(result.error);
    result.error.dispose();
    end({ type: 'error', message });
  } else {
    main = result.value;
    parentPort.postMessage({ type: 'ready' });
    schedulePump();
  }
} catch (error) { end({ type: 'error', message: String(error.message ?? error) }); }
