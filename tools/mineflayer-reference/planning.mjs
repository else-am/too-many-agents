// Scenarios authored before planning.mjs. Source fixtures only; no game/server is started.
// node tools/mineflayer-reference/planning.mjs /path/to/reference/node_modules
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';
import { performance } from 'node:perf_hooks';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const dependencies = resolve(process.argv[2] ?? fileURLToPath(new URL('./node_modules', import.meta.url)));
const reference = createRequire(pathToFileURL(resolve(dependencies, '../package.json')));
const plugin = createRequire(pathToFileURL(resolve(dependencies, '../../../bb-plugin/package.json')));
const { build } = plugin('esbuild');
const upstream = reference('mineflayer-pathfinder');
const { Vec3 } = reference('vec3');
const Move = reference('mineflayer-pathfinder/lib/move');
const Physics = reference('mineflayer-pathfinder/lib/physics');
const registry = reference('prismarine-registry')('1.21.1');
const Block = reference('prismarine-block')(registry);
assert.equal(reference('mineflayer-pathfinder/package.json').version, '2.4.5');

function fixture() {
  const blocks = new Map(), bot = new EventEmitter();
  const key = p => `${Math.floor(p.x)},${Math.floor(p.y)},${Math.floor(p.z)}`;
  const set = (x, y, z, name, properties = {}) => blocks.set(`${x},${y},${z}`, Block.fromProperties(name, properties, 0).stateId);
  Object.assign(bot, {
    registry, version: '1.21.1', game: { minY: -16 }, entities: {},
    entity: { position: new Vec3(0.5, 0, 0.5), velocity: new Vec3(0, 0, 0), width: 0.6, height: 1.8,
      effects: {}, attributes: {}, yaw: 0, pitch: 0, onGround: true, isInWater: false },
    inventory: { slots: Array(46).fill(null), items: () => [
      { type: registry.itemsByName.diamond_pickaxe.id, name: 'diamond_pickaxe', count: 1 },
    ] },
    controlState: { forward: false, back: false, left: false, right: false, jump: false, sprint: false, sneak: false },
    clearControlStates() {},
    blockAt(position) {
      const p = position.floored();
      if (p.x < -8 || p.x > 16 || p.z < -8 || p.z > 8 || p.y < -8 || p.y > 8) return null;
      const state = blocks.get(key(p)) ?? registry.blocksByName[p.y === -1 ? 'stone' : 'air'].minStateId;
      const block = Block.fromStateId(state, 0); block.position = p; return block;
    },
  });
  bot.physics = reference('prismarine-physics').Physics(registry, { getBlock: pos => bot.blockAt(pos) });
  return { bot, set };
}
const pos = (x, y = 0, z = 0) => new Vec3(x, y, z);
const goal = (x, y = 0, z = 0) => new upstream.goals.GoalBlock(x, y, z);
const clean = value => JSON.parse(JSON.stringify(value));
const summary = result => ({ status: result.status, cost: result.cost, visitedNodes: result.visitedNodes,
  generatedNodes: result.generatedNodes, path: clean(result.path) });
const methodOptions = { timeout: 100000, tickTimeout: 100000 };
function noConstruction(m) { m.allow1by1towers = false; m.allowParkour = false; }

// Deterministic graph/world comparisons with the pinned injector, not a reimplementation.
const worlds = [
  ['flat route', () => {}, () => goal(5)],
  ['obstacle and cost callback', f => { f.set(2, 0, 0, 'stone'); f.set(2, 1, 0, 'stone'); }, () => goal(5), m => {
    m.canDig = false; m.exclusionAreasStep.push(b => b.position?.z === -1 ? 3 : 0);
  }],
  ['already at goal', () => {}, () => goal(0)],
  ['sealed noPath', f => {
    for (const [x, z] of [[-1, 0], [1, 0], [0, -1], [0, 1]]) for (let y = 0; y < 3; y++) f.set(x, y, z, 'bedrock');
  }, () => goal(4), m => { m.canDig = false; }],
  ['custom goal', () => {}, () => ({ heuristic: p => Math.abs(5 - p.x), isEnd: p => p.x === 5 })],
  ['composite goal', () => {}, () => new upstream.goals.GoalCompositeAny([goal(5), goal(3, 0, 3)])],
  ['start on slab', f => {
    f.set(0, 0, 0, 'stone_slab', { type: 'bottom', waterlogged: false }); f.bot.entity.position.y = 0.5;
  }, () => goal(4)],
  ['raw output option', () => {}, () => goal(4), () => {}, { optimizePath: false }],
  ['search radius', f => { f.set(2, 0, 0, 'bedrock'); f.set(2, 1, 0, 'bedrock'); }, () => goal(4), m => {
    m.canDig = false;
  }, { searchRadius: 0.25 }],
  ['manual entity intersections preserved', () => {}, () => goal(4), m => {
    m.entityIntersections['2,0,0'] = 100;
  }, { resetEntityIntersects: false }],
  ['entity detection disabled leaves manual index intact', () => {}, () => goal(4), m => {
    m.entityIntersections['2,0,0'] = 100; m.allowEntityDetection = false;
  }],
  ['manual entity intersections reset', () => {}, () => goal(4), m => {
    m.entityIntersections['2,0,0'] = 100;
  }, { resetEntityIntersects: true }],
];

