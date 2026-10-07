// Deterministic library differential scenarios, authored before the guest Item.
// No server, game, or EULA. Reuse installed packages read-only with:
// MINEFLAYER_REFERENCE_ROOT=/path/to/tools/mineflayer-reference \
// MINEFLAYER_PLUGIN_ROOT=/path/to/bb-plugin node tools/mineflayer-reference/items.mjs
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? fileURLToPath(new URL('.', import.meta.url)));
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? fileURLToPath(new URL('../../bb-plugin', import.meta.url)));
const reference = createRequire(resolve(referenceRoot, 'package.json'));
const plugin = createRequire(resolve(pluginRoot, 'package.json'));
for (const [name, version] of Object.entries({ mineflayer: '4.39.0', 'prismarine-item': '1.18.0', 'prismarine-nbt': '2.8.0', 'minecraft-data': '3.117.0' })) {
  assert.equal(reference(resolve(referenceRoot, 'node_modules', name, 'package.json')).version, version, `Pinned ${name}`);
}
const registry = reference('minecraft-data')('1.21.1');
const data = JSON.parse(JSON.stringify({ itemsArray: registry.itemsArray, enchantmentsByName: registry.enchantmentsByName }));
const ReferenceItem = reference('prismarine-item')('1.21.1');

function exercise(Item, scenario) {
  'use strict';
  const capture = fn => {
    try { return { value: fn() }; }
    catch (error) { return { error: error.name, message: error instanceof TypeError || error instanceof SyntaxError ? undefined : error.message }; }
  };
  const make = spec => {
    if (spec == null) return spec;
    const item = Object.hasOwn(spec, 'wire') ? Item.fromNotch(spec.wire, spec.stackId) : new Item(...spec.args);
    if (spec.withoutMap) delete item.componentMap;
    for (const [key, value] of Object.entries(spec.assign ?? {})) item[key] = value;
    return item;
  };
  const describe = item => {
    if (item == null) return item;
    const getters = Object.fromEntries(['customName', 'customLore', 'repairCost', 'customModel', 'enchants', 'blocksCanPlaceOn', 'blocksCanDestroy', 'durabilityUsed', 'spawnEggMobName'].map(name => [name, capture(() => item[name])]));
    return { own: { ...item }, getters, instance: item instanceof Item, json: capture(() => JSON.stringify(item)), notch: capture(() => Item.toNotch(item)) };
  };
  if (scenario.kind === 'make') return capture(() => {
    const item = make(scenario.item);
    return { item: describe(item), roundtrip: capture(() => describe(Item.fromNotch(Item.toNotch(item)))) };
  });
  if (scenario.kind === 'set') return capture(() => {
    const item = make(scenario.item);
    const steps = scenario.steps.map(([key, value]) => ({ outcome: capture(() => { item[key] = value; }), item: describe(item) }));
    return { steps, roundtrip: capture(() => describe(Item.fromNotch(Item.toNotch(item)))) };
  });
  if (scenario.kind === 'equal') return capture(() => {
    const left = make(scenario.left), right = make(scenario.right);
    return [Item.equal(left, right), Item.equal(left, right, false), Item.equal(left, right, true, false), Item.equal(left, right, false, false)];
  });
  if (scenario.kind === 'notch') return capture(() => Item.toNotch(scenario.item, scenario.authoritative));
  if (scenario.kind === 'anvil') return capture(() => {
    const left = make(scenario.left), right = make(scenario.right);
    const result = capture(() => {
      const { item, ...rest } = Item.anvil(left, right, scenario.creative, scenario.rename);
      return { ...rest, item: describe(item) };
    });
    return { result, left: describe(left), right: describe(right) };
  });
  if (scenario.kind === 'aliases') return capture(() => {
    const wire = scenario.wire;
    const item = Item.fromNotch(wire);
    const output = Item.toNotch(item);
    const aliases = {
      components: item.components === wire.components,
      removed: item.removedComponents === wire.removeComponents,
      map: item.componentMap.get('damage') === wire.components[0],
      serialized: output.components === item.components,
      serializedRemoved: output.removeComponents === item.removedComponents,
    };
    item.componentMap.get('damage').data++;
    const before = describe(item);
    item.durabilityUsed = 200;
    return { aliases, before, after: describe(item), wire };
  });
  if (scenario.kind === 'statics') {
    Item.currentStackId = 0;
    const ids = [Item.nextStackId(), Item.nextStackId()];
    Item.currentStackId = 40;
    ids.push(Item.nextStackId());
    return { ids, current: Item.currentStackId, prototype: Object.getOwnPropertyNames(Item.prototype), statics: Object.getOwnPropertyNames(Item) };
  }
  throw new Error(`Unknown scenario ${scenario.kind}`);
}

