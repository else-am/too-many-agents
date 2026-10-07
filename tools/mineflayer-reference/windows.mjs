// Authored before windows.mjs. Independent pinned-library and documented/source
// scenarios, run in an actual browser bundle inside QuickJS. No game or server.
// MINEFLAYER_REFERENCE_ROOT=/path/to/tools/mineflayer-reference \
// MINEFLAYER_PLUGIN_ROOT=/path/to/bb-plugin node tools/mineflayer-reference/windows.mjs
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const root = fileURLToPath(new URL('../..', import.meta.url));
const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? resolve(root, 'tools/mineflayer-reference'));
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? resolve(root, 'bb-plugin'));
const reference = createRequire(resolve(referenceRoot, 'package.json'));
const plugin = createRequire(resolve(pluginRoot, 'package.json'));
assert.equal(reference('prismarine-windows/package.json').version, '2.10.0');
assert.equal(reference('prismarine-item/package.json').version, '1.18.0');
assert.equal(reference('minecraft-data/package.json').version, '3.117.0');
assert.equal(plugin('events/package.json').version, '3.3.0');
const registry = reference('minecraft-data')('1.21.1');
const factory = reference('prismarine-windows')('1.21.1');
const stone = registry.itemsByName.stone.id, dirt = registry.itemsByName.dirt.id;
// Each upstream loader creates a distinct Item constructor. Obtain the one
// owned by this factory so instanceof checks are meaningful on both sides.
const probe = factory.createWindow(0, 'minecraft:inventory', 'probe');
probe.middleClick({ item: { type: stone, stackSize: 64, metadata: 0, nbt: null } }, 1);
const Item = probe.selectedItem.constructor;
const data = { itemsArray: registry.itemsArray, enchantmentsByName: registry.enchantmentsByName };

function exercise(factory, Item, s) {
  const { Window, createWindow, windows } = factory;
  const encode = value => {
    if (value === undefined) return { undefined: true };
    if (typeof value === 'bigint') return { bigint: String(value) };
    if (typeof value === 'number' && !Number.isFinite(value)) return { number: String(value) };
    if (value instanceof Map) return { map: [...value].map(encode) };
    if (Array.isArray(value)) return value.map(encode);
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, encode(v)]));
    return value;
  };
  const capture = fn => {
    try { return { value: encode(fn()) }; }
    catch (error) {
      return { error: error.name, code: error.code, message: error.name === 'TypeError' || error.name === 'RangeError' ? undefined : error.message };
    }
  };
  const make = spec => {
    if (spec == null) return null;
    const item = new Item(spec.type, spec.count, spec.metadata ?? 0, spec.nbt);
    if (spec.components) {
      item.components = spec.components;
      item.removedComponents = spec.removed ?? [];
      item.componentMap = new Map(spec.components.map(c => [c.type, c]));
    }
    return item;
  };
  if (s.kind === 'surface') {
    const w = createWindow(0, 'minecraft:inventory', 'Inventory');
    return {
      methods: Object.getOwnPropertyNames(Window.prototype),
      aliases: Object.keys(w).filter(k => typeof w[k] === 'function').map(k => [k, w[k] === w.acceptClick]),
      exports: Object.keys(factory),
    };
  }
  const w = Object.hasOwn(s, 'constructor') ? new Window(...s.constructor) : createWindow(4, s.type ?? 'minecraft:generic_9x3', s.title ?? { text: 'Chest' }, s.slotCount);
  if (!w) return null;
  const snapshot = () => encode({
    id: w.id, type: w.type, title: w.title, slots: w.slots, inventoryStart: w.inventoryStart,
    inventoryEnd: w.inventoryEnd, hotbarStart: w.hotbarStart, craftingResultSlot: w.craftingResultSlot,
    requiresConfirmation: w.requiresConfirmation, selectedItem: w.selectedItem, instance: w instanceof Window,
  });
  if (s.kind === 'layout') return { window: snapshot(), metadata: encode(windows[w.type]) };
  for (const [slot, item] of s.slots ?? []) w.updateSlot(slot, make(item));
  w.selectedItem = make(s.selected);
  const events = [];
  w.on('updateSlot', (slot, oldItem, newItem) => events.push(encode(['any', slot, oldItem, newItem, w.slots[slot] === newItem, newItem == null || newItem instanceof Item])));
  for (let slot = 0; slot < w.slots.length; slot++) {
    w.on(`updateSlot:${slot}`, (oldItem, newItem) => events.push(encode(['slot', slot, oldItem, newItem, w.slots[slot] === newItem])));
  }
  const arg = a => a && typeof a === 'object' && 'slotItem' in a ? w.slots[a.slotItem] : a;
  const steps = [];
  for (const op of s.ops ?? []) {
    const outcome = capture(() => {
      if (op.click) {
        const click = { ...op.click, item: w.slots[op.click.slot] ?? null };
        if (op.omitItem) delete click.item;
        return w[op.method ?? 'acceptClick'](click, op.gamemode);
      }
      if (op.method === 'updateSlot') return w.updateSlot(op.args[0], make(op.args[1]));
      if (op.method === 'fillSlotsWithItem') return w.fillSlotsWithItem(op.args[0].map(i => w.slots[i]), w.slots[op.args[1]], op.args[2]);
      if (op.method === 'listenerProbe') {
        let once = 0;
        const cb = () => once++;
        w.once('probe', cb);
        const before = w.listenerCount('probe');
        const emitted = [w.emit('probe'), w.emit('probe')];
        w.on('probe', cb); w.removeListener('probe', cb);
        return { once, before, emitted, after: w.listenerCount('probe') };
      }
      return w[op.method](...(op.args ?? []).map(arg));
    });
    steps.push({ outcome, state: snapshot(), events: events.splice(0) });
  }
  if (s.project === 'layout') return [w.slots.length, w.inventoryStart, w.inventoryEnd, w.hotbarStart, w.craftingResultSlot];
  if (s.project === 'counts') return { slots: w.slots.map(i => i ? [i.type, i.count] : null), cursor: w.selectedItem ? [w.selectedItem.type, w.selectedItem.count] : null, returns: steps.map(step => step.outcome) };
  if (s.project === 'components') return { slots: w.slots.map(i => i ? [i.count, i.components, i.removedComponents, [...i.componentMap]] : null), cursor: w.selectedItem ? [w.selectedItem.count, w.selectedItem.components, w.selectedItem.removedComponents, [...w.selectedItem.componentMap]] : null };
  if (s.project === 'values') return steps.map(step => step.outcome);
  return { steps, final: snapshot() };
}