// Graph fixtures isolate the upstream shape postprocessor from route selection.
// Blocks are still real reference Blocks, and the subclass still exercises the public movement seam.
const shapeWorlds = [
  ['slab top', f => f.set(1, 0, 0, 'stone_slab', { type: 'bottom', waterlogged: false }), [pos(1, 1)], [1.5, 0.5, 0.5]],
  ['stair top', f => f.set(1, 0, 0, 'stone_stairs', { facing: 'east', half: 'bottom', shape: 'straight', waterlogged: false }), [pos(1, 1)], null],
  ['water center', f => f.set(1, 0, 0, 'water'), [pos(1)], [1.5, 0, 0.5]],
  ['descending ladder', f => f.set(1, 1, 0, 'ladder', { facing: 'north', waterlogged: false }), [pos(1, 1), pos(1, 0)], [1.5, 1, 0.5]],
  ['descending vine', f => f.set(1, 1, 0, 'vine', { north: true }), [pos(1, 1), pos(1, 0)], [1.5, 1, 0.5]],
  ['unknown support fallback', () => {}, [pos(1, 3)], [1.5, 2, 0.5]],
];

const checks = [
  ['installation retains caller state and unset-only defaults', ({ installPlanning, Movements }) => {
    const f = fixture(); const pf = { thinkTimeout: 0, tickTimeout: 0, searchRadius: 0,
      enablePathShortcut: false, LOSWhenPlacingBlocks: false, marker: {} };
    const marker = pf.marker; pf.movements = new Movements(f.bot); f.bot.pathfinder = pf;
    installPlanning(f.bot, pf); assert.equal(pf.marker, marker);
    assert.equal(pf.thinkTimeout, 0); assert.equal(pf.tickTimeout, 0); assert.equal(pf.searchRadius, 0);
    assert.equal(pf.LOSWhenPlacingBlocks, false);
    const defaults = {}; installPlanning(f.bot, defaults);
    assert.deepEqual([defaults.thinkTimeout, defaults.tickTimeout, defaults.searchRadius,
      defaults.enablePathShortcut, defaults.LOSWhenPlacingBlocks], [5000, 40, -1, false, true]);
  }],
  ['getPathTo is synchronous and returns the resumable AStar context', api => {
    const { bot } = setup(api); const result = bot.pathfinder.getPathTo(bot.pathfinder.movements, goal(3));
    assert.equal(result.status, 'success'); assert.equal(result.then, undefined);
    assert.equal(typeof result.context.compute, 'function'); assert.equal(result.context.goal.x, 3);
  }],
  ['explicit startMove skips startPos lookup and preserves resources', api => {
    const actual = setup(api), expected = setup(null);
    for (const f of [actual, expected]) {
      const start = new Move(2, 0, 0, 7, 0);
      const iter = f.bot.pathfinder.getPathFromTo(f.bot.pathfinder.movements, null, goal(5), { ...methodOptions, startMove: start });
      const first = iter.next(); f.result = first.value.result;
      assert.equal(first.value.astarContext, f.result.context); assert.equal(f.result.path[0].remainingBlocks, 7);
      assert.equal(iter.next().done, true);
    }
    assert.deepEqual(summary(actual.result), summary(expected.result));
  }],
  ['plain startMove obeys the public data contract without Vec3 methods', api => {
    const f = setup(api), r = setup(null);
    const startMove = { x: 2, y: 0, z: 0, remainingBlocks: 0, cost: 0, toBreak: [], toPlace: [], parkour: false, hash: '2,0,0' };
    for (const t of [f, r]) t.result = t.bot.pathfinder.getPathFromTo(t.bot.pathfinder.movements, null, goal(5), { startMove }).next().value.result;
    assert.deepEqual(summary(f.result), summary(r.result));
  }],
  ['getPathTo timeout overrides its configured budget', api => {
    const f = setup(api), r = setup(null); fakeTime = true;
    try {
      const result = f.bot.pathfinder.getPathTo(f.bot.pathfinder.movements, goal(5), 0);
      const expected = r.bot.pathfinder.getPathTo(r.bot.pathfinder.movements, goal(5), 0);
      assert.equal(result.status, 'timeout'); assert.deepEqual(summary(result), summary(expected));
    } finally { fakeTime = false; }
  }],
  ['partial generators resume the same context and honor total timeout', api => {
    const run = (impl, timeout) => {
      const f = setup(impl); clock = 0;
      const iter = f.bot.pathfinder.getPathFromTo(f.bot.pathfinder.movements, f.bot.entity.position, goal(8),
        { timeout, tickTimeout: 4, optimizePath: false });
      const states = [], contexts = [];
      for (let i = 0; i < 200; i++) { const next = iter.next(); if (next.done) break;
        states.push(summary(next.value.result)); contexts.push(next.value.astarContext); }
      assert(states.length > 1); assert.equal(states[0].status, 'partial');
      assert(contexts.every(c => c === contexts[0])); return states;
    };
    fakeTime = true;
    try {
      for (const timeout of [1000, 18]) {
        const actual = run(api, timeout), expected = run(null, timeout);
        assert.deepEqual(actual, expected); assert.equal(actual.at(-1).status, timeout === 1000 ? 'success' : 'timeout');
      }
    } finally { fakeTime = false; }
  }],
  ['optimized partial results cannot corrupt future search or be reused as search nodes', api => {
    // Pinned upstream demonstrably mutates the search node; retaining that defect is intentional non-conformance.
    const referenceFixture = setup(null); fakeTime = true; clock = 0;
    try {
      const first = referenceFixture.bot.pathfinder.getPathFromTo(referenceFixture.bot.pathfinder.movements,
        referenceFixture.bot.entity.position, goal(8), { timeout: 1000, tickTimeout: 4 }).next().value;
      assert.equal(first.result.status, 'partial'); assert(first.result.path.length > 0);
      assert(!Number.isInteger(first.astarContext.bestNode.data.x));
    } finally { fakeTime = false; }
    const f = setup(api); fakeTime = true; clock = 0;
    try {
      const iter = f.bot.pathfinder.getPathFromTo(f.bot.pathfinder.movements, f.bot.entity.position, goal(8), { timeout: 1000, tickTimeout: 4 });
      let result, context, yields = 0;
      for (const value of iter) {
        yields++; result = value.result; context = value.astarContext;
        if (result.status === 'partial' && result.path.length) {
          const exported = result.path[0]; exported.x = 1000; exported.toBreak.push(pos(1000));
        }
        assert([...context.openDataMap.values()].every(n => Number.isInteger(n.data.x) && !n.data.toBreak.some(p => p.x === 1000)));
      }
      assert(yields > 1); assert.equal(result.status, 'success'); assert.equal(result.path.at(-1).x, 8.5);
      assert(result.path.every(n => n.x < 1000 && n.toBreak.length === 0));
    } finally { fakeTime = false; }
  }],
  ['shortcut candidates work without a hook; an optional restriction must be synchronous', api => {
    const f = setup(api); f.bot.pathfinder.enablePathShortcut = true;
    const result = f.bot.pathfinder.getPathTo(f.bot.pathfinder.movements, goal(5));
    assert.equal(result.status, 'success'); assert.equal(result.path.length, 1);
    // Geometry selects a candidate; Java still owns native trajectory feasibility.
    assert.equal(f.bot.pathfinder.getPathFromTo(f.bot.pathfinder.movements, f.bot.entity.position, goal(5), { optimizePath: false }).next().value.result.status, 'success');
    api.installPlanning(f.bot, f.bot.pathfinder, { canShortcut: () => Promise.resolve(true) });
    assert.throws(() => f.bot.pathfinder.getPathTo(f.bot.pathfinder.movements, goal(5)), /synchronous|boolean/);
  }],
  ['additional shortcut restriction matches actual upstream player physics on flat ground', api => {
    const expected = setup(null), actual = setup(api);
    for (const f of [expected, actual]) f.bot.pathfinder.enablePathShortcut = true;
    api.installPlanning(actual.bot, actual.bot.pathfinder, { canShortcut: (a, b) => new Physics(actual.bot).canStraightLineBetween(a, b) });
    const a = actual.bot.pathfinder.getPathTo(actual.bot.pathfinder.movements, goal(5));
    const b = expected.bot.pathfinder.getPathTo(expected.bot.pathfinder.movements, goal(5));
    assert.deepEqual(summary(a), summary(b)); assert.equal(a.path.length, 1);
  }],
  ['shortcut origin and exclusions belong to the explicit query', api => {
    const f = setup(api), calls = [];
    f.bot.pathfinder.enablePathShortcut = true;
    api.installPlanning(f.bot, f.bot.pathfinder, { canShortcut: (from, to) => { calls.push([from.clone(), to.clone()]); return true; } });
    const queryMovements = new api.Movements(f.bot); noConstruction(queryMovements);
    f.bot.pathfinder.getPathFromTo(queryMovements, pos(3.5, 0, 0.5), goal(7)).next();
    assert.equal(calls[0][0].x, 3.5);
    calls.length = 0; queryMovements.exclusionAreasStep.push(() => 0);
    const result = f.bot.pathfinder.getPathFromTo(queryMovements, pos(3.5, 0, 0.5), goal(7)).next().value.result;
    assert(calls.length > 0); assert.equal(result.path.length, 1);
    // A zero-cost callback is not a barrier. Pinned upstream disables all
    // shortcutting merely because its installed callback list is nonempty.
    const reference = setup(null); reference.bot.pathfinder.enablePathShortcut = true;
    reference.bot.pathfinder.movements.exclusionAreasStep.push(() => 0);
    const pinned = reference.bot.pathfinder.getPathFromTo(reference.bot.pathfinder.movements,
      pos(3.5, 0, 0.5), goal(7)).next().value.result;
    assert.equal(pinned.path.length, 4);
  }],
  ['hypothetical slab start uses physical support rather than current body or emptyBlocks', api => {
    const results = [];
    for (const impl of [api, null]) {
      const f = setup(impl); f.set(0, 0, 0, 'stone_slab', { type: 'bottom', waterlogged: false });
      f.bot.entity.position = pos(10, 4, 0.5); f.bot.entity.onGround = false;
      const query = new (impl?.Movements ?? upstream.Movements)(f.bot); query.emptyBlocks.add(registry.blocksByName.stone_slab.id);
      results.push(f.bot.pathfinder.getPathFromTo(query, pos(0.5, 0.5, 0.5), goal(0), { timeout: 0 }).next().value.astarContext.bestNode.data.y);
    }
    // Policy metadata cannot erase the real slab collision surface. Pinned
    // upstream instead classifies this queried start using the actual body's onGround.
    assert.deepEqual(results, [1, 0]);
  }],
  ['shortcutting never skips queued dig/place actions or raw suffixes', api => {
    const f = setup(api); f.bot.pathfinder.enablePathShortcut = true;
    const points = [pos(1), pos(2), pos(3), pos(4)];
    const actions = { 1: { toBreak: [pos(2, 0, 0)], toPlace: [{ x: 2, y: -1, z: 0, dx: 1, dy: 0, dz: 0 }] } };
    const m = graphMovements(api.Movements, f.bot, points, actions);
    const calls = []; api.installPlanning(f.bot, f.bot.pathfinder, { canShortcut: (a, b) => { calls.push(b.x); return true; } });
    const result = f.bot.pathfinder.getPathFromTo(m, f.bot.entity.position, goal(4)).next().value.result;
    assert(result.path.some(n => n.toBreak.length === 1 && n.toPlace.length === 1));
    assert.deepEqual(result.path.slice(-3).map(n => n.x), [2, 3, 4]); assert(calls.every(x => x < 2));
    const r = setup(null); r.bot.pathfinder.enablePathShortcut = true;
    const referencePath = r.bot.pathfinder.getPathFromTo(graphMovements(upstream.Movements, r.bot, points, actions),
      r.bot.entity.position, goal(4)).next().value.result.path;
    assert(!referencePath.some(n => n.toBreak.length || n.toPlace.length)); // Pinned unsafe-shortcut regression.

  }],
  ['harvest tool selection honors effects, enchantments, ties and empty inventory', api => {
    for (const effects of [{}, { [registry.effectsByName.Haste.id]: { amplifier: 1 } }, { [registry.effectsByName.MiningFatigue.id]: { amplifier: 2 } }]) {
      const f = setup(api), r = setup(null);
      const items = [
        { type: registry.itemsByName.diamond_pickaxe.id, slot: 36 },
        { type: registry.itemsByName.iron_pickaxe.id, slot: 37, nbt: { type: 'compound', value: {
          Enchantments: { type: 'list', value: { type: 'compound', value: [
            { name: { type: 'string', value: 'efficiency' }, lvl: { type: 'short', value: 4 } },
          ] } },
        } } },
      ];
      for (const t of [f, r]) { t.bot.entity.effects = effects; t.bot.inventory.items = () => items; }
      for (const block of ['stone', 'dirt', 'bedrock']) {
        const b = Block.fromStateId(registry.blocksByName[block].minStateId, 0);
        assert.equal(f.bot.pathfinder.bestHarvestTool(b), r.bot.pathfinder.bestHarvestTool(b));
      }
      assert.equal(f.bot.pathfinder.bestHarvestTool(Block.fromStateId(registry.blocksByName.stone.minStateId, 0)), items[1]);
      f.bot.inventory.items = () => []; assert.equal(f.bot.pathfinder.bestHarvestTool(f.bot.blockAt(pos(0, -1))), null);
    }
  }],
];

