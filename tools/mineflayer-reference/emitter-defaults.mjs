// Preauthored EventEmitter default-limit contract through the production bot bundle.
// The empty observation below is synthetic initialization, not native evidence.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { encodeItemTransport } from '../../bb-plugin/scripting/item-wire.mjs';
const reference = createRequire(new URL('./package.json', import.meta.url));
const plugin = createRequire(new URL('../../bb-plugin/package.json', import.meta.url));
assert.equal(reference('mineflayer/package.json').version, '4.39.0');
const registry = reference('minecraft-data')('1.21.1');
const registryCodecs = ['minecraft:dimension_type', 'minecraft:worldgen/biome', 'minecraft:chat_type']
  .map(id => structuredClone(registry.loginPacket.dimensionCodec[id]));
const empty = { item: encodeItemTransport({ itemCount: 0 }), itemKey: 'empty', count: 0 };
const initial = {
  session: 'synthetic-plugin-contract', revision: 1, tick: 1, completedActionSequence: 0,
  dimension: 'minecraft:overworld', minY: -64, height: 384, registryCodecs,
  itemRegistries: { references: { 'minecraft:worldgen/biome': registryCodecs[1].entries.map(entry => entry.key) } },
  body: { id: 1, uuid: '00000000-0000-0000-0000-000000000001', type: 'minecraft:cow', name: 'Fixture',
    position: { x: .5, y: 0, z: .5 }, velocity: { x: 0, y: 0, z: 0 }, yaw: 0, pitch: 0,
    mainHand: 'right', alive: true, health: 20, airSupply: 300, width: .9, height: 1.4,
    rawMetadata: { values: encodeItemTransport([]), nonDefaultKeys: [] } },
  entities: [], blocks: { min: [0, 0, 0], size: [1, 1, 1], states: [0], biomes: [0], light: [0], entities: {} },
  columnView: { dimension: 'minecraft:overworld', minY: -64, worldHeight: 384, columns: {} },
  worldState: { dayTime: '0', gameTime: '0', doDaylightCycle: true, isRaining: false, rainState: 0, thunderState: 0 },
  hands: { mode: 'survival', selected: 0, inventory: [], usingItem: false,
    menu: { id: 0, type: 'minecraft:inventory', generation: 1, slots: [], carried: empty } },
};
function exercise(EventEmitter) {
  const saved = EventEmitter.defaultMaxListeners;
  const encode = value => value === Infinity ? 'Infinity' : value;
  try {
    const inherited = new EventEmitter(), local = new EventEmitter();
    local.setMaxListeners(7);
    const accepted = [];
    for (const value of [0, 1, 2.5, Infinity]) {
      EventEmitter.defaultMaxListeners = value;
      accepted.push([encode(EventEmitter.defaultMaxListeners), encode(inherited.getMaxListeners()), local.getMaxListeners()]);
    }
    EventEmitter.defaultMaxListeners = 9;
    const rejected = [];
    for (const [kind, value] of [['negative', -1], ['nan', NaN], ['string', '3'],
      ['null', null], ['undefined', undefined], ['object', {}]]) {
      let error = null;
      try { EventEmitter.defaultMaxListeners = value; } catch (caught) { error = caught.name; }
      rejected.push([kind, error, EventEmitter.defaultMaxListeners, inherited.getMaxListeners(), local.getMaxListeners()]);
    }
    const descriptor = Object.getOwnPropertyDescriptor(EventEmitter, 'defaultMaxListeners');
    return { accepted, rejected, selfAlias: EventEmitter.EventEmitter === EventEmitter,
      accessor: typeof descriptor.get === 'function' && typeof descriptor.set === 'function' };
  } finally { EventEmitter.defaultMaxListeners = saved; }
}
const expected = exercise(EventEmitter);
assert.deepEqual(expected.accepted, [[0, 0, 7], [1, 1, 7], [2.5, 2.5, 7], ['Infinity', 'Infinity', 7]]);
assert.deepEqual(expected.rejected.map(row => row[1]), ['RangeError', 'RangeError', 'TypeError', 'TypeError', 'TypeError', 'TypeError']);
assert(expected.rejected.every(row => row[2] === 9 && row[3] === 9 && row[4] === 7));
assert(expected.selfAlias && expected.accessor);
const bundle = await readFile(new URL('../../bb-plugin/dist/scripting/bot.js', import.meta.url), 'utf8');
const vm = (await plugin('quickjs-emscripten').getQuickJS()).newContext();
vm.runtime.setMemoryLimit(64 * 1024 * 1024); vm.runtime.setMaxStackSize(512 * 1024);
const deadline = Date.now() + 10000;
vm.runtime.setInterruptHandler(() => Date.now() > deadline);
function evaluate(source) {
  const result = vm.evalCode(source), handle = result.error ?? result.value;
  const value = vm.dump(handle); handle.dispose();
  if (result.error) throw new Error(JSON.stringify(value));
  return value;
}
let actual;
try {
  evaluate('globalThis.__mcNow=()=>0;globalThis.__mcRequest=()=>{throw new Error("Unexpected native request")};');
  evaluate(bundle);
  evaluate(`globalThis.fixture=MinecraftBot.createBot(${JSON.stringify(initial)});`);
  const before = evaluate('fixture.bot.constructor.defaultMaxListeners');
  actual = evaluate(`(${exercise.toString()})(fixture.bot.constructor)`);
  // Pinned events3 uses RangeError for nonnumeric values; Node24 uses TypeError.
  const guestExpected = structuredClone(expected);
  for (const row of guestExpected.rejected) row[1] = 'RangeError';
  assert.deepEqual(actual, guestExpected);
  assert.equal(evaluate('fixture.bot.constructor.defaultMaxListeners'), before);
} finally { vm.dispose(); }
const sha = value => createHash('sha256').update(value).digest('hex');
const report = { result: 'matched-with-recorded-difference', actual, reference: expected,
  difference: 'Nonnumeric defaults reject with RangeError in pinned events3 and TypeError in Node24; both preserve the prior value. Negative/NaN RangeError and valid assignments agree.',
  initialAttempt: 'Initial Node-only oracle assumed RangeError for all invalid values and failed before guest evaluation. Preserved initial source; corrected classifier retains both error types rather than claiming equality.',
  sourceSha256: sha(await readFile(new URL(import.meta.url))), bundleSha256: sha(bundle),
  initialSha256: sha(JSON.stringify(initial)), guestEmitterSha256: sha(await readFile(plugin.resolve('events/'))),
  nodeVersion: process.version, nodeEmitterSha256: sha(process.binding('natives').events),
  memoryBytes: 64 * 1024 * 1024, stackBytes: 512 * 1024,
  limitation: 'Local default limits and alias only, through actual production bot constructor. Synthetic initialization; no native events, warnings or removeListener identity claim.' };
await writeFile(new URL('../../run/mineflayer-reference/emitter-defaults.json', import.meta.url), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report));