const cases = [];
const add = (label, s) => cases.push({ label, s });
const item = (count, type = stone, extra = {}) => ({ type, count, ...extra });
const click = (slot, mode = 0, mouseButton = 0, extra = {}) => ({ click: { slot, mode, mouseButton }, ...extra });
for (const [key, info] of Object.entries(factory.windows)) {
  if (['minecraft:crafter_3x3', 'minecraft:smithing', 'minecraft:lectern'].includes(key)) continue;
  for (const type of [key, info.type]) add(`layout ${type}`, { kind: 'layout', type, slotCount: 123 });
}
for (const type of ['custom:box', 'EntityHorse', 'minecraft:container', 900]) {
  add(`missing ${type}`, { kind: 'layout', type });
  add(`fallback ${type}`, { kind: 'layout', type, slotCount: 17 });
}
add('base constructor defaults', { kind: 'layout', constructor: [5, 'custom:base', 'Base', 63] });
add('base constructor custom range', { kind: 'layout', constructor: [6, 77, 'Base', 14, { start: 2, end: 13 }, 1, false] });
add('surface', { kind: 'surface' });
const nbt = { type: 'compound', name: '', value: { label: { type: 'string', value: 'a' } } };
for (const type of ['minecraft:inventory', 'minecraft:generic_9x3', 'minecraft:generic_9x6', 'minecraft:furnace', 'minecraft:hopper']) {
  const w = factory.createWindow(0, type, 'x');
  const a = w.inventoryStart, h = w.hotbarStart, end = w.inventoryEnd;
  const slots = [[0, item(5)], [a, item(64)], [a + 1, item(13, stone, { nbt })], [h, item(7, dirt)], [end - 1, item(3)]];
  const ops = [
    ['findItemRange', [0, end, stone, null, false]], ['findItemRange', [0, end, stone, null, true, null, true]],
    ['findItemsRange', [0, end, stone, null, false]], ['findItemsRange', [a, end, stone, 0, true, nbt]],
    ['findItemRange', [a, end, stone, 17]], ['findItemRangeName', [a, end, 'stone', 0, true]],
    ['findInventoryItem', [stone]], ['findInventoryItem', ['stone', null, true]], ['findInventoryItem', ['missing']],
    ['findInventoryItem', []], ['findContainerItem', [stone]], ['findContainerItem', ['stone']],
    ['firstEmptySlotRange', [a, end]], ['firstEmptySlotRange', [a, a]],
    ['lastEmptySlotRange', [a, end - 1]], // end-1 is occupied: unaffected by upstream inclusive bug.
    ['firstEmptyHotbarSlot'], ['firstEmptyContainerSlot'], ['firstEmptyInventorySlot'], ['firstEmptyInventorySlot', [false]],
    ['sumRange', [0, end]], ['countRange', [0, end, stone]], ['countRange', [0, end, stone, 3]],
    ['itemsRange', [0, end]], ['count', [String(stone)]], ['count', ['stone']], ['containerCount', [stone]],
    ['items'], ['containerItems'], ['emptySlotCount'], ['transactionRequiresConfirmation'], ['listenerProbe'],
    ['findInventoryItem', [{}]], ['findContainerItem', [null]],
    ['findItemRange', [0, end, null]], ['findItemRangeName', [0, end, null]],
  ].map(([method, args]) => ({ method, args }));
  add(`queries/events ${type}`, { type, slots, ops });
}
add('update event identity order and null updates', { slots: [[1, item(4)]], ops: [{ method: 'updateSlot', args: [1, item(8, dirt)] }, { method: 'updateSlot', args: [1, null] }, { method: 'updateSlot', args: [1, null] }] });
for (const count of [1, 2, 3, 32, 63, 64]) {
  for (const button of [0, 1]) {
    add(`mouse sequence ${count}/${button}`, { slots: [[2, item(count)]], ops: [click(2, 0, button), click(3, 0, 1), click(4), click(4), click(-999, 0, 0)] });
    for (const target of [1, 63, 64]) {
      add(`merge ${count}/${target}/${button}`, { slots: [[2, item(target)]], selected: item(count), ops: [click(2, 0, button)] });
      add(`swap ${count}/${target}/${button}`, { slots: [[2, item(target, dirt)]], selected: item(count), ops: [click(2, 0, button)] });
    }
    add(`drop ${count}/${button}`, { slots: [[2, item(count)]], ops: [click(2, 4, button), click(3, 4, button)] });
    add(`outside drop ${count}/${button}`, { selected: item(count), ops: [click(-999, 0, button)] });
  }
}
for (const key of ['minecraft:crafting', 'minecraft:furnace', 'minecraft:anvil']) {
  const slot = factory.windows[key].craft;
  add(`craft result ${key}`, { type: key, slots: [[slot, item(4)]], selected: item(62), ops: [click(slot), click(slot, 0, 1)] });
}
for (const mode of [1, 2, 3, 4]) {
  for (const gamemode of [0, 1]) {
    add(`mode ${mode}/${gamemode}`, { slots: [[0, item(19)], [54, item(61)]], ops: [click(0, mode, mode === 3 ? 2 : 0, { gamemode })] });
  }
}
for (const slot of [0, 3, 54, 62]) for (const target of [null, item(7, dirt)]) {
  add(`number swap ${slot}/${!!target}`, { slots: [[0, item(5)], [54, target]], ops: [click(slot, 2, 0)] });
}
for (const alias of ['acceptOutsideWindowClick', 'acceptInventoryClick', 'acceptNonInventorySwapAreaClick', 'acceptSwapAreaLeftClick', 'acceptSwapAreaRightClick', 'acceptCraftingClick']) {
  add(alias, { slots: [[2, item(3)]], ops: [click(2, 0, 0, { method: alias })] });
}
for (const [mode, mouseButton, slot] of [[7, 0, 0], [-1, 0, 0], [0, 2, 0], [0, 0, -1], [0, 0, 100], [3, 0, 0], [5, 1, 0], [5, 5, 0], [5, 9, 0], [6, 0, 0], [6, 1, 0]]) {
  add(`invalid/unsupported ${mode}/${mouseButton}/${slot}`, { ops: [click(slot, mode, mouseButton)] });
}
for (const reverse of [false, true]) {
  add(`fill and dump ${reverse}`, { slots: [[0, item(8)], [27, item(63)], [28, item(61)]], ops: [{ method: 'fillAndDump', args: [{ slotItem: 0 }, 27, 63, reverse] }] });
  add(`fill slots ${reverse}`, { slots: [[0, item(8)], [27, item(63)], [28, item(61)]], ops: [{ method: 'fillSlotsWithItem', args: [[27, 28], 0, reverse] }] });
}
add('direct helpers', { slots: [[0, item(11)], [27, item(63)]], ops: [
  { method: 'fillSlotWithItem', args: [{ slotItem: 27 }, { slotItem: 0 }] },
  { method: 'splitSlot', args: [{ slotItem: 0 }] }, { method: 'fillSlotWithSelectedItem', args: [{ slotItem: 0 }, false] },
  { method: 'swapSelectedItem', args: [5, null] }, { method: 'splitSlot', args: [null] },
] });
for (const count of [undefined, 1, 64, 1000]) add(`clear inventory ${count}`, { type: 'minecraft:inventory', slots: [[9, item(12)], [36, item(64)], [40, item(4, dirt)]], ops: [{ method: 'clear', args: count === undefined ? [] : [stone, count] }] });