function graphMovements(Base, bot, points, actions = {}) {
  class Graph extends Base {
    getNeighbors(node) {
      const index = points.findIndex(p => p.x === node.x && p.y === node.y && p.z === node.z) + 1;
      if (index >= points.length) return [];
      const p = points[index], action = actions[index] ?? {};
      return [new Move(p.x, p.y, p.z, node.remainingBlocks, 1, action.toBreak ?? [], action.toPlace ?? [])];
    }
  }
  return new Graph(bot);
}
function setup(api) {
  const f = fixture();
  if (api) {
    f.bot.pathfinder = { movements: new api.Movements(f.bot) };
    api.installPlanning(f.bot, f.bot.pathfinder);
  } else upstream.pathfinder(f.bot);
  noConstruction(f.bot.pathfinder.movements);
  f.bot.pathfinder.thinkTimeout = methodOptions.timeout; f.bot.pathfinder.tickTimeout = methodOptions.tickTimeout;
  return f;
}

// The only Node dependency in the guest's search imports is supplied as a clock binding.
const clockAlias = { name: 'guest-clock', setup(builder) {
  builder.onResolve({ filter: /^(node:)?perf_hooks$/ }, () => ({ path: 'clock', namespace: 'guest-clock' }));
  builder.onLoad({ filter: /.*/, namespace: 'guest-clock' }, () => ({ contents: 'const now = __mcNow; export const performance = { now: () => now() };' }));
} };
const bundleOptions = { stdin: { contents: `export { installPlanning } from './planning.mjs'; export { Movements } from './movements.mjs';`,
  resolveDir: fileURLToPath(new URL('../../bb-plugin/scripting', import.meta.url)), sourcefile: 'planning-fixture.mjs' },
  bundle: true, platform: 'browser', format: 'esm', target: 'es2022', write: false, nodePaths: [dependencies], plugins: [clockAlias] };