function encode(value) {
  if (value === undefined) return ['undefined'];
  if (typeof value === 'function') return ['function'];
  if (typeof value === 'number' && !Number.isFinite(value)) return ['number', String(value)];
  if (value instanceof Map) return ['map', [...value].map(([key, value]) => [encode(key), encode(value)])];
  if (Array.isArray(value)) return ['array', value.map(encode)];
  if (value && typeof value === 'object') return ['object', Object.keys(value).sort().map(key => [key, encode(value[key])])];
  return value;
}
// Emit JavaScript literals so undefined and NaN in input are not lost in JSON.
function literal(value) {
  if (value === undefined) return 'undefined';
  if (typeof value === 'number' && !Number.isFinite(value)) return String(value);
  if (Array.isArray(value)) return `[${value.map(literal).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.entries(value).map(([key, value]) => `${JSON.stringify(key)}:${literal(value)}`).join(',')}}`;
  return JSON.stringify(value);
}
const cases = [];
const add = (label, scenario) => cases.push({ label, scenario });
const id = name => registry.itemsByName[name].id;
const ctor = (name, count = 1, nbt = null) => ({ args: [id(name), count, 0, nbt] });
const slot = (name, components = [], removeComponents = [], count = 1) => ({ wire: { itemId: id(name), itemCount: count, components, removeComponents } });
const component = (type, data) => ({ type, data });
const tag = (type, value) => ({ type, value });
const comp = value => ({ type: 'compound', name: '', value });
const list = (type, value) => tag('list', { type, value });
const enchantNbt = (name, enchants, damage = 0, repairCost = 0) => comp({
  Damage: tag('int', damage), RepairCost: tag('int', repairCost),
  [name === 'enchanted_book' ? 'StoredEnchantments' : 'Enchantments']: list('compound', enchants.map(([name, lvl]) => ({ id: tag('string', `minecraft:${name}`), lvl: tag('short', lvl) }))),
});

