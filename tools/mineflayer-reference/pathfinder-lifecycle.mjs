// Preauthored integration scenarios from pathfinder-integration-review.md (2026-10-07).
// Run: node pathfinder-lifecycle.mjs /path/to/source-checkout [/path/to/reference/node_modules]
// No Minecraft server, game, lifecycle operations, downloads or implementation substitutions.
// Source scope: actual Pathfinder module + real Block/Movements/AStar; actual minecraftScripts
// host orchestration + real QuickJS worker, with controlled native callback outcomes.
// Native slab trajectory validation belongs to the lead's live fixture.
const scenarios = [
  ['replacement event ordering', 'Compare goal_updated/path_reset ordering to pinned 2.4.5.'],
  ['replacement reentrancy', 'A path_reset listener installs C while B replaces A; C stays current and its goto stays pending until C actually completes.'],
  ['execution-error fallback', 'A known native route rejection emits execution_error; a listener installs B, which must survive the old failure.'],
  ['terminal-event drain', 'After native completion, goal_reached cleanup setsGoal(null); goto cannot settle with a resulting control request still pending.'],
  ['overlapping control drains', 'A stopRoute reply is pending when setGoal(null) adds cancelAction; neither control may be abandoned when goto settles.'],
  ['terminal-state barrier stop', 'Stop arrives after the native terminal reply while ordered state is pending; no late native control is created and goto waits for the barrier.'],
  ['first-slab raw path', 'Real upstream and guest planner emit raw node (1,2,0) from physical (.5,.5,.5) to the adjacent slab at Y1.5.'],
  ['unknown start outcome', 'Native accepts a mutation but loses its start reply; real host/runner must abort and scoped-end, preventing a caught guest error from issuing a second mutation.'],
  ['unknown await outcome', 'A known action ID loses its await reply; real host/runner must abort and scoped-end, preventing a second mutation.'],
  ['known pre-start rejection', 'Contrast an explicit native pre-start rejection: guest may catch it and complete one subsequent action.'],
  ['empty noPath correction', 'Explicit documented upstream defect correction: goto rejects NoPath for an empty unreachable path, although pinned upstream resolves.'],
];

// Scenario definitions above precede their fixture plumbing and assertions below.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const sourceRoot = resolve(process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '../..'));
const dependencies = resolve(process.argv[3] ?? join(sourceRoot, 'tools/mineflayer-reference/node_modules'));
const reference = createRequire(join(dependencies, '../package.json'));
const plugin = createRequire(join(sourceRoot, 'bb-plugin/package.json'));
const { build } = plugin('esbuild');
const upstream = reference('mineflayer-pathfinder');
const { Vec3 } = reference('vec3');
const registry = reference('prismarine-registry')('1.21.1');
const Block = reference('prismarine-block')(registry);
assert.equal(reference('mineflayer-pathfinder/package.json').version, '2.4.5');

