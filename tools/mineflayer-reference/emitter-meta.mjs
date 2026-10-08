// Preauthored EventEmitter metadata contract, through the actual production bot bundle.
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
// Identical procedure in Node and the production bundle's exposed constructor.
function exercise(EventEmitter) {
  const previousDefault = EventEmitter.defaultMaxListeners;
  try {
    EventEmitter.defaultMaxListeners = 14;
    const first = new EventEmitter(), second = new EventEmitter();
    const defaults = [first.getMaxListeners(), second.getMaxListeners()];
    first.setMaxListeners(3);
    EventEmitter.defaultMaxListeners = 18;
    defaults.push(first.getMaxListeners(), second.getMaxListeners());
    const log = [];
    let inserted = false;
    function earlier() { log.push(['call','earlier']); }
    function original() { log.push(['call','original']); }
    function identity(listener) {
      return listener === original ? 'original' : listener === earlier ? 'earlier'
        : listener.listener === original ? 'original-wrapper' : 'unknown';
    }
    first.on('newListener', function (name, listener) {
      if (name !== 'work') return;
      log.push(['new',identity(listener),this === first,first.listenerCount(name)]);
      if (!inserted) { inserted = true; first.on('work',earlier); }
    });
    first.on('removeListener', function (name, listener) {
      if (name === 'work') log.push(['remove',identity(listener),this === first,first.listenerCount(name)]);
    });
    first.once('work',original);
    const counts = [EventEmitter.listenerCount(first,'work'),EventEmitter.listenerCount(second,'work')];
    first.emit('work');
    first.removeAllListeners('work');
    counts.push(EventEmitter.listenerCount(first,'work'));
    return {defaults,counts,log,selfAlias:EventEmitter.EventEmitter === EventEmitter,
      independent:second.eventNames().length === 0};
  } finally { EventEmitter.defaultMaxListeners = previousDefault; }
}
const expected = exercise(EventEmitter);
assert.deepEqual(expected.defaults,[14,14,3,18]);
assert.deepEqual(expected.counts,[2,0,0]);
assert.deepEqual(expected.log,[['new','original',true,0],['new','earlier',true,0],
  ['call','earlier'],['remove','original-wrapper',true,1],['call','original'],['remove','earlier',true,0]]);
assert(expected.selfAlias && expected.independent);
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
  actual = evaluate(`(${exercise.toString()})(fixture.bot.constructor)`);
  // Preserve the observed Node/browser once-removal identity difference.
  const guestExpected = structuredClone(expected);
  guestExpected.log[3][1] = 'original';
  assert.deepEqual(actual, guestExpected);
} finally { vm.dispose(); }
const sha = value => createHash('sha256').update(value).digest('hex');
const report = { result: 'matched-with-recorded-difference', actual, reference: expected, memoryBytes: 64 * 1024 * 1024,
  stackBytes: 512 * 1024, bundleSha256: sha(bundle), initialSha256: sha(JSON.stringify(initial)),
  sourceSha256: sha(await readFile(new URL(import.meta.url))),
  guestEmitterSha256: sha(await readFile(plugin.resolve('events/'))),
  nodeVersion: process.version, nodeEmitterSha256: sha(process.binding('natives').events),
  difference: 'Node exposes the once wrapper during self-removal from a multi-listener event; bundled events exposes the original callback. No production change or exact metadata-event parity claim.',
  initialAttempt: 'The preauthored original-callback oracle failed in Node before QuickJS ran. The classifier was corrected to distinguish wrapper identity; both raw traces are retained.',
  limitation: 'Local EventEmitter metadata/defaults/static aliases in production QuickJS, compared with Node. Synthetic initialization only; no native event sources or Node-only options/warnings claimed.' };
await writeFile(new URL('../../run/mineflayer-reference/emitter-meta.json', import.meta.url), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report));