const bundled = await build(bundleOptions);
const realNow = performance.now.bind(performance), previousNow = Object.getOwnPropertyDescriptor(performance, 'now');
let fakeTime = false, clock = 0;
Object.defineProperty(performance, 'now', { configurable: true, value: () => fakeTime ? ++clock : realNow() });
globalThis.__mcNow = () => performance.now();
let passed = 0;
try {
  const api = await import(`data:text/javascript;base64,${Buffer.from(bundled.outputFiles[0].text + '\n//# sourceURL=planning-fixture-bundle.mjs').toString('base64')}`);
  for (const [name, prepare, makeGoal, configure = () => {}, options = {}] of worlds) {
    const expected = setup(null), actual = setup(api);
    for (const f of [expected, actual]) {
      prepare(f); configure(f.bot.pathfinder.movements);
      f.result = f.bot.pathfinder.getPathFromTo(f.bot.pathfinder.movements, f.bot.entity.position, makeGoal(), { ...methodOptions, ...options }).next().value.result;
    }
    assert.deepEqual(summary(actual.result), summary(expected.result), name); passed++;
  }
  for (const [name, prepare, points, firstExpected] of shapeWorlds) {
    const expected = setup(null), actual = setup(api);
    for (const [f, Base] of [[expected, upstream.Movements], [actual, api.Movements]]) {
      prepare(f); const m = graphMovements(Base, f.bot, points);
      f.result = f.bot.pathfinder.getPathFromTo(m, f.bot.entity.position, goal(points.at(-1).x, points.at(-1).y, points.at(-1).z), methodOptions).next().value.result;
    }
    assert.deepEqual(summary(actual.result), summary(expected.result), name);
    if (firstExpected) assert.deepEqual(actual.result.path.slice(0, 1).map(p => [p.x, p.y, p.z]), [firstExpected], name);
    passed++;
  }
  for (const [name, check] of checks) { try { check(api); } catch (error) { error.message = `${name}: ${error.message}`; throw error; } passed++; }
} finally {
  if (previousNow) Object.defineProperty(performance, 'now', previousNow); else delete performance.now;
  delete globalThis.__mcNow;
}

