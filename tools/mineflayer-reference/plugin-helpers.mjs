// Preauthored runtime-surface contract, through the actual production bot bundle.
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
// Identical program exercises both implementations, with independent assertions below.
function exercise(bot) {
  const events = [], returns = [], errors = [];
  let shared;
  const first = (self, options) => {
    events.push(['first', self === bot, self.hasPlugin(first), options.version]);
    shared = options;
    options.marker = 37;
    returns.push(bot.loadPlugin(first) === undefined);
  };
  const second = (self, options) => events.push(['second', self === bot, options === shared, options.marker]);
  const marker = new Error('initializer failed');
  const throws = () => { events.push(['throws']); throw marker; };
  const untouched = () => events.push(['invalid array invoked']);
  events.push(['initial', bot.hasPlugin(first), bot.hasPlugin(undefined)]);
  returns.push(bot.loadPlugin(first) === undefined, bot.loadPlugin(first) === undefined);
  returns.push(bot.loadPlugins([first, second, second]) === undefined, bot.loadPlugins([]) === undefined);
  try { bot.loadPlugin(throws); } catch (error) { events.push(['caught', error === marker]); }
  returns.push(bot.loadPlugin(throws) === undefined);
  events.push(['throw retained', bot.hasPlugin(throws)]);
  for (const value of [null, 4, {}, 'plugin']) {
    try { bot.loadPlugin(value); errors.push(false); } catch { errors.push(true); }
  }
  for (const value of [null, {}, [untouched, null], [untouched, 4], [untouched, , second]]) {
    try { bot.loadPlugins(value); errors.push(false); } catch { errors.push(!bot.hasPlugin(untouched)); }
  }
  return { events, returns, errors };
}
const upstream = new EventEmitter();
reference('mineflayer/lib/plugin_loader.js')(upstream, { version: '1.21.1' });
upstream.emit('inject_allowed');
const expected = exercise(upstream);
assert.deepEqual(expected.events, [['initial', false, false], ['first', true, true, '1.21.1'],
  ['second', true, true, 37], ['throws'], ['caught', true], ['throw retained', true]]);
assert(expected.returns.every(Boolean));
assert.equal(expected.errors.length, 9);
assert(expected.errors.every(Boolean));
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
  actual = evaluate(`(${exercise.toString()})(fixture.bot)`);
  assert.deepEqual(actual, expected);
} finally { vm.dispose(); }
const sha = value => createHash('sha256').update(value).digest('hex');
const report = { result: 'passed', actual, reference: expected, memoryBytes: 64 * 1024 * 1024,
  stackBytes: 512 * 1024, bundleSha256: sha(bundle), initialSha256: sha(JSON.stringify(initial)),
  sourceSha256: sha(await readFile(new URL(import.meta.url))),
  referenceSha256: sha(await readFile(reference.resolve('mineflayer/lib/plugin_loader.js'))),
  limitation: 'Script-local post-injection plugin functions only. Synthetic initialization, no native requests, third-party packages, lifecycle or world conformance claim. Invalid arguments reject in both; error classes intentionally differ (TypeError versus upstream AssertionError).' };
await writeFile(new URL('../../run/mineflayer-reference/plugin-helpers.json', import.meta.url), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report));