const evidence = [];
const temporary = await mkdtemp(join(tmpdir(), 'pathfinder-lifecycle-'));
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const flush = async () => { await sleep(0); await sleep(0); };
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function watch(promise) {
  const result = { state: 'pending' };
  result.done = promise.then(value => Object.assign(result, { state: 'resolved', value }),
    error => Object.assign(result, { state: 'rejected', error }));
  return result;
}
async function settled(result, label) {
  let timer;
  try {
    await Promise.race([result.done, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${label}: promise did not settle`)), 1000);
    })]);
    return result;
  } finally { clearTimeout(timer); }
}
async function bundle(options, require = reference) {
  const result = await build({ bundle: true, platform: 'node', format: 'cjs', write: false,
    nodePaths: [dependencies, join(sourceRoot, 'bb-plugin/node_modules')], logLevel: 'silent', ...options });
  const module = { exports: {} };
  new Function('require', 'module', 'exports', result.outputFiles[0].text)(require, module, module.exports);
  return module.exports;
}
const { installPathfinder } = await bundle({ entryPoints: [join(sourceRoot, 'bb-plugin/scripting/pathfinder.mjs')] });

function world() {
  const blocks = new Map();
  const bot = new EventEmitter();
  Object.assign(bot, {
    registry, version: '1.21.1', game: { minY: -16 }, entities: {},
    entity: { position: new Vec3(.5, 0, .5), velocity: new Vec3(0, 0, 0), width: .6, height: 1.8,
      onGround: true, isInWater: false, effects: {}, attributes: {}, yaw: 0, pitch: 0 },
    inventory: { slots: Array(46).fill(null), items: () => [] },
    controlState: { forward: false, back: false, left: false, right: false, jump: false, sprint: false, sneak: false },
    setControlState(name, value) { this.controlState[name] = value; },
    clearControlStates() { for (const key of Object.keys(this.controlState)) this.controlState[key] = false; },
    look() { return Promise.resolve(); },
    blockAt(position) {
      const p = position.floored();
      if (Math.abs(p.x) > 16 || Math.abs(p.z) > 16 || Math.abs(p.y) > 8) return null;
      const state = blocks.get(p.toString()) ?? registry.blocksByName[p.y === -1 ? 'stone' : 'air'].minStateId;
      const block = Block.fromStateId(state, 0); block.position = p; return block;
    },
  });
  bot.physics = reference('prismarine-physics').Physics(registry, { getBlock: p => bot.blockAt(p) });
  return { bot, set(x, y, z, name, properties = {}) {
    blocks.set(new Vec3(x, y, z).toString(), Block.fromProperties(name, properties, 0).stateId);
  } };
}

// Controlled native replies, not a replacement Pathfinder implementation. We deliberately
// hold starts, terminals, state barriers and controls independently to expose ordering.
function guest({ holdControls = false, stateBarrier = null, startFailure = null } = {}) {
  const fixture = world();
  const actions = [], controls = [], calls = [];
  const request = (operation, args) => {
    calls.push({ operation, args });
    if (operation === 'startAction') {
      if (startFailure && actions.length === 0) {
        const failure = startFailure; startFailure = null;
        return Promise.reject(failure);
      }
      const action = { id: `route-${actions.length + 1}`, sequence: actions.length + 1, args, terminal: deferred() };
      actions.push(action);
      return Promise.resolve({ id: action.id, sequence: action.sequence, status: 'running' });
    }
    const action = actions.find(value => value.id === args.id);
    assert.ok(action, `unknown fixture action ${args.id}`);
    if (operation === 'awaitAction') return action.terminal.promise;
    if (operation === 'cancelAction' || operation === 'stopRoute') {
      const control = { operation, id: args.id, reply: deferred(), pending: true };
      controls.push(control);
      control.reply.promise.then(() => { control.pending = false; });
      if (!holdControls) control.reply.resolve({ id: args.id, status: 'running' });
      return control.reply.promise;
    }
    throw new Error(`Unexpected operation ${operation}`);
  };
  installPathfinder(fixture.bot, { request,
    waitForActionState: () => stateBarrier?.promise ?? Promise.resolve(),
    snapshot: () => ({ action: {}, blocks: { min: [-16, -8, -16], size: [33, 17, 33], states: [] } }),
  });
  fixture.bot.pathfinder.movements.canDig = false;
  return { ...fixture, actions, controls, calls,
    finish(index = actions.length - 1, status = 'completed') {
      const action = actions[index]; assert.ok(action, 'route should have started');
      action.terminal.resolve({ id: action.id, sequence: action.sequence, status, terminal: true });
    },
    drain() {
      for (const control of controls) control.reply.resolve({ id: control.id, status: 'completed' });
      for (const action of actions) action.terminal.resolve({ id: action.id, sequence: action.sequence, status: 'interrupted' });
    },
  };
}
function pinned() {
  const fixture = world(); upstream.pathfinder(fixture.bot); fixture.bot.pathfinder.movements.canDig = false;
  return fixture;
}
const goal = x => new upstream.goals.GoalBlock(x, 0, 0);

const checks = new Map();
checks.set('replacement event ordering', async () => {
  const traces = [];
  for (const fixture of [pinned(), guest()]) {
    const { bot } = fixture, events = [];
    bot.on('goal_updated', next => events.push(['goal_updated', next?.x ?? null]));
    bot.on('path_reset', reason => events.push(['path_reset', reason]));
    bot.pathfinder.setGoal(goal(2)); bot.emit('physicsTick'); await flush();
    bot.pathfinder.setGoal(goal(3)); await flush();
    traces.push(events); fixture.drain?.();
  }
  assert.deepEqual(traces[1], traces[0]);
  evidence.push({ scenario: 'replacement event ordering', upstream: traces[0], guest: traces[1] });
});

checks.set('replacement reentrancy', async () => {
  for (const fixture of [pinned(), guest()]) {
    const { bot } = fixture;
    try {
      bot.pathfinder.setGoal(goal(2)); bot.emit('physicsTick'); await flush();
      const cGoal = goal(4); let c;
      bot.once('path_reset', () => { c = watch(bot.pathfinder.goto(cGoal)); });
      const b = watch(bot.pathfinder.goto(goal(3)));
      fixture.finish?.(0, 'interrupted'); await flush();
      assert.equal(bot.pathfinder.goal, cGoal);
      assert.ok(c, 'path_reset listener must run');
      assert.equal(c.state, 'pending', 'current replacement goto was rejected by a stale goal_updated event');
      assert.equal((await settled(b, 'superseded B')).error.name, 'GoalChanged');
      // Both implementations must resolve C only after observed physical arrival.
      bot.entity.position = new Vec3(4.5, 0, .5); bot.emit('physicsTick');
      assert.equal((await settled(c, 'replacement C')).state, 'resolved');
    } finally { fixture.drain?.(); }
  }
});

checks.set('execution-error fallback', async () => {
  const f = guest({ startFailure: new Error('known route rejection') }); const { bot } = f;
  try {
    const fallbackGoal = goal(4); let fallback;
    bot.once('path_reset', reason => {
      assert.equal(reason, 'execution_error'); fallback = watch(bot.pathfinder.goto(fallbackGoal));
    });
    const original = watch(bot.pathfinder.goto(goal(2))); bot.emit('physicsTick'); await flush();
    await settled(original, 'failed original');
    assert.equal(bot.pathfinder.goal, fallbackGoal, 'old execution failure erased the listener-installed goal');
    assert.equal(fallback.state, 'pending');
    bot.emit('physicsTick'); await flush();
    assert.equal(f.actions.length, 1, 'fallback must start its own route');
    bot.entity.position = new Vec3(4.5, 0, .5); f.finish();
    assert.equal((await settled(fallback, 'fallback')).state, 'resolved');
  } finally { f.drain(); }
});

checks.set('terminal-event drain', async () => {
  const f = guest({ holdControls: true }); const { bot } = f;
  try {
    const done = watch(bot.pathfinder.goto(goal(2)));
    bot.on('goal_reached', () => bot.pathfinder.setGoal(null));
    bot.emit('physicsTick'); await flush();
    bot.entity.position = new Vec3(2.5, 0, .5); f.finish(); await flush();
    assert.ok(done.state === 'pending' || f.controls.every(control => !control.pending),
      'goto settled while a control request created by its terminal event was still pending');
    for (const control of f.controls) control.reply.resolve({ id: control.id, status: 'completed' });
    assert.equal((await settled(done, 'terminal cleanup')).state, 'resolved');
  } finally { f.drain(); }
});

checks.set('overlapping control drains', async () => {
  const f = guest({ holdControls: true }); const { bot } = f;
  try {
    const done = watch(bot.pathfinder.goto(goal(3))); bot.emit('physicsTick'); await flush();
    bot.pathfinder.stop(); bot.emit('physicsTick'); await flush();
    assert.equal(f.controls[0]?.operation, 'stopRoute');
    bot.pathfinder.setGoal(null); await flush();
    assert.equal(f.controls[1]?.operation, 'cancelAction');
    assert.equal(f.controls[0].id, f.controls[1].id);
    f.finish(0, 'interrupted');
    f.controls[1].reply.resolve({ id: f.controls[1].id, status: 'interrupted' }); await flush();
    assert.equal(done.state, 'pending', 'later cancellation overwrote the pending graceful-stop control');
    f.controls[0].reply.resolve({ id: f.controls[0].id, status: 'interrupted' });
    assert.equal((await settled(done, 'overlapping controls')).error.name, 'GoalChanged');
    assert.ok(f.controls.every(control => !control.pending));
  } finally { f.drain(); }
});

checks.set('terminal-state barrier stop', async () => {
  const barrier = deferred();
  const f = guest({ holdControls: true, stateBarrier: barrier }); const { bot } = f;
  try {
    const done = watch(bot.pathfinder.goto(goal(2))); bot.emit('physicsTick'); await flush();
    bot.entity.position = new Vec3(2.5, 0, .5); f.finish(); await flush();
    assert.equal(done.state, 'pending', 'native terminal reply must not bypass ordered state delivery');
    bot.pathfinder.stop(); bot.emit('physicsTick'); await flush();
    assert.equal(f.controls.length, 0, 'a terminal action must not receive a late stop control');
    assert.equal(done.state, 'pending');
    barrier.resolve();
    assert.equal((await settled(done, 'terminal state barrier stop')).error.name, 'PathStopped');
  } finally { barrier.resolve(); f.drain(); }
});

checks.set('first-slab raw path', async () => {
  const requests = [];
  for (const fixture of [pinned(), guest()]) {
    const { bot, set } = fixture;
    try {
      set(0, 0, 0, 'stone_slab', { type: 'bottom', waterlogged: false });
      set(1, 0, 0, 'stone'); set(1, 1, 0, 'stone_slab', { type: 'bottom', waterlogged: false });
      bot.entity.position = new Vec3(.5, .5, .5);
      bot.pathfinder.movements.allowParkour = false; bot.pathfinder.movements.allow1by1towers = false;
      const destination = new upstream.goals.GoalBlock(1, 2, 0);
      const result = bot.pathfinder.getPathFromTo(bot.pathfinder.movements, bot.entity.position, destination,
        { optimizePath: false, timeout: 10000, tickTimeout: 10000 }).next().value.result;
      assert.equal(result.status, 'success');
      const raw = result.path.map(({ x, y, z }) => [x, y, z]);
      assert.deepEqual(raw, [[1, 2, 0]]);
      if (fixture.actions) {
        bot.pathfinder.setGoal(destination); bot.emit('physicsTick'); await flush();
        const request = fixture.actions[0]?.args;
        assert.ok(request); assert.deepEqual(request.nodes.map(({ x, y, z }) => [x, y, z]), raw);
        assert.deepEqual([request.start.x, request.start.y, request.start.z], [.5, .5, .5]);
        requests.push({ physicalStart: [.5, .5, .5], raw, physicalLanding: [1.5, 1.5, .5], nativeExecution: 'lead live fixture required' });
      }
    } finally { fixture.drain?.(); }
  }
  evidence.push({ scenario: 'first-slab raw path', requests });
});

checks.set('empty noPath correction', async () => {
  const outcomes = [];
  for (const fixture of [pinned(), guest()]) {
    const { bot, set } = fixture;
    try {
      for (const [x, z] of [[-1, 0], [1, 0], [0, -1], [0, 1]]) for (let y = 0; y < 3; y++) set(x, y, z, 'bedrock');
      bot.pathfinder.movements.allowParkour = false;
      const done = watch(bot.pathfinder.goto(goal(4))); bot.emit('physicsTick');
      await settled(done, 'empty noPath'); outcomes.push(done.state === 'resolved' ? 'resolved' : done.error.name);
    } finally { fixture.drain?.(); }
  }
  assert.deepEqual(outcomes, ['resolved', 'NoPath'], 'retain the explicit empty-noPath correction, not pinned accidental success');
  evidence.push({ scenario: 'empty noPath correction', upstream: outcomes[0], intendedGuest: outcomes[1] });
});

// Bundle exports together so the known rejection has exactly the ApiError identity
// imported by minecraftScripts. runner.mjs is the real source, never a stub/alias.
const { minecraftScripts, ApiError } = await bundle({
  stdin: { contents: `export { minecraftScripts } from './scripts.ts'; export { ApiError } from './protocol.ts';`,
    resolveDir: join(sourceRoot, 'bb-plugin'), sourcefile: 'lifecycle-host-entry.mjs', loader: 'js' },
  packages: 'external',
}, plugin);
await mkdir(join(temporary, 'dist/scripting'), { recursive: true });
await symlink(join(sourceRoot, 'bb-plugin/scripting'), join(temporary, 'scripting'), 'dir');
// Only the guest transport caller is minimal. Host orchestration, item decoding,
// runner and QuickJS worker remain actual implementations from the selected source tree.
await writeFile(join(temporary, 'dist/scripting/bot.js'), `var MinecraftBot = {
  createBot() { return {
    bot: { attempt: async request => JSON.parse(await __mcRequest('action', JSON.stringify(request))) },
    goals: {}, Vec3: function() {}, Movements: function() {}, update() {}
  }; }
};\n`);

function initial() {
  return { revision: 1, tick: 1, action: { status: 'idle' }, body: {}, entities: [],
    itemRegistries: { items: ['minecraft:air'], components: ['minecraft:custom_data'] },
    hands: { inventory: [], equipment: {}, menu: { slots: [], carried: { wire: 'AA==', count: 0 } } },
  };
}
async function hostScenario(mode) {
  const calls = [], mutations = [];
  let attempts = 0, accepted, lease;
  const bb = { pluginId: 'fixture', onDispose() {}, sdk: { plugins: { list: async () => ({ plugins: [{ id: 'fixture', rootDir: temporary }] }) } } };
  const worlds = { async toolCallback(live, ctx, kind, payload, onState) {
    assert.equal(kind, 'script'); ctx.signal.throwIfAborted();
    const args = payload.arguments; calls.push({ operation: args.operation, scriptId: args.scriptId });
    if (args.operation === 'begin') { lease = args.scriptId; return initial(); }
    assert.equal(args.scriptId, lease, 'every operation, including cleanup, must use the same scoped script ID');
    if (args.operation === 'stream') {
      await onState(initial());
      if (!ctx.signal.aborted) await new Promise(resolve => ctx.signal.addEventListener('abort', resolve, { once: true }));
      return {};
    }
    if (args.operation === 'heartbeat') return {};
    if (args.operation === 'end') { accepted = null; return { status: 'released' }; }
    if (args.operation === 'action') {
      attempts++;
      if (mode === 'known' && attempts === 1) throw new ApiError('minecraft_action_failed', 'fixture pre-start rejection');
      accepted = { id: `action-${attempts}`, sequence: attempts, status: 'running' };
      mutations.push(args.action.probe);
      if (mode === 'lost-start' && attempts === 1) throw new ApiError('world_unreachable', 'fixture lost accepted start reply; outcome unknown');
      return { ...accepted };
    }
    if (args.operation === 'awaitAction') {
      assert.equal(args.id, accepted?.id);
      if (mode === 'lost-await' && attempts === 1) throw new ApiError('world_unreachable', 'fixture lost await reply; outcome unknown');
      return { ...accepted, status: 'completed', terminal: true };
    }
    throw new Error(`Unexpected native callback ${args.operation}`);
  } };
  const execute = minecraftScripts(bb, worlds);
  const report = await execute({ timeoutMs: 5000, code: `
    let caught = false;
    try { await bot.attempt({ type: 'route', probe: 1 }); } catch (error) { caught = true; }
    const second = await bot.attempt({ type: 'route', probe: 2 });
    return { caught, second: second.status };
  ` }, { threadId: 'fixture-thread', signal: new AbortController().signal }, { worldSessionId: `fixture-${mode}` }, 'fixture-body');
  const body = JSON.parse(report.content[0].text);
  evidence.push({ scenario: mode, operations: calls.map(call => call.operation), mutations, isError: !!report.isError,
    bodyRelease: body.bodyRelease, error: body.error, value: body.result?.value });
  assert.equal(body.bodyRelease, 'confirmed');
  assert.equal(calls.filter(call => call.operation === 'end').length, 1, 'exactly one scoped release is required');
  if (mode === 'known') {
    assert.equal(report.isError, undefined, JSON.stringify(body));
    assert.equal(attempts, 2); assert.deepEqual(mutations, [2]);
    assert.deepEqual(body.result.value, { caught: true, second: 'completed' });
  } else {
    assert.equal(attempts, 1, `unknown ${mode} outcome allowed a second native mutation`);
    assert.deepEqual(mutations, [1]);
    assert.equal(report.isError, true, 'unknown outcome must abort execution, not be catch-and-continue');
    assert.match(body.error, /fixture lost/);
  }
}
checks.set('unknown start outcome', () => hostScenario('lost-start'));
checks.set('unknown await outcome', () => hostScenario('lost-await'));
checks.set('known pre-start rejection', () => hostScenario('known'));

const fingerprints = {};
for (const file of ['bb-plugin/scripting/pathfinder.mjs', 'bb-plugin/scripts.ts', 'bb-plugin/protocol.ts',
  'bb-plugin/scripting/runner.mjs', 'bb-plugin/scripting/worker.mjs', 'src/main/java/toomanyagents/ScriptNavigation.java']) {
  fingerprints[file] = createHash('sha256').update(await readFile(join(sourceRoot, file))).digest('hex');
}
let failed = 0;
try {
  for (const [name, contract] of scenarios) {
    try { await checks.get(name)(); console.log(`PASS ${name}`); }
    catch (error) { failed++; console.error(`FAIL ${name}: ${error.message}`); evidence.push({ scenario: name, contract, error: error.stack }); }
  }
  console.log(JSON.stringify({ sourceRoot, referenceVersion: '2.4.5', fingerprints, evidence,
    passed: scenarios.length - failed, failed, nativeExecution: 'not run' }, null, 2));
  process.exitCode = failed ? 1 : 0;
} finally { await rm(temporary, { recursive: true, force: true }); }