// Deliberate departures from upstream, specified before adaptation. Layouts are
// from generated PC1.21.1 CrafterMenu.addSlots, SmithingMenu input definitions,
// LecternMenu constructor (one slot, no player inventory). These are source
// checks, NOT bytes or observations produced by a running Java menu.
const fixes = [];
const fix = (label, s, expected) => fixes.push({ label, s, expected });
fix('crafter preview is slot45 after player inventory', { type: 'minecraft:crafter_3x3', project: 'layout' }, [46, 9, 45, 36, -1]);
fix('smithing template and result3', { type: 'minecraft:smithing', project: 'layout' }, [40, 4, 40, 31, 3]);
fix('lectern has no player inventory', { type: 'minecraft:lectern', project: 'layout' }, [1, 1, 1, 1, -1]);
fix('reverse ranges exclude end', { ops: [{ method: 'lastEmptySlotRange', args: [0, 2] }], project: 'values' }, [{ value: 1 }]);
fix('empty cursor right drop is noop', { ops: [click(-999, 0, 1)], project: 'values' }, [{ value: [] }]);
const sparse = (length, entries) => Object.assign(Array(length).fill(null), entries);
fix('shift hotbar cannot self consume', { type: 'minecraft:inventory', slots: [[36, item(5)]], ops: [click(36, 1)], project: 'counts' }, { slots: sparse(46, { 9: [stone, 5] }), cursor: null, returns: [{ value: { undefined: true } }] });
fix('last main slot shifts to hotbar', { type: 'minecraft:inventory', slots: [[35, item(5)]], ops: [click(35, 1)], project: 'counts' }, { slots: sparse(46, { 36: [stone, 5] }), cursor: null, returns: [{ value: { undefined: true } }] });
fix('last container slot can receive shift', { type: 'minecraft:generic_9x1', slots: [...Array.from({ length: 8 }, (_, i) => [i, item(64, dirt)]), [9, item(5)]], ops: [click(9, 1)], project: 'counts' }, { slots: sparse(45, { ...Object.fromEntries(Array.from({ length: 8 }, (_, i) => [i, [dirt, 64]])), 8: [stone, 5] }), cursor: null, returns: [{ value: { undefined: true } }] });
fix('reverse transfer cannot write offhand', { type: 'minecraft:inventory', slots: [[0, item(5)]], ops: [click(0, 1)], project: 'counts' }, { slots: sparse(46, { 44: [stone, 5] }), cursor: null, returns: [{ value: { undefined: true } }] });
fix('clear covers container and inventory', { slots: [[0, item(2)], [27, item(3)]], ops: [{ method: 'clear' }], project: 'counts' }, { slots: sparse(63, {}), cursor: null, returns: [{ value: 5 }] });
fix('clear zero leaves items', { slots: [[54, item(2)]], ops: [{ method: 'clear', args: [stone, 0] }], project: 'counts' }, { slots: sparse(63, { 54: [stone, 2] }), cursor: null, returns: [{ value: 0 }] });
const components = [{ type: 'custom_name', data: { type: 'string', value: 'Named stone' } }, { type: 'enchantments', data: { enchantments: [{ id: 9, level: 3 }], showTooltip: true } }];
const removed = [{ type: 'lore' }];
const rich = count => item(count, stone, { components, removed });
const patch = count => [count, components, removed, components.map(c => [c.type, c])];
fix('split preserves components', { slots: [[0, rich(5)]], ops: [click(0, 0, 1)], project: 'components' }, { slots: sparse(63, { 0: patch(2) }), cursor: patch(3) });
fix('right place preserves components', { selected: rich(5), ops: [click(0, 0, 1)], project: 'components' }, { slots: sparse(63, { 0: patch(1) }), cursor: patch(4) });
fix('creative copy preserves components', { slots: [[0, rich(5)]], ops: [click(0, 3, 2, { gamemode: 1 })], project: 'components' }, { slots: sparse(63, { 0: patch(5) }), cursor: patch(64) });
fix('partial clear preserves components', { slots: [[54, rich(5)]], ops: [{ method: 'clear', args: [stone, 2] }], project: 'components' }, { slots: sparse(63, { 54: patch(3) }), cursor: null });
fix('different components do not merge', { slots: [[0, item(2)]], selected: rich(5), ops: [click(0)], project: 'components' }, { slots: sparse(63, { 0: patch(5) }), cursor: [2, [], [], []] });
fix('shift preserves distinct component stacks', { slots: [[0, rich(5)], [62, item(2)]], ops: [click(0, 1)], project: 'components' }, { slots: sparse(63, { 61: patch(5), 62: [2, [], [], []] }), cursor: null });
fix('click type declarations need no item field', { slots: [[0, item(2)]], ops: [click(0, 0, 0, { omitItem: true })], project: 'counts' }, { slots: sparse(63, {}), cursor: [stone, 2], returns: [{ value: [0] }] });

