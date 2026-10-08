// Authored before bounded stale-start replanning. Real planner/Blocks, controlled
// transport replies; this does not establish native rejection provenance.
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../..', import.meta.url));
const reference = createRequire(resolve(root, 'tools/mineflayer-reference/package.json'));
const plugin = createRequire(resolve(root, 'bb-plugin/package.json'));
const { build } = plugin('esbuild');
const { Vec3 } = reference('vec3');
const registry = reference('prismarine-registry')('1.21.1');
const Block = reference('prismarine-block')(registry);
const { goals } = reference('mineflayer-pathfinder');
globalThis.__mcNow = performance.now.bind(performance);
const clock = { name: 'guest-clock', setup(builder) {
  builder.onResolve({ filter: /^(node:)?perf_hooks$/ }, () => ({ path: 'clock', namespace: 'guest-clock' }));
  builder.onLoad({ filter: /.*/, namespace: 'guest-clock' }, () => ({ contents: 'const now=__mcNow; export const performance={now};' }));
} };
const bundle = await build({ entryPoints: [resolve(root, 'bb-plugin/scripting/pathfinder.mjs')],
  bundle: true, write: false, platform: 'browser', format: 'esm', plugins: [clock], nodePaths: [resolve(root, 'bb-plugin/node_modules')] });
const { installPathfinder } = await import('data:text/javascript;base64,' + Buffer.from(bundle.outputFiles[0].text).toString('base64'));
const flush = () => new Promise(resolve => setImmediate(resolve));
const known = () => Object.assign(new Error('route_start_changed: requested=(0.5,0,0.5), actual=(1.5,0,0.5)'),
  { phase: 'before_start', detail: 'route_start_changed: requested=(0.5,0,0.5), actual=(1.5,0,0.5)' });
async function scenario(mode) {
  const bot = new EventEmitter(), starts = [], resets = [];
  let tick = 1, rejectStart, cancelCount = 0;
  Object.assign(bot, { registry, version: '1.21.1', game: { minY: -16 }, entities: {},
    entity: { position: new Vec3(.5,0,.5), velocity: new Vec3(0,0,0), width: .6, height: 1.8,
      effects: {}, attributes: {}, yaw: 0, pitch: 0, onGround: true, isInWater: false },
    inventory: { slots: Array(46).fill(null), items: () => [] },
    controlState: {}, blockAt(position) {
      const p = position.floored();
      if (p.x < -8 || p.x > 16 || p.z < -8 || p.z > 8 || p.y < -8 || p.y > 8) return null;
      const b = Block.fromStateId(registry.blocksByName[p.y === -1 ? 'stone' : 'air'].defaultState, 0);
      b.position = p; return b;
    }
  });
  const request = async (operation, value) => {
    if (operation === 'startAction') {
      starts.push(JSON.parse(JSON.stringify(value)));
      if (starts.length === 1) return new Promise((_, reject) => { rejectStart = reject; });
      if (mode === 'twice') throw known();
      return { id: 'accepted-second' };
    }
    if (operation === 'awaitAction') {
      bot.entity.position.set(4.5,0,.5);
      return { id: value.id, status: 'completed', result: {}, sequence: 1 };
    }
    if (operation === 'cancelAction') { cancelCount++; return {}; }
    throw new Error('Unexpected operation ' + operation);
  };
  installPathfinder(bot, { request, waitForActionState: async () => {}, snapshot: () => ({ tick, action: {} }) });
  bot.pathfinder.enablePathShortcut = false;
  const movement = bot.pathfinder.movements;
  movement.canDig = false; movement.allowParkour = false;
  movement.allow1by1towers = false; movement.scafoldingBlocks = [];
  bot.on('path_reset', reason => {
    resets.push(reason);
    if (mode === 'cancel-on-reset' && reason === 'start_changed') bot.pathfinder.setGoal(null);
  });
  let outcome;
  const done = bot.pathfinder.goto(new goals.GoalBlock(4,0,0)).then(
    () => { outcome = 'completed'; }, error => { outcome = error.name; });
  bot.emit('physicsTick'); await flush();
  assert.equal(starts.length, 1);
  if (mode === 'stop') bot.pathfinder.stop();
  rejectStart(mode === 'unknown' ? new Error('route_start_changed: lost reply') : known());
  await flush();
  // Repeated dispatch with no newer authoritative tick must not create work.
  bot.emit('physicsTick'); await flush();
  assert.equal(starts.length, 1, 'No replan from the rejected observation');
  for (let i=0; i<10 && outcome===undefined; i++) {
    tick++; bot.entity.position.set(1.5,0,.5);
    bot.emit('physicsTick'); await flush();
  }
  await done;
  if (mode === 'recover') {
    assert.equal(outcome, 'completed'); assert.equal(starts.length, 2);
    assert.deepEqual(starts[1].start, { x:1.5,y:0,z:.5 });
    assert(resets.includes('start_changed'));
  } else if (mode === 'twice') { assert.equal(starts.length, 2); assert.notEqual(outcome, 'completed'); }
  else { assert.equal(starts.length, 1); assert.notEqual(outcome, 'completed'); }
  assert.equal(cancelCount, 0, 'Rejected origins never acquired an action to cancel');
  return { mode, outcome, starts: starts.length, resets };
}
const results=[];
for (const mode of ['recover','twice','unknown','cancel-on-reset','stop']) results.push(await scenario(mode));
console.log(JSON.stringify({ results, scope: 'Actual browser-bundled planner with pinned Blocks; controlled transport only, no native run' }, null, 2));