// Sweep all registry items through both public creation paths and roundtrips.
for (const item of registry.itemsArray) {
  add(`constructor:${item.name}`, { kind: 'make', item: ctor(item.name, item.stackSize) });
  add(`network:${item.name}`, { kind: 'make', item: slot(item.name, [], [], item.stackSize) });
}
for (const args of [[], [undefined, 1], [null, 1], [-1, 1], [999999, 1], ['838', 1], [NaN, 1], [id('stone')], [id('stone'), 0], [id('stone'), -3], [id('stone'), 1000], [id('diamond_sword'), 1, null], [id('diamond_sword'), 1, 5], [id('diamond_sword'), 1, 0, null, 77, true], [id('diamond_sword'), 1, comp({ Damage: tag('int', 42) }), 77, true], [id('stone'), 1, comp({})], [id('stone'), 1, []]]) add(`constructor edge:${literal(args)}`, { kind: 'make', item: { args } });
for (const wire of [null, undefined, {}, { present: false }, { itemCount: 0 }, { itemCount: 0, itemId: id('stone') }, { present: false, itemCount: 1, itemId: id('stone') }, { itemCount: 1, itemId: -1 }, { itemCount: 1, itemId: id('diamond_sword') }, { itemCount: 1, itemId: id('stone'), components: null, removeComponents: null }, { itemCount: 1, itemId: id('stone'), components: {} }, { itemCount: 1, itemId: id('stone'), components: [null] }]) {
  // Direct construction with wire even when it is null/undefined.
  add(`wire edge:${literal(wire)}`, { kind: 'make', item: { wire, args: [] } });
}
for (const item of [null, undefined, {}, { count: 0, type: id('stone'), components: [], removedComponents: [] }, { nbt: {} }, { nbt: { value: null } }]) add(`toNotch:${literal(item)}`, { kind: 'notch', item });
const swordComponents = [component('damage', 321), component('enchantments', { enchantments: [{ id: registry.enchantmentsByName.sharpness.id, level: 3 }], showTooltip: true }), component('repair_cost', 7), component('custom_name', comp({ text: tag('string', 'Named sword') })), component('lore', [tag('string', 'First line'), comp({ translate: tag('string', 'item.minecraft.diamond_sword') })])];
const complexSlots = [
  ['diamond_sword', swordComponents, [{ type: 'attribute_modifiers' }]],
  ['enchanted_book', [component('stored_enchantments', { enchantments: [{ id: registry.enchantmentsByName.efficiency.id, level: 5 }], showInTooltip: false })]],
  ['potion', [component('potion_contents', { potionId: 1, customColor: 0x12ab34, customEffects: [], customName: 'Potion' })]],
  ['splash_potion', [component('potion_contents', { potionId: undefined, customEffects: [{ id: 1, details: { amplifier: 2, duration: 600, ambient: false, showParticles: true, showIcon: true, hiddenEffect: undefined } }], customColor: undefined, customName: undefined })]],
  ['shulker_box', [component('container', { contents: [slot('diamond', [], [], 3).wire, { itemCount: 0 }, slot('diamond_sword', swordComponents).wire] })]],
  ['bundle', [component('bundle_contents', { contents: [slot('apple', [], [], 5).wire] })]],
  ['crossbow', [component('charged_projectiles', { projectiles: [slot('arrow').wire] })]],
  ['apple', [component('food', { nutrition: 4, saturationModifier: 0.3, canAlwaysEat: false, secondsToEat: 1.6, usingConvertsTo: { itemCount: 0 }, effects: [] })]],
  ['suspicious_stew', [component('suspicious_stew_effects', { effects: [{ effect: 1, duration: 100 }] })]],
  ['written_book', [component('written_book_content', { rawTitle: 'Title', filteredTitle: undefined, author: 'Author', generation: 0, pages: [{ content: tag('string', 'Page'), filteredContent: undefined }], resolved: true })]],
  ['stone', [component('max_stack_size', 12), component('item_name', tag('string', 'Item name')), component('custom_model_data', 123), component('can_break', { predicates: [], showTooltip: true }), component('can_place_on', { predicates: [], showTooltip: true }), component('custom_data', comp({ long: tag('long', [123, 456]), bytes: tag('byteArray', [1, -1]), list: list('compound', [{ nested: tag('int', 3) }]) }))]],
  ['diamond_sword', [component('damage', 1), component('damage', 99), component('max_damage', 1000)]],
];
for (const [name, components, removed] of complexSlots) add(`components:${name}:${cases.length}`, { kind: 'make', item: slot(name, components, removed) });
for (const name of ['stone', 'diamond_sword', 'enchanted_book', 'potion', 'zombie_spawn_egg']) {
  const nbt = enchantNbt(name, [['unbreaking', 3], ['mending', 1]], 17, 4);
  nbt.value.display = comp({ Name: tag('string', '{"text":"Legacy name"}'), Lore: list('string', ['{"text":"Lore"}']) });
  nbt.value.CustomModelData = tag('int', 11);
  nbt.value.CanPlaceOn = list('string', ['minecraft:stone', 'custom:block']);
  nbt.value.CanDestroy = list('string', ['minecraft:dirt']);
  add(`NBT:${name}`, { kind: 'make', item: ctor(name, 1, nbt) });
  for (const withoutMap of [false, true]) add(`setters:${name}:withoutMap=${withoutMap}`, { kind: 'set', item: { ...ctor(name, 1, nbt), withoutMap }, steps: [
    ['customName', '{"text":"New name"}'], ['customLore', ['line1', 'line2']], ['repairCost', 12], ['durabilityUsed', 43],
    ['enchants', [{ name: 'efficiency', lvl: 4 }]], ['enchants', []], ['blocksCanPlaceOn', ['dirt', 'other:block']], ['blocksCanPlaceOn', []], ['blocksCanDestroy', ['stone']], ['blocksCanDestroy', []],
    ['customName', null], ['customLore', null], ['repairCost', 0], ['durabilityUsed', null],
  ] });
}
for (const [key, value] of [['enchants', [{ name: 'minecraft:sharpness', lvl: 1 }]], ['enchants', [{ name: 'not_real', lvl: 1 }]], ['enchants', null], ['customLore', 'single string'], ['blocksCanPlaceOn', [null]], ['blocksCanDestroy', null], ['customModel', 10], ['durabilityUsed', -10]]) add(`invalid setter:${key}:${literal(value)}`, { kind: 'set', item: ctor('diamond_sword'), steps: [[key, value]] });
add('component setter precedence', { kind: 'set', item: slot('diamond_sword', swordComponents), steps: [['durabilityUsed', 10], ['enchants', [{ name: 'mending', lvl: 1 }]], ['customName', 'Rename'], ['customLore', ['Changed']], ['repairCost', 99]] });
add('component customModel spelling', { kind: 'make', item: slot('stone', [component('custom_model', 123)]) });
add('component references and precedence', { kind: 'aliases', wire: slot('diamond_sword', [component('damage', 42)]).wire });
for (const [left, right] of [
  [null, null], [undefined, null], [null, ctor('stone')], [ctor('stone'), ctor('stone')], [ctor('stone', 1), ctor('stone', 2)], [ctor('stone'), ctor('dirt')],
  [ctor('stone', 1, comp({ a: tag('int', 1), b: tag('int', 2) })), ctor('stone', 1, comp({ b: tag('int', 2), a: tag('int', 1) }))],
  [slot('diamond_sword', [component('damage', 1)]), slot('diamond_sword', [component('damage', 500)])],
  [slot('stone', [], [{ type: 'lore' }]), slot('stone')], [ctor('diamond_sword', 1, enchantNbt('diamond_sword', [], 4)), ctor('diamond_sword')],
]) add(`equal:${cases.length}`, { kind: 'equal', left, right });
const sword = (enchants, damage = 0, repair = 0) => ctor('diamond_sword', 1, enchantNbt('diamond_sword', enchants, damage, repair));
const book = enchants => ctor('enchanted_book', 1, enchantNbt('enchanted_book', enchants));
for (const creative of [false, true]) {
  for (const [left, right, rename] of [
    [sword([], 300), ctor('diamond', 1)], [sword([], 1000), ctor('diamond', 8)], [sword([], 300), sword([], 200)],
    [sword([['sharpness', 2]]), book([['sharpness', 2]])], [sword([['sharpness', 2]]), book([['smite', 3]])],
    [sword([]), book([['efficiency', 5]])], [book([['mending', 1]]), book([['unbreaking', 3]])],
    [sword([], 0, 31), null, 'Expensive rename'], [sword([], 0, 0x7fffffff), null, 'Impossible'],
    [sword([]), null, 'Renamed'], [ctor('apple'), null, 'Snack'], [sword([]), ctor('dirt')],
    [slot('diamond_sword', swordComponents), book([['mending', 1]])], [sword([]), slot('enchanted_book', [component('enchantments', { enchantments: [], showTooltip: true })])],
    [null, null], [sword([]), undefined], [sword([]), null],
  ]) add(`anvil:${cases.length}`, { kind: 'anvil', left, right, rename, creative });
}
add('statics and public surface', { kind: 'statics' });