// Actual guest evaluation includes a full AStar route, with real reference Block snapshots.
const guestBundle = await build({ ...bundleOptions, format: 'iife', globalName: 'PlanningModule' });
const { getQuickJS } = plugin('quickjs-emscripten');
const runtime = (await getQuickJS()).newRuntime(); runtime.setMemoryLimit(64 * 1024 * 1024);
const vm = runtime.newContext(), now = vm.newFunction('__mcNow', () => vm.newNumber(realNow()));
vm.setProp(vm.global, '__mcNow', now); now.dispose();
const f = fixture(), snapshots = {};
for (let x = -2; x <= 8; x++) for (let y = -2; y <= 4; y++) for (let z = -2; z <= 2; z++) snapshots[`${x},${y},${z}`] = clean(f.bot.blockAt(pos(x, y, z)));
const guestData = JSON.stringify({ registry: { blocksArray: registry.blocksArray, blocksByName: registry.blocksByName,
  itemsByName: registry.itemsByName, blockCollisionShapes: registry.blockCollisionShapes }, snapshots });
const evaluated = vm.evalCode(guestBundle.outputFiles[0].text + `
  const data = ${guestData};
  globalThis.__mcNow = () => { throw new Error('Guest code replaced the clock binding'); };
  // Reuse Vec3 through a generated Move so this fixture adds no host object handles.
  const bot = { registry: data.registry, game: {minY: -16}, entities: {},
    entity: {width: 0.6, height: 1.8, position: {x: 0.5, y: 0, z: 0.5}, effects: {}, onGround: true},
    inventory: {items: () => []}, blockAt(p) { const b = data.snapshots[Math.floor(p.x)+','+Math.floor(p.y)+','+Math.floor(p.z)]; return b ? {...b, position: p.floored()} : null; } };
  bot.pathfinder = { movements: new PlanningModule.Movements(bot) };
  const movement = bot.pathfinder.movements; movement.canDig = false; movement.allow1by1towers = false; movement.allowParkour = false;
  PlanningModule.installPlanning(bot, bot.pathfinder);
  const neighbors = []; movement.getMoveForward({x: 0, y: 0, z: 0, remainingBlocks: 0}, {x: 1, z: 0}, neighbors);
  const start = neighbors[0];
  let result;
  for (const step of bot.pathfinder.getPathFromTo(movement, null,
    {heuristic: p => Math.abs(5-p.x), isEnd: p => p.x === 5}, {startMove: start})) result = step.result;
  JSON.stringify({status: result.status, endX: result.path.at(-1).x, resumption: typeof result.context.compute, nodeGlobals: typeof process});
`);
try {
  if (evaluated.error) throw new Error(JSON.stringify(vm.dump(evaluated.error)));
  assert.deepEqual(JSON.parse(vm.getString(evaluated.value)), { status: 'success', endX: 5.5, resumption: 'function', nodeGlobals: 'undefined' });
} finally { evaluated.error?.dispose(); evaluated.value?.dispose(); vm.dispose(); runtime.dispose(); }
console.log(JSON.stringify({ status: 'passed', scenarios: passed, differentialWorlds: worlds.length + shapeWorlds.length,
  contractScenarios: checks.length, correctedUpstreamDefects: ['mutable partial search nodes', 'global query policies/origin', 'shortcut action loss'],
  intentionalShortcutDifferences: ['native geometry candidates without an external hook', 'zero-cost callbacks permit shortcuts',
    'queried collision support is independent of current onGround and emptyBlocks'],
  quickjsRoute: 'passed with captured host clock', bundleBytes: guestBundle.outputFiles[0].contents.length,
  limitation: 'Planning only. No native route execution, live game, or server conformance tested.' }, null, 2));
