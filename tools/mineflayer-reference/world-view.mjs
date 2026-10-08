// Differential scenarios authored before world-view.mjs. No game/server needed.
// MINEFLAYER_REFERENCE_ROOT=/path/to/tools/mineflayer-reference \
// MINEFLAYER_PLUGIN_ROOT=/path/to/bb-plugin node tools/mineflayer-reference/world-view.mjs
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../..', import.meta.url));
const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? resolve(root, 'tools/mineflayer-reference'));
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? resolve(root, 'bb-plugin'));
const reference = createRequire(resolve(referenceRoot, 'package.json'));
const plugin = createRequire(resolve(pluginRoot, 'package.json'));
for (const [name, version] of Object.entries({ 'prismarine-world': '3.7.0', 'prismarine-block': '1.23.0', 'minecraft-data': '3.117.0', vec3: '0.1.10' })) {
  assert.equal(reference(resolve(referenceRoot, 'node_modules', name, 'package.json')).version, version, `Pinned ${name}`);
}
const registry = reference('minecraft-data')('1.21.1');
const Block = reference('prismarine-block')('1.21.1');
const { Vec3 } = reference('vec3');
const WorldSync = reference('prismarine-world/src/worldsync');
// Exercise the installed WorldSync method against exactly the same synthetic
// lookup as the guest, without loading/generating any world or chunk.
const referenceView = getBlock => Object.assign(new WorldSync(null), { getBlock });
const data = Object.fromEntries(['blocksArray', 'biomesArray', 'blockCollisionShapes', 'materials', 'effectsByName', 'enchantmentsByName', 'language'].map(k => [k, registry[k]]));