if (process.argv.includes('--reference-only')) {
  for (const { scenario } of cases) encode(exercise(ReferenceItem, structuredClone(scenario)));
  console.log(`Reference scenarios ready: ${cases.length}; no guest implementation evaluated.`);
} else {
  const { build } = plugin('esbuild');
  const { getQuickJS } = plugin('quickjs-emscripten');
  const bundle = await build({
    stdin: { contents: `import { createItemClass } from './bb-plugin/scripting/items.mjs'; globalThis.Item = createItemClass(${JSON.stringify(data)});`, resolveDir: fileURLToPath(new URL('../..', import.meta.url)) },
    bundle: true, write: false, platform: 'browser', format: 'iife', target: 'es2022',
  });
  const vm = (await getQuickJS()).newContext();
  vm.runtime.setMemoryLimit(256 * 1024 * 1024);
  const evaluate = source => {
    const result = vm.evalCode(source);
    if (result.error) {
      const error = vm.dump(result.error);
      result.error.dispose();
      throw new Error(JSON.stringify(error));
    }
    const value = vm.dump(result.value);
    result.value.dispose();
    return value;
  };
  try {
    evaluate(bundle.outputFiles[0].text);
    evaluate(`globalThis.exercise = ${exercise}; globalThis.encode = ${encode};`);
    for (let start = 0; start < cases.length; start += 100) {
      const batch = cases.slice(start, start + 100);
      const actual = JSON.parse(evaluate(`JSON.stringify(${literal(batch.map(c => c.scenario))}.map(s => encode(exercise(Item, s))))`));
      for (const [index, { label, scenario }] of batch.entries()) {
        assert.deepEqual(actual[index], encode(exercise(ReferenceItem, structuredClone(scenario))), label);
      }
    }
    console.log(`PASS: ${cases.length} differential Item scenarios in bundled QuickJS (prismarine-item 1.18.0, Minecraft 1.21.1). Library behavior only; no native inventory or world behavior tested.`);
  } finally {
    vm.dispose();
  }
}