fix('self transfer helper cannot destroy source', { slots: [[0, item(5)]], ops: [{ method: 'fillSlotWithItem', args: [{ slotItem: 0 }, { slotItem: 0 }] }], project: 'counts' }, { slots: sparse(63, { 0: [stone, 5] }), cursor: null, returns: [{ value: { undefined: true } }] });
fix('clear item zero is not wildcard', { slots: [[54, item(2)]], ops: [{ method: 'clear', args: [0] }], project: 'counts' }, { slots: sparse(63, { 54: [stone, 2] }), cursor: null, returns: [{ value: 0 }] });

const run = (f, I, s) => JSON.parse(JSON.stringify(exercise(f, I, structuredClone(s))));
const referenceResults = cases.map(({ s }) => run(factory, Item, s));
for (const { label, s, expected } of fixes) assert.notDeepEqual(run(factory, Item, s), expected, `Upstream defect must be demonstrated: ${label}`);
if (process.argv.includes('--reference-only')) {
  console.log(`Reference ready: ${cases.length} differential scenarios, ${fixes.length} independently specified defects demonstrated. No guest evaluated.`);
} else {
  const { build } = plugin('esbuild');
  const { getQuickJS } = plugin('quickjs-emscripten');
  const bundle = await build({
    stdin: { contents: `import { createItemClass } from './bb-plugin/scripting/items.mjs'; import { createWindowFactory } from './bb-plugin/scripting/windows.mjs'; globalThis.Item = createItemClass(${JSON.stringify(data)}); globalThis.factory = createWindowFactory(Item);`, resolveDir: root },
    bundle: true, write: false, platform: 'browser', format: 'iife', target: 'es2022', nodePaths: [resolve(pluginRoot, 'node_modules')],
  });
  const vm = (await getQuickJS()).newContext();
  vm.runtime.setMemoryLimit(256 * 1024 * 1024);
  const evaluate = code => {
    const result = vm.evalCode(code);
    const handle = result.error ?? result.value;
    const value = vm.dump(handle); handle.dispose();
    if (result.error) throw new Error(JSON.stringify(value));
    return value;
  };
  try {
    evaluate(bundle.outputFiles[0].text);
    evaluate(`globalThis.exercise = ${exercise};`);
    for (const [index, { label, s }] of cases.entries()) {
      const actual = JSON.parse(evaluate(`JSON.stringify(exercise(factory, Item, ${JSON.stringify(s)}))`));
      assert.deepEqual(actual, referenceResults[index], label);
    }
    for (const { label, s, expected } of fixes) {
      const actual = JSON.parse(evaluate(`JSON.stringify(exercise(factory, Item, ${JSON.stringify(s)}))`));
      assert.deepEqual(actual, expected, label);
    }
    console.log(`PASS: ${cases.length} pinned Window differential scenarios + ${fixes.length} documented/native-source corrections in bundled QuickJS. Library behavior only; no native menu/click conformance claimed.`);
  } finally { vm.dispose(); }
}