function exercise(createWorldView, Block, Vec3, s) {
  const vector = values => new Vec3(...values);
  const trace = [], matches = [], supplied = [];
  const lookup = position => {
    const pos = position.floored();
    trace.push([pos.x, pos.y, pos.z]);
    if (s.lookupError) throw new Error('lookup failed');
    if ((s.unloaded ?? []).some(p => p.every((n, i) => n === [pos.x, pos.y, pos.z][i]))) return null;
    const spec = (s.blocks ?? []).find(b => b.at.every((n, i) => n === [pos.x, pos.y, pos.z][i]));
    if (!spec && s.onlyListedLoaded) return null;
    const block = Block.fromProperties(spec?.name ?? 'air', spec?.properties ?? {}, 1);
    if (spec?.shapes) block.shapes = spec.shapes;
    block.position = pos;
    supplied.push(block);
    return block;
  };
  const world = createWorldView(lookup);
  const describe = hit => hit == null ? null : {
    name: hit.name, position: hit.position, face: hit.face, intersect: hit.intersect,
    instance: hit instanceof Block, positionVec: hit.position instanceof Vec3,
    intersectVec: hit.intersect === undefined || hit.intersect instanceof Vec3,
    supplied: supplied.includes(hit), shapes: hit.shapes,
  };
  let value;
  try {
    if (s.kind === 'get') value = describe(world.getBlock(vector(s.origin)));
    else {
      const from = vector(s.origin), dir = vector(s.direction);
      if (s.normalize !== false) dir.normalize();
      const before = { from: from.clone(), dir: dir.clone() };
      let matcher;
      if (s.matcher) matcher = (block, iter) => {
        matches.push({ name: block.name, position: block.position, cell: { ...iter.block },
          origin: iter.pos.clone(), direction: iter.dir.clone(), range: iter.maxDistance,
          identities: [iter.pos === from, iter.dir === dir], methods: [typeof iter.next, typeof iter.intersect] });
        if (s.matcher === 'throw') throw new Error('matcher failed');
        if (s.matcher === 'skip') return false;
        if (s.matcher === 'air') return block.name === 'air';
        if (s.matcher === 'water') return block.name === 'water';
        if (s.matcher === 'shape') {
          const hit = iter.intersect(block.shapes, block.position);
          if (!hit) return false;
          block.face = hit.face;
          block.intersect = hit.pos;
          return true;
        }
        if (s.matcher === 'advance') { iter.next(); return false; }
        return block.name === 'stone';
      };
      const hit = world.raycast(from, dir, s.range, matcher);
      value = { hit: describe(hit), untouched: from.equals(before.from) && dir.equals(before.dir), synchronous: !(hit && typeof hit.then === 'function') };
    }
  } catch (error) { value = { error: error.name, message: error.message }; }
  if (s.project === 'hit') {
    const hit = value.hit;
    return hit === null ? null : hit ? { name: hit.name, position: [hit.position.x, hit.position.y, hit.position.z], intersect: hit.intersect && [hit.intersect.x, hit.intersect.y, hit.intersect.z], face: hit.face } : value;
  }
  if (s.project === 'error') return { error: value.error, calls: trace.length };
  return { value, trace, matches };
}
function encode(value) {
  if (value === undefined) return ['undefined'];
  if (typeof value === 'number') return Number.isFinite(value) ? Math.round(value * 1e12) / 1e12 : ['number', String(value)];
  if (Array.isArray(value)) return value.map(encode);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, encode(value[k])]));
  return value;
}
const cube = at => ({ at, name: 'stone' });
const cases = [], fixes = [], bounds = [];
const add = (label, s) => cases.push({ label, s });
const fix = (label, s, expected) => fixes.push({ label, s: { ...s, project: 'hit' }, expected });
const hit = (name, position, intersect, face) => ({ name, position, intersect, face });
add('fractional getBlock floors and preserves type', { kind: 'get', origin: [-0.1, 1.9, 2.2], blocks: [cube([-1, 1, 2])] });
add('unloaded getBlock stays null', { kind: 'get', origin: [0.1, 0, 0], onlyListedLoaded: true });
const axes = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
for (const dir of axes) {
  const target = dir.map(n => n * 3);
  for (const range of [0, 1, 2.49, 2.5, 3, 3.5, 8]) {
    add(`cube axes ${dir} range ${range}`, { origin: [0.5, 0.5, 0.5], direction: dir, range, blocks: [cube(target)] });
  }
}
for (const origin of [[0, 0, 0], [-1, -1, -1], [-0.5, -0.5, -0.5]]) {
  for (const direction of [[1, 1, 0], [-1, 1, 0], [0, -1, 1], [1, 1, 1], [-1, -1, -1]]) {
    add(`DDA ties ${origin}/${direction}`, { origin, direction, range: 8, blocks: [cube([2, 2, 2]), cube([-3, -3, -3]), cube([2, 1, 0]), cube([-2, 1, 0]), cube([0, -2, 1])] });
  }
}
for (const name of ['air', 'water', 'lava', 'short_grass', 'oak_slab', 'oak_stairs', 'oak_fence', 'snow', 'stone']) {
  for (const y of [0.125, 0.5, 0.875]) {
    const s = { origin: [-2.5, y, 0.5], direction: [1, 0, 0], range: 6, blocks: [{ at: [0, 0, 0], name }, cube([2, 0, 0])] };
    // This pre-authored fixture exposed another upstream defect during the
    // differential run: the first stair box is [0,0,0,1,1,0.5]. A parallel
    // ray at z=0.5 lies on its closed boundary and enters at x=0, not x=2.
    if (name === 'oak_stairs' && y === 0.125) fix('parallel ray on stair upper boundary', s, hit('oak_stairs', [0, 0, 0], [0, y, 0.5], 4));
    else add(`shape ${name} height ${y}`, s);
  }
}
for (const type of ['top', 'bottom', 'double']) {
  for (const y of [0.25, 0.75]) add(`slab ${type}/${y}`, { origin: [-1.5, y, 0.5], direction: [1, 0, 0], range: 5, blocks: [{ at: [0, 0, 0], name: 'oak_slab', properties: { type, waterlogged: true } }] });
}
for (const facing of ['north', 'east', 'south', 'west']) for (const half of ['top', 'bottom']) for (const shape of ['straight', 'inner_left', 'inner_right', 'outer_left', 'outer_right']) {
  add(`stair ${facing}/${half}/${shape}`, { origin: [-2, 0.75, 0.25], direction: [1, 0, 0], range: 5, blocks: [{ at: [0, 0, 0], name: 'oak_stairs', properties: { facing, half, shape, waterlogged: false } }] });
}
const inset = { at: [1, 0, 0], name: 'stone', shapes: [[0.75, 0, 0, 1, 1, 1], [0.25, 0, 0, 0.5, 1, 1]] };
add('nearest box independent of shapes order', { origin: [0.5, 0.5, 0.5], direction: [1, 0, 0], range: 2, blocks: [inset] });
add('unloaded cells skipped as upstream', { origin: [0.5, 0.5, 0.5], direction: [1, 0, 0], range: 5, onlyListedLoaded: true, blocks: [cube([3, 0, 0])] });
add('empty loaded and unloaded segments miss', { origin: [0.5, 0.5, 0.5], direction: [1, 0, 0], range: 5, unloaded: [[1, 0, 0], [3, 0, 0]] });
add('zero direction empty', { origin: [0, 0, 0], direction: [0, 0, 0], range: 5 });
add('inside cube at grid origin +x', { origin: [0, 0, 0], direction: [1, 0, 0], range: 5, blocks: [cube([0, 0, 0])] });
for (const matcher of ['stone', 'shape', 'skip', 'air', 'water', 'advance', 'throw']) {
  add(`matcher ${matcher}`, { origin: [0.5, 0.75, 0.5], direction: [1, 0, 0], range: 5, matcher, blocks: [{ at: [1, 0, 0], name: 'water' }, cube([3, 0, 0])] });
}
add('lookup failure propagates', { origin: [0, 0, 0], direction: [1, 0, 0], range: 1, lookupError: true });
add('unsupported nonunit retains parametric interpretation', { origin: [0, 0.5, 0.5], direction: [2, 0, 0], normalize: false, range: 2, blocks: [cube([3, 0, 0])] });
// Corrections are independent, hand-derived ray/AABB geometry. These expose
// upstream defects instead of using guest output as its own oracle.
fix('first partial shape uses floored block position', { origin: [0.5, 0.75, 0.5], direction: [1, 0, 0], range: 3, blocks: [{ at: [0, 0, 0], name: 'oak_slab', properties: { type: 'bottom' } }, cube([2, 0, 0])] }, hit('stone', [2, 0, 0], [2, 0.75, 0.5], 4));
fix('first partial shape ahead uses actual offset', { origin: [0.1, 0.5, 0.5], direction: [1, 0, 0], range: 2, blocks: [{ at: [0, 0, 0], name: 'stone', shapes: [[0.75, 0, 0, 1, 1, 1]] }] }, hit('stone', [0, 0, 0], [0.75, 0.5, 0.5], 4));
fix('shape behind origin cannot hit', { origin: [0.75, 0.5, 0.5], direction: [1, 0, 0], range: 2, blocks: [{ at: [0, 0, 0], name: 'stone', shapes: [[0, 0, 0, 0.25, 1, 1]] }] }, null);
fix('partial shape beyond range is excluded', { origin: [0.5, 0.5, 0.5], direction: [1, 0, 0], range: 0.6, blocks: [inset] }, null);
fix('shape exactly at range is included', { origin: [0.1, 0.5, 0.5], direction: [1, 0, 0], range: 0.65, blocks: [{ at: [0, 0, 0], name: 'stone', shapes: [[0.75, 0, 0, 1, 1, 1]] }] }, hit('stone', [0, 0, 0], [0.75, 0.5, 0.5], 4));
fix('inside cube reports origin and entry face', { origin: [0.5, 0.5, 0.5], direction: [0, 1, 0], range: 2, blocks: [cube([0, 0, 0])] }, hit('stone', [0, 0, 0], [0.5, 0.5, 0.5], 0));
fix('negative ray starting on face', { origin: [0, 0, 0], direction: [-1, 0, 0], range: 1, blocks: [cube([0, 0, 0])] }, hit('stone', [0, 0, 0], [0, 0, 0], 5));
fix('zero direction has no hit even in solid', { origin: [0.5, 0.5, 0.5], direction: [0, 0, 0], range: 1, blocks: [cube([0, 0, 0])] }, null);
fix('matcher intersection rejects behind origin', { origin: [0.75, 0.5, 0.5], direction: [1, 0, 0], range: 2, matcher: 'shape', blocks: [{ at: [0, 0, 0], name: 'stone', shapes: [[0, 0, 0, 0.25, 1, 1]] }] }, null);

