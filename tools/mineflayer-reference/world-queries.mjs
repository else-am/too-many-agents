// Scenarios authored and reference-run before world-queries.mjs implementation.
// No client/server: invoke installed plugins over the same synthetic loaded cells.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../..', import.meta.url));
const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? resolve(root, 'tools/mineflayer-reference'));
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? resolve(root, 'bb-plugin'));
const reference = createRequire(resolve(referenceRoot, 'package.json'));
const plugin = createRequire(resolve(pluginRoot, 'package.json'));
for (const [name, version] of Object.entries({ mineflayer: '4.39.0', 'prismarine-world': '3.7.0', 'prismarine-block': '1.23.0', 'minecraft-data': '3.117.0', vec3: '0.1.10' })) assert.equal(reference(resolve(referenceRoot, 'node_modules', name, 'package.json')).version, version);
const registry = reference('minecraft-data')('1.21.1');
const Block = reference('prismarine-block')(registry);
const { Vec3 } = reference('vec3');
const { EventEmitter } = reference('node:events');
const WorldSync = reference('prismarine-world/src/worldsync');
const injectBlocks = reference('mineflayer/lib/plugins/blocks');
const injectRay = reference('mineflayer/lib/plugins/ray_trace');
const data = Object.fromEntries(['blocksArray', 'biomesArray', 'blockCollisionShapes', 'materials', 'effectsByName', 'enchantmentsByName', 'language'].map(k => [k, registry[k]]));
function referenceInstall(bot) {
  bot.registry = registry; bot.supportFeature = registry.supportFeature;
  bot._client = new EventEmitter();
  injectBlocks(bot, { version: '1.21.1', hideErrors: true });
  injectRay(bot);
}
function referenceWorld(lookup) { return Object.assign(new WorldSync(null), { getBlock: lookup }); }
function exercise(install, makeWorld, Block, Vec3, EventEmitter, s) {
  const vector = a => new Vec3(...a);
  const bot = new EventEmitter();
  const min = vector(s.min ?? [-16, -16, -16]), max = vector(s.max ?? [16, 16, 16]);
  const inside = p => p.x >= min.x && p.y >= min.y && p.z >= min.z && p.x < max.x && p.y < max.y && p.z < max.z;
  const specs = new Map((s.blocks ?? []).map(b => [b.at.join(','), b]));
  const unknown = new Set((s.unknown ?? []).map(p => p.join(',')));
  let calls = 0, matches = 0, extras = 0;
  const lookup = p => {
    calls++;
    p = p.floored();
    if (!inside(p) || unknown.has([p.x, p.y, p.z].join(','))) return null;
    const spec = specs.get([p.x, p.y, p.z].join(','));
    const b = Block.fromProperties(spec?.name ?? 'air', spec?.properties ?? {}, 1);
    b.position = p;
    if (spec?.shapes) b.shapes = spec.shapes;
    if (spec?.info) b.fixtureInfo = spec.info;
    return b;
  };
  bot.username = 'Body';
  bot.entity = { id: 1, username: 'Body', type: 'player', position: vector(s.origin ?? [0.5, 0, 0.5]), height: s.height ?? 1, eyeHeight: s.eyeHeight ?? 1, yaw: s.yaw ?? 0.2, pitch: s.pitch ?? 0.1, width: 0.6 };
  bot.entities = { 1: bot.entity };
  for (const spec of s.entities ?? []) bot.entities[spec.id] = { type: 'mob', width: 0.6, height: 1.8, ...spec, position: vector(spec.at) };
  bot.game = { minY: -64, height: 384 };
  install(bot, { getLoadedBounds: () => s.noLoaded ? null : { min, max } });
  bot.world = makeWorld(lookup);
  // The reference plugin's public findBlocks consumes section availability.
  // No fabricated expected query: installed code does all iteration/matching.
  bot.world.getColumn = (x, z) => {
    if (x * 16 >= max.x || x * 16 + 16 <= min.x || z * 16 >= max.z || z * 16 + 16 <= min.z) return null;
    const sections = Array.from({ length: 24 }, (_, i) => i * 16 - 64 >= max.y || i * 16 - 48 <= min.y ? null : {});
    return { sections };
  };
  // Guest uses the injected public blockAt; reference installs its real one.
  if (!bot.blockAt) bot.blockAt = (p, extra = true) => { const b = bot.world.getBlock(p); if (b && extra) b.painting = undefined; return b; };
  const describe = b => b === null ? null : { name: b.name, at: [b.position.x, b.position.y, b.position.z], face: b.face, intersect: b.intersect && [b.intersect.x, b.intersect.y, b.intersect.z], typed: b instanceof Block, vec: b.position instanceof Vec3 };
  const matcher = b => {
    if (!b) return false;
    matches++;
    if (s.match === 'throw') throw new Error('matching failed');
    if (s.match === 'extra') return Object.hasOwn(b, 'painting') && b.name === 'stone';
    if (s.match === 'position') return b.position?.equals(vector(s.target));
    return (s.names ?? ['stone']).includes(b.name);
  };
  try {
    let value;
    if (!s.kind || s.kind === 'find' || s.kind === 'one') {
      const matching = s.ids ? s.ids.map(n => Block.fromProperties(n, {}, 1).type) : s.scalar ? Block.fromProperties(s.scalar, {}, 1).type : matcher;
      const options = { matching };
      if (s.point) options.point = vector(s.point);
      for (const key of ['count', 'maxDistance']) if (Object.hasOwn(s, key)) options[key] = s[key];
      if (s.extra === 'function' || s.extra === 'throw') options.useExtraInfo = b => { extras++; if (s.extra === 'throw') throw new Error('extra failed'); return Object.hasOwn(b, 'painting') && b.fixtureInfo === 'wanted'; };
      else if (Object.hasOwn(s, 'extra')) options.useExtraInfo = s.extra;
      value = s.kind === 'one' ? describe(bot.findBlock(options)) : bot.findBlocks(options).map(p => ({ at: [p.x, p.y, p.z], vec: p instanceof Vec3 }));
      if (s.checkCallbacks) value = { value, matchingCalled: matches > 0, extraCalled: extras > 0 };
    } else if (s.kind === 'see') value = bot.canSeeBlock({ position: vector(s.target) });
    else if (s.kind === 'entity') { const e = bot.entityAtCursor(s.maxDistance); value = e === null ? null : { id: e.id, same: e === bot.entities[e.id] }; }
    else {
      const match = s.matcher ? (b, iter) => { matches++; if (s.matcher === 'throw') throw new Error('ray matcher failed'); if (s.matcher === 'water') return b.name === 'water'; const hit = iter.intersect(b.shapes, b.position); if (!hit) return false; b.intersect = hit.pos; b.face = hit.face; return true; } : undefined;
      value = describe(s.kind === 'otherCursor' ? bot.blockAtEntityCursor(bot.entity, s.maxDistance, match) : s.kind === 'legacy' ? (bot.blockInSight(16, 0.25) ?? null) : bot.blockAtCursor(s.maxDistance, match));
    }
    return value;
  } catch (e) { return { error: e.name, message: e.message }; }
}
function encode(v) {
  if (v === undefined) return ['undefined'];
  if (typeof v === 'number') return Number.isFinite(v) ? Math.round(v * 1e10) / 1e10 : String(v);
  if (Array.isArray(v)) return v.map(encode);
  if (v && typeof v === 'object') return Object.fromEntries(Object.keys(v).sort().map(k => [k, encode(v[k])]));
  return v;
}
const cases = [], fixes = [], bounds = [];
const add = (label, s) => cases.push({ label, s });
const fix = (label, s, expected) => fixes.push({ label, s, expected });
const cube = (at, name = 'stone', extra = {}) => ({ at, name, ...extra });
const positions = value => value.map(at => ({ at, vec: true }));
const fixture = [cube([0, 0, 2]), cube([0, 0, -2]), cube([-2, 0, 0]), cube([2, 0, 0]), cube([0, 2, 0]), cube([0, -2, 0]), cube([5, 0, 0], 'dirt')];
for (const count of [undefined, 0, 1, 2, 6, 20]) for (const maxDistance of [0, 1, 2, 2.5, 8, 16]) add(`ordering/count/radius ${count}/${maxDistance}`, { blocks: fixture, count, maxDistance, point: [0.9, 0.8, 0.7] });
for (const select of [{ scalar: 'stone' }, { ids: ['stone', 'dirt'] }, { names: ['stone', 'dirt'] }, { ids: [] }]) add(`matching ${JSON.stringify(select)}`, { ...select, blocks: fixture, count: 20, maxDistance: 8 });
for (const extra of [false, true, 'function']) add(`extraInfo ${extra}`, { blocks: [cube([1, 0, 1], 'stone', { info: 'wanted' }), cube([2, 0, 1])], extra, count: 8, maxDistance: 4, checkCallbacks: true });
add('boolean true matching sees extra fields', { blocks: fixture, match: 'extra', extra: true });
add('boolean false does not add painting', { blocks: fixture, match: 'extra', extra: false });
add('matching exception', { match: 'throw' });
add('extra exception only for matched Blocks', { blocks: fixture, extra: 'throw' });
add('extra not called on no matches', { extra: 'throw', checkCallbacks: true });
add('findBlock returns typed block', { kind: 'one', blocks: fixture, count: 6 });
add('findBlock miss', { kind: 'one' });
add('unknown search cells are not matches', { blocks: fixture, unknown: [[0, 0, 2]], count: 10, maxDistance: 8 });
add('negative sections', { min: [-48, -32, -48], max: [-16, 0, -16], point: [-31.2, -16.1, -31.2], blocks: [cube([-32, -17, -32]), cube([-33, -17, -32]), cube([-31, -17, -32])], count: 3 });
// Reference's early break can choose a farther cell before even starting shell 1.
fix('finish relevant sections for closest result', { point: [15, 0, 0], blocks: [cube([0, 0, 0]), cube([16, 0, 0])], min: [0, 0, 0], max: [32, 16, 16], count: 1 }, positions([[16, 0, 0]]));
fix('sphere extends beyond upstream octahedron radius', { point: [0, 0, 0], maxDistance: 64, blocks: [cube([32, 32, 32])], min: [32, 32, 32], max: [48, 48, 48] }, positions([[32, 32, 32]]));
for (const name of ['stone', 'water', 'oak_slab', 'oak_stairs', 'oak_fence']) {
  for (const kind of ['cursor', 'otherCursor']) add(`${kind} ${name}`, { kind, blocks: [cube([-1, 1, -3], name)], maxDistance: 8 });
}
for (const maxDistance of [0, 1, 2, 3, 8]) add(`cursor distance ${maxDistance}`, { kind: 'cursor', blocks: [cube([-1, 1, -3])], maxDistance });
add('cursor matcher', { kind: 'cursor', matcher: 'water', blocks: [cube([-1, 1, -3], 'water')], maxDistance: 8 });
add('cursor matcher error', { kind: 'cursor', matcher: 'throw', maxDistance: 2 });
add('legacy cursor alias', { kind: 'legacy', blocks: [cube([-1, 1, -3])] });
for (const target of [[0, 1, -3], [3, 0, 0], [-3, 2, 1]]) for (const name of ['stone', 'water', 'oak_slab']) for (const blocked of [false, true]) add(`visibility ${target}/${name}/${blocked}`, { kind: 'see', target, blocks: [cube(target, name), ...(blocked ? [cube([0, 1, -1]), cube([1, 0, 0]), cube([-1, 1, 0])] : [])], maxDistance: 8 });
add('nearest entity identity', { kind: 'entity', maxDistance: 8, entities: [{ id: 2, at: [-0.1, 0, -2] }, { id: 3, at: [-0.8, 0, -4] }] });
add('entity behind wall', { kind: 'entity', maxDistance: 8, blocks: [cube([-1, 1, -2])], entities: [{ id: 2, at: [-0.3, 0, -3] }] });
add('object entities excluded like upstream', { kind: 'entity', maxDistance: 8, entities: [{ id: 2, type: 'object', at: [-0.1, 0, -2] }] });
fix('zero yaw/pitch are valid; eye not height', { kind: 'cursor', yaw: 0, pitch: 0, height: 2, eyeHeight: 1, blocks: [cube([0, 1, -3])], maxDistance: 8 }, { name: 'stone', at: [0, 1, -3], face: 3, intersect: [0.5, 1, -2], typed: true, vec: true });
fix('entity range measured from eye with AABB extent', { kind: 'entity', yaw: 0, pitch: 0, eyeHeight: 1.5, maxDistance: 3, entities: [{ id: 2, at: [0.5, 0, -2.7], width: 1 }] }, { id: 2, same: true });
fix('entity cannot be selected behind eye-height wall', { kind: 'entity', yaw: 0, pitch: 0, height: 2, eyeHeight: 1, maxDistance: 5, blocks: [cube([0, 1, -1])], entities: [{ id: 2, at: [0.5, 0, -2] }] }, null);
fix('canSeeBlock always returns boolean on miss', { kind: 'see', target: [4, 0, 0], unknown: [[4, 0, 0]] }, { error: 'Error', message: 'World query entered an unknown block cell' });
for (const s of [{ maxDistance: Infinity }, { count: -1 }, { count: 1.5 }, { maxDistance: -1 }, { point: [NaN, 0, 0] }, { min: [-100, -100, -100], max: [100, 100, 100], maxDistance: 100 }, { kind: 'cursor', maxDistance: Infinity }, { kind: 'cursor', yaw: NaN }, { kind: 'entity', maxDistance: -1 }]) bounds.push({ label: `bounds ${JSON.stringify(s)}`, s });
const runReference = s => encode(exercise(referenceInstall, referenceWorld, Block, Vec3, EventEmitter, structuredClone(s)));
const expected = cases.map(({ s }) => runReference(s));
for (const { label, s, expected } of fixes) assert.notDeepEqual(runReference(s), encode(expected), `Demonstrate source defect: ${label}`);
assert(expected.some(v => Array.isArray(v) && v.length === 6), 'nonempty sorted block search');
assert(expected.some(v => v?.name === 'stone'), 'nonempty Block result');
assert(expected.some(v => v?.id === 2), 'nonempty Entity result');
if (process.argv.includes('--reference-only')) console.log(`Reference ready: ${cases.length} parity cases, ${fixes.length} source defects, ${bounds.length} guest bounds; no guest loaded.`);
else {
  const { build } = plugin('esbuild'); const { getQuickJS } = plugin('quickjs-emscripten');
  const bundle = await build({ stdin: { contents: `import {installWorldQueries} from './bb-plugin/scripting/world-queries.mjs';import {createWorldView} from './bb-plugin/scripting/world-view.mjs';import {createBlockClass} from './bb-plugin/scripting/blocks.mjs';import {Vec3} from 'vec3';import {EventEmitter} from 'events';globalThis.install=installWorldQueries;globalThis.makeWorld=createWorldView;globalThis.Block=createBlockClass(${JSON.stringify(data)});globalThis.Vec3=Vec3;globalThis.EventEmitter=EventEmitter;`, resolveDir: root }, bundle: true, write: false, platform: 'browser', format: 'iife', target: 'es2022', metafile: true, alias: { vec3: plugin.resolve('vec3'), events: plugin.resolve('events/') }, nodePaths: [resolve(pluginRoot, 'node_modules'), resolve(referenceRoot, 'node_modules')] });
  assert(Object.values(bundle.metafile.outputs).every(o => o.imports.length === 0), 'no external loader/builtins');
  const vm = (await getQuickJS()).newContext(); vm.runtime.setMemoryLimit(64 * 1024 * 1024); vm.runtime.setMaxStackSize(512 * 1024); let deadline;
  vm.runtime.setInterruptHandler(() => Date.now() > deadline);
  const evaluate = code => { deadline = Date.now() + 15000; const r = vm.evalCode(code), h = r.error ?? r.value, v = vm.dump(h); h.dispose(); if (r.error) throw new Error(JSON.stringify(v)); return v; };
  const literal = v => v === undefined ? 'undefined' : typeof v === 'number' && !Number.isFinite(v) ? String(v) : Array.isArray(v) ? `[${v.map(literal).join(',')}]` : v && typeof v === 'object' ? `{${Object.entries(v).map(([k, x]) => `${JSON.stringify(k)}:${literal(x)}`).join(',')}}` : JSON.stringify(v);
  const guest = s => JSON.parse(evaluate(`JSON.stringify(encode(exercise(install,makeWorld,Block,Vec3,EventEmitter,${literal(s)})))`));
  try {
    evaluate(bundle.outputFiles[0].text); evaluate(`globalThis.exercise=${exercise};globalThis.encode=${encode};`);
    for (const [i, { label, s }] of cases.entries()) assert.deepEqual(guest(s), expected[i], label);
    for (const { label, s, expected } of fixes) assert.deepEqual(guest(s), encode(expected), label);
    for (const { label, s } of bounds) assert.equal(guest(s).error, 'RangeError', label);
    console.log(`PASS: ${cases.length} upstream comparisons, ${fixes.length} explicit fixes, ${bounds.length} bounds; actual QuickJS 64MiB/512KiB, no native/live conformance.`);
  } finally { vm.dispose(); }
}