// Invalid/unbounded requests must terminate in the actual guest. Do not run
// Infinity/NaN cases in upstream: source has no guard and can loop forever.
for (const [label, extra] of [['negative range', { range: -1 }], ['infinite range', { range: Infinity }], ['NaN origin', { origin: [NaN, 0, 0] }], ['infinite direction', { direction: [Infinity, 0, 0], normalize: false }]]) {
  bounds.push({ label, s: { origin: [0, 0, 0], direction: [1, 0, 0], range: 1, ...extra, project: 'error' }, expected: { error: 'RangeError', calls: 0 } });
}
bounds.push({ label: 'finite ray has a traversal budget', s: { origin: [0, 0, 0], direction: [1, 0, 0], range: 1000000, onlyListedLoaded: true, project: 'error' }, expected: { error: 'RangeError', calls: 65536 } });
bounds.push({ label: 'shape count budget', s: { origin: [0, 0, 0], direction: [1, 0, 0], range: 1, blocks: [{ ...cube([0, 0, 0]), shapes: Array(262145).fill([2, 2, 2, 3, 3, 3]) }], project: 'error' }, expected: { error: 'RangeError', calls: 1 } });
const runReference = s => encode(exercise(referenceView, Block, Vec3, structuredClone(s)));
const expected = cases.map(({ s }) => runReference(s));
for (const { label, s, expected } of fixes) assert.notDeepEqual(runReference(s), encode(expected), `Demonstrate upstream defect: ${label}`);
if (process.argv.includes('--reference-only')) {
  console.log(`Reference ready: ${cases.length} parity scenarios, ${fixes.length} demonstrated geometry defects, ${bounds.length} guest-bound scenarios. No guest evaluated.`);
} else {
  const { build } = plugin('esbuild');
  const { getQuickJS } = plugin('quickjs-emscripten');
  const bundle = await build({
    stdin: { contents: `import { createWorldView } from './bb-plugin/scripting/world-view.mjs'; import { createBlockClass } from './bb-plugin/scripting/blocks.mjs'; import { Vec3 } from 'vec3'; globalThis.createWorldView = createWorldView; globalThis.Block = createBlockClass(${JSON.stringify(data)}); globalThis.Vec3 = Vec3;`, resolveDir: root },
    bundle: true, write: false, platform: 'browser', format: 'iife', target: 'es2022', nodePaths: [resolve(referenceRoot, 'node_modules'), resolve(pluginRoot, 'node_modules')],
    // The harness imports from two dependency roots; the production guest has
    // one Vec3 constructor. Keep the reference and guest in that same realm.
    alias: { vec3: plugin.resolve('vec3') },
  });
  const vm = (await getQuickJS()).newContext();
  vm.runtime.setMemoryLimit(256 * 1024 * 1024);
  let deadline;
  vm.runtime.setInterruptHandler(() => Date.now() > deadline);
  const evaluate = code => {
    deadline = Date.now() + 10000;
    const result = vm.evalCode(code), handle = result.error ?? result.value;
    const value = vm.dump(handle); handle.dispose();
    if (result.error) throw new Error(JSON.stringify(value));
    return value;
  };
  // JSON cannot represent NaN/Infinity; build literals without conflating null.
  const literal = value => typeof value === 'number' && !Number.isFinite(value) ? String(value)
    : Array.isArray(value) ? `[${value.map(literal).join(',')}]`
      : value && typeof value === 'object' ? `{${Object.entries(value).map(([k, v]) => `${JSON.stringify(k)}:${literal(v)}`).join(',')}}`
        : JSON.stringify(value);
  const guest = s => JSON.parse(evaluate(`JSON.stringify(encode(exercise(createWorldView, Block, Vec3, ${literal(s)})))`));
  try {
    evaluate(bundle.outputFiles[0].text);
    evaluate(`globalThis.exercise = ${exercise}; globalThis.encode = ${encode};`);
    for (const [i, { label, s }] of cases.entries()) assert.deepEqual(guest(s), expected[i], label);
    for (const { label, s, expected } of [...fixes, ...bounds]) assert.deepEqual(guest(s), encode(expected), label);
    console.log(`PASS: ${cases.length} pinned world/goal differential scenarios, ${fixes.length} geometry corrections, ${bounds.length} bounds in bundled QuickJS. No native cache or live world conformance claimed.`);
  } finally { vm.dispose(); }
}
