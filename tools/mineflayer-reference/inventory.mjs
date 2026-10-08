// Preauthored before inventory.mjs. Native cases I01–I09/R01–R03 in
// inventory-native-scenarios.md (14cc401) remain lead-owned live requirements.
// Run with MINEFLAYER_REFERENCE_ROOT and MINEFLAYER_PLUGIN_ROOT pointing at
// existing installed reference/plugin roots; no downloads or game lifecycle.
const scenarios = [
  ['ordinary source comparison', 'Compare actual pinned transfer/move/equip/toss orchestration and return values against independent authoritative Window clicks.'],
  ['authoritative click barrier', 'No optimistic guest change; valid no-op resolves without a slot event; continuation sees all slots and cursor.'],
  ['serialized operations', 'Concurrent whole transfers and clicks run in invocation order with no interleaved cursor ownership.'],
  ['native merge predicates', 'Component-incompatible stacks do not merge; native per-slot/carried capacity controls splits, including full-inventory partial-stack room.'],
  ['occupied cursor', 'An unrelated cursor is stored or rejects before mutation when storage is impossible; a matching cursor may satisfy transfer.'],
  ['move swap and result putAway', 'Container cursor is authoritative; displaced item returns to source; result putAway takes one result without quick-moving repeated crafts.'],
  ['bounded transfer failure', 'No-op/insufficient/full destinations reject with truthful partial progress and bounded same-menu cursor recovery.'],
  ['known and unknown errors', 'Known native rejection may recover only in same generation; unknown action failure poisons queued work and never causes a recovery mutation.'],
  ['generation and cancellation', 'Reused menu ID with new generation rejects saved handles and queued commands; cancellation forbids all later clicks.'],
  ['open and close', 'Exact block direction/cursor coordinates and entity UUID, same hydrated Window/class identity, methods before windowOpen, actual close events once, no listener hang on denied/non-opening interaction.'],
  ['storage aliases', 'Pinned container classifier and aliases; deposit/withdraw use end-exclusive regions and native merge capacity rather than empty-slot count.'],
  ['container equipment mappings', 'Equip armor/offhand/hotbar through native inventory mappings while another menu is open; absent mapped slots reject before mutation.'],
  ['synchronous hotbar controls', 'setQuickBarSlot returns void and immediately selects; native selection is ordered, errors owned, selection survives older hydration, final drain covers controls only.'],
  ['explicit drops and overflow', 'toss count/default and tossStack drop exact authoritative stacks; only explicit putSelectedItemRange overflow may discard leftover.'],
  ['input bounds', 'Reject invalid/fractional counts, invalid/overlapping ranges, modes/buttons and unknown item IDs before mutation; zero count is a no-op.'],
  ['craft source/count/layout', 'Run pinned craft plugin for ordinary 2x2 shaped/shapeless recipes and compare placements, quantities and return behavior; parseInt counts/default/null/zero.'],
  ['craft authoritative results', 'Controlled result-slot authority consumes real fixture inputs and supplies components/count/remainders absent from Recipe.outShape; guest never predicts output or bucket mutations.'],
  ['craft table lifecycle', '2x2 and 3x3 role/index layouts, preexisting grid recovery, unrelated menu close, exact table interaction, required/wrong table and final acknowledged close.'],
  ['craft bounded failure', 'Missing ingredients/disabled or wrong result/full storage reject with actual completed count; same-menu recovery preserves inputs/cursor and never closes to drop.'],
  ['craft cancellation and queue', 'Invalidate menu or lose reply after a result pickup: no cleanup/replay after unknown outcome; whole craft owns cursor ahead of concurrent/reentrant operation.'],
  ['QuickJS runtime', 'Run actual browser-bundled adapter with existing Item/Window constructors, plugin vec3/events identities, 64 MiB memory and 512 KiB stack.'],
];

import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../..', import.meta.url));
const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? resolve(root, 'tools/mineflayer-reference'));
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? resolve(root, 'bb-plugin'));
const reference = createRequire(resolve(referenceRoot, 'package.json'));
const plugin = createRequire(resolve(pluginRoot, 'package.json'));
assert.equal(reference('mineflayer/package.json').version, '4.39.0');
const registry = reference('prismarine-registry')('1.21.1');
const windows = reference('prismarine-windows')(registry);
const { EventEmitter } = plugin('events/');
const { build } = plugin('esbuild');
const { getQuickJS } = plugin('quickjs-emscripten');
const { encodeItemTransport } = await import(pathToFileURL(resolve(pluginRoot, 'scripting/item-wire.mjs')));
const stone = registry.itemsByName.stone.id, dirt = registry.itemsByName.dirt.id;
const helmet = registry.itemsByName.diamond_helmet.id;
const plank = registry.itemsByName.oak_planks.id, stick = registry.itemsByName.stick.id;
const milk = registry.itemsByName.milk_bucket.id, bucket = registry.itemsByName.bucket.id;
const cake = registry.itemsByName.cake.id, sugar = registry.itemsByName.sugar.id;
const wheat = registry.itemsByName.wheat.id, egg = registry.itemsByName.egg.id;
const Recipe = reference('prismarine-recipe')(registry).Recipe;
const probe = windows.createWindow(0, 'minecraft:inventory', 'Inventory');
probe.middleClick({ item: { type: stone, stackSize: 64, metadata: 0, nbt: null } }, 1);
const Item = probe.selectedItem.constructor;
const make = spec => {
  if (!spec) return null;
  const item = new Item(spec.type ?? stone, spec.count, spec.metadata ?? 0, spec.nbt);
  if (spec.components) item.components = spec.components;
  if (spec.stackSize) item.stackSize = spec.stackSize;
  return item;
};
const encode = item => item && ({ type: item.type, count: item.count, metadata: item.metadata, nbt: item.nbt,
  components: item.components, removedComponents: item.removedComponents, stackSize: item.stackSize });
const invSlot = slot => slot >= 36 && slot <= 44 ? slot - 36 : slot === 45 ? 40 : slot < 9 ? 44 - slot : slot;
const same = (a, b) => a && b && a.type === b.type && a.metadata === b.metadata &&
  JSON.stringify(a.nbt) === JSON.stringify(b.nbt) && JSON.stringify(a.components) === JSON.stringify(b.components);
const bundle = await build({ stdin: { contents: `
  export { installInventory } from './inventory.mjs';
  export { createItemClass } from './items.mjs';
  export { createWindowFactory } from './windows.mjs';
  export { Vec3 } from 'vec3'; export { EventEmitter } from 'events';
  `, resolveDir: resolve(root, 'bb-plugin/scripting'), sourcefile: 'inventory-fixture-entry.mjs' },
  bundle: true, platform: 'browser', format: 'iife', globalName: 'InventoryModule', target: 'es2022', write: false,
  alias: { vec3: plugin.resolve('vec3'), events: plugin.resolve('events/') },
  // Match the production guest's transport binding, including creative's
  // transitive import. Do not replace the real encoder with a fixture stub.
  plugins: [{ name: 'item-transport', setup(build) {
    build.onResolve({ filter: /^minecraft-item-transport$/ }, () => ({ path: 'transport', namespace: 'item-transport' }));
    build.onLoad({ filter: /.*/, namespace: 'item-transport' }, () => ({ contents: `export const encodeItemTransport = ${encodeItemTransport.toString()};`, loader: 'js' }));
  } }],
  nodePaths: [resolve(pluginRoot, 'node_modules'), resolve(referenceRoot, 'node_modules')] });
const quickjs = await getQuickJS();
const data = JSON.stringify({ itemsArray: registry.itemsArray, enchantmentsByName: registry.enchantmentsByName, entitiesByName:registry.entitiesByName });

// Authoritative callback fixture uses pinned Window click semantics independently
// of the guest adapter. Native permissions/components/races are controlled inputs,
// not a claim that this models Minecraft's menus, recipes or network timing.
function authority(spec = {}) {
  const inventory = windows.createWindow(0, 'minecraft:inventory', 'Inventory');
  let window = spec.container ? windows.createWindow(4, spec.container, { text: 'Chest' }) : inventory;
  let generation = 1, selected = 0, active = true, count = 0, crafts = 0;
  const calls = [], dropped = [];
  const slotRules = spec.rules ?? {};
  for (const [slot, item] of spec.inventory ?? []) inventory.updateSlot(slot, make(item));
  if (window !== inventory) for (let slot = window.inventoryStart; slot < window.inventoryEnd; slot++)
    window.updateSlot(slot, make(encode(inventory.slots[slot - window.inventoryStart + 9])));
  for (const [slot, item] of spec.slots ?? []) window.updateSlot(slot, make(item));
  window.selectedItem = make(spec.cursor);
  const gridWidth = () => window.type === 'minecraft:inventory' ? 2 : window.type === 'minecraft:crafting' ? 3 : 0;
  // Fixed scenario recipe authority, deliberately independent of guest Recipe
  // placement/delta/outShape. This is an input/output fixture, not native proof.
  function recipeMatches() {
    if (!spec.nativeRecipe || gridWidth() !== spec.nativeRecipe.width) return false;
    return Array.from({length:gridWidth()**2},(_,index)=>index).every(index => {
      const type = spec.nativeRecipe.inputs[index], item = window.slots[index+1];
      return type == null ? !item : item?.type === type;
    });
  }
  function updateResult() {
    if (spec.nativeRecipe && gridWidth()) window.updateSlot(0, recipeMatches() && !spec.disabledRecipe ? make(spec.nativeRecipe.output) : null);
  }
  updateResult();
  function mirror() {
    if (window !== inventory) for (let slot = window.inventoryStart; slot < window.inventoryEnd; slot++)
      inventory.updateSlot(slot - window.inventoryStart + 9, make(encode(window.slots[slot])));
  }
  function state() {
    mirror();
    return { active, hands: { selected, experience:spec.experience??{level:0,progress:0,total:0,seed:0}, inventory: inventory.slots.map(encode), menu: {
      id: window.id, type: window.type, generation, title: window.title, carried: { ...encode(window.selectedItem), maxStackSize: window.selectedItem?.stackSize ?? 64 },
      ...(spec.observation?.({window,inventory})??{}),
      slots: window.slots.map((item, slot) => {
        const playerSlot = window === inventory ? slot : slot >= window.inventoryStart && slot < window.inventoryEnd ? slot - window.inventoryStart + 9 : null;
        const rules = slotRules[slot] ?? {};
        return { slot, item: encode(item), role: slot === window.craftingResultSlot ? 'crafting_result'
          : gridWidth() && slot >= 1 && slot <= gridWidth()**2 ? 'crafting_input' : 'ordinary',
          ...(gridWidth() && slot >= 1 && slot <= gridWidth()**2 ? {craftingIndex:slot-1,craftingWidth:gridWidth(),craftingHeight:gridWidth()} : {}),
          inventorySlot: playerSlot != null && (playerSlot >= 5) ? invSlot(playerSlot) : null, inventoryWindowSlot: playerSlot,
          mayPickup: rules.mayPickup !== false, mayPlaceCarried: rules.mayPlace !== false && slot !== window.craftingResultSlot,
          maxStackSize: rules.maxStackSize ?? window.selectedItem?.stackSize ?? 64,
          componentMerge: !!same(item, window.selectedItem), ...rules };
      }),
    } } };
  }
  function replace(type = 'minecraft:generic_9x3', id = 4) {
    mirror(); window = windows.createWindow(id, type, { text: 'Native title', extra: [] }); generation++;
    for (let slot = window.inventoryStart; slot < window.inventoryEnd; slot++)
      window.updateSlot(slot, make(encode(inventory.slots[slot - window.inventoryStart + 9])));
  }
  async function action(args) {
    count++; calls.push(args);
    const override = await spec.before?.({ args, count, window, inventory, replace, state });
    if (override?.cancel) active = false;
    if (override?.error) return { state: state(), error: override.error };
    if (args.type !== 'select_hotbar' && args.type !== 'interact') {
      assert.equal(args.menuId, window.id); assert.equal(args.generation, generation);
    }
    if (!override?.skip) {
      if (args.type === 'menu_click') {
        const mode = ['PICKUP', 'QUICK_MOVE', 'SWAP', 'CLONE', 'THROW'].indexOf(args.clickType);
        const rules = slotRules[args.slot] ?? {};
        if (args.slot === -999 && window.selectedItem) dropped.push({ ...encode(window.selectedItem), count: args.button === 0 ? window.selectedItem.count : 1 });
        if (spec.nativeRecipe && args.slot === 0 && mode === 0 && recipeMatches() && window.slots[0] && !window.selectedItem) {
          // A single successful ResultSlot take consumes one operation and
          // returns server-chosen leftovers, including absent outShape entries.
          window.selectedItem = make(encode(window.slots[0])); crafts++;
          for (const index of Object.keys(spec.nativeRecipe.inputs)) {
            const slot = Number(index)+1, input = window.slots[slot];
            assert.equal(input.count,1,'fixture receives exactly one operation');
            window.updateSlot(slot,make(spec.nativeRecipe.remainders?.[index]));
          }
        } else if (mode === 2 && args.button === 40) {
          const offhand = inventory.slots[45]; inventory.updateSlot(45, make(encode(window.slots[args.slot])));
          window.updateSlot(args.slot, make(encode(offhand)));
        } else if (!spec.noop && !(window.selectedItem ? rules.mayPlace === false : rules.mayPickup === false)) {
          window.acceptClick({ slot: args.slot, mouseButton: args.button, mode, item: window.slots[args.slot] ?? null }, 0);
        }
      } else if (args.type === 'select_hotbar') selected = args.slot;
      else if (args.type === 'menu_close') {
        mirror(); const cursor = window.selectedItem;
        assert.equal(cursor, null, 'fixture close expects cursor already stored');
        window = inventory; generation++;
      } else if (args.type === 'interact') {
        if (!spec.noOpen) replace(spec.openType);
      } else if (['menu_button','anvil_name','select_trade'].includes(args.type)) {
        // Specialized scenarios supply explicit authoritative responses below.
      } else throw new Error(`Unexpected action ${args.type}`);
    }
    updateResult();
    const after = await spec.after?.({ args, count, window, inventory, replace });
    return { state: state(), ...(after?.error ? {error:after.error} : {}) };
  }
  return { state, action, calls, dropped, replace, get window() { return window; }, get crafts() {return crafts;} };
}

const bootstrap = `
  const { installInventory, createItemClass, createWindowFactory, EventEmitter, Vec3 } = InventoryModule;
  const registry = ${data}; const Item = createItemClass(registry);
  const { Window, createWindow } = createWindowFactory(Item);
  const stone = ${stone}, dirt = ${dirt}, helmet = ${helmet};
  const bot = Object.assign(new EventEmitter(), { registry, inventory: createWindow(0, 'minecraft:inventory', 'Inventory'),
    currentWindow: null, quickBarSlot: 0, entity: {} });
  Object.defineProperty(bot, 'heldItem', { get() { return bot.inventory.slots[36 + bot.quickBarSlot] ?? null; } });
  class KnownError extends Error {}
  let snapshot, api, lastGeneration;
  const item = value => {
    if (!value?.count) return null;
    const result = new Item(value.type, value.count, value.metadata, value.nbt);
    if (value.components) { result.components = value.components; result.componentMap = new Map(value.components.map(v => [v.type, v])); }
    if (value.removedComponents) result.removedComponents = value.removedComponents;
    return result;
  };
  function apply(next) {
    snapshot = next;
    bot.experience = {level:next.hands.experience.level,points:next.hands.experience.total,progress:next.hands.experience.progress};
    const old = bot.currentWindow, menu = next.hands.menu, changed = [];
    const hydrate = (window, slots) => {
      for (let slot = 0; slot < slots.length; slot++) {
        const before = window.slots[slot], after = item(slots[slot]);
        if (after) after.slot = slot;
        if (JSON.stringify(before) === JSON.stringify(after)) continue;
        window.slots[slot] = after; changed.push([window, slot, before, after]);
      }
      window.selectedItem = item(menu.carried);
    };
    hydrate(bot.inventory, next.hands.inventory);
    if (menu.type === 'minecraft:inventory') { bot.currentWindow = null; hydrate(bot.inventory, menu.slots.map(s => s.item)); }
    else {
      if (!old || lastGeneration !== menu.generation) bot.currentWindow = createWindow(menu.id, menu.type, menu.title);
      hydrate(bot.currentWindow, menu.slots.map(s => s.item));
    }
    lastGeneration = menu.generation;
    bot.quickBarSlot = api.selection() ?? next.hands.selected;
    api.syncWindow(bot.currentWindow || bot.inventory, menu);
    for (const [window, slot, before, after] of changed) { window.emit('updateSlot', slot, before, after); window.emit('updateSlot:' + slot, before, after); }
    if (old && old !== bot.currentWindow) bot.emit('windowClose', old);
    if (bot.currentWindow && old !== bot.currentWindow) bot.emit('windowOpen', bot.currentWindow);
  }
  api = installInventory(bot, {
    decodeItem:item,
    snapshot: () => snapshot, assertActive() { if (!snapshot.active) throw new Error('fixture canceled'); },
    isKnownActionError: error => error instanceof KnownError,
    async action(args) {
      const result = JSON.parse(await __nativeAction(JSON.stringify(args)));
      apply(result.state);
      if (result.error) throw Object.assign(result.error.known ? new KnownError(result.error.message) : new Error(result.error.message), { code: result.error.code });
      return { status: 'completed' };
    },
  });
  const summarize = () => ({ slots: (bot.currentWindow || bot.inventory).slots.map(i => i && [i.type, i.count]),
    cursor: (bot.currentWindow || bot.inventory).selectedItem?.count ?? 0, selected: bot.quickBarSlot,
    inventory: bot.inventory.slots.map(i => i && [i.type, i.count]), current: bot.currentWindow?.type ?? null });
  const caught = async work => { try { await work(); return null; } catch(e) { return { name:e.name, code:e.code, message:e.message, completedCrafts:e.completedCrafts }; } };
`;
async function guest(spec, program) {
  const native = authority(spec), runtime = quickjs.newRuntime();
  runtime.setMemoryLimit(64 * 1024 * 1024); runtime.setMaxStackSize(512 * 1024);
  const vm = runtime.newContext();
  const errors = [];
  const callback = vm.newFunction('__nativeAction', handle => {
    const args = JSON.parse(vm.getString(handle)), pending = vm.newPromise();
    native.action(args).then(result => {
      const value = vm.newString(JSON.stringify(result)); pending.resolve(value); value.dispose();
    }, error => {
      errors.push(error);
      const value = vm.newString(JSON.stringify({ state: native.state(), error: { message: error.message } }));
      pending.resolve(value); value.dispose();
    });
    return pending.handle;
  });
  vm.setProp(vm.global, '__nativeAction', callback); callback.dispose();
  let handle;
  try {
    const evaluated = vm.evalCode(bundle.outputFiles[0].text + bootstrap + `apply(${JSON.stringify(native.state())});
      (async () => { ${program} })()`);
    if (evaluated.error) throw new Error(JSON.stringify(vm.dump(evaluated.error)));
    handle = evaluated.value;
    let result, failed, complete = false;
    vm.resolvePromise(handle).then(value => { result = value; complete = true; }, error => { failed = error; complete = true; });
    const deadline = Date.now() + 5000;
    while (!complete && Date.now() < deadline) {
      const jobs = runtime.executePendingJobs();
      if (jobs.error) { const value = vm.dump(jobs.error); jobs.error.dispose(); throw new Error(JSON.stringify(value)); }
      await new Promise(resolve => setTimeout(resolve, 0));
    }
    assert(complete, 'guest scenario timed out'); if (failed) throw failed;
    if (result.error) { const error = vm.dump(result.error); result.error.dispose(); throw new Error(JSON.stringify(error)); }
    const value = vm.dump(result.value); result.value.dispose();
    if (errors.length) throw errors[0];
    return { value, native };
  } finally { handle?.dispose(); vm.dispose(); runtime.dispose(); }
}

// Pinned public plugin code executes unchanged. Its packet writes are recorded;
// no packet is sent. Ordinary 1.21.1 clicks use the plugin's own Window semantics.
async function pinned(program, slots) {
  const bot = Object.assign(new EventEmitter(), { registry, version: '1.21.1', entity: {}, game: { gameMode: 'survival' },
    _client: new EventEmitter(), supportFeature: key => registry.supportFeature(key) });
  bot._client.write = () => {};
  reference('mineflayer/lib/plugins/inventory')(bot, { hideErrors: true });
  reference('mineflayer/lib/plugins/simple_inventory')(bot);
  bot.quickBarSlot = 0;
  for (const [slot, spec] of slots) bot.inventory.updateSlot(slot, make(spec));
  await program(bot);
  return { slots: bot.inventory.slots.map(i => i && [i.type, i.count]), selected: bot.quickBarSlot,
    cursor: bot.inventory.selectedItem?.count ?? 0 };
}

let passed = 0;
const checks = [];
const test = (name, check) => checks.push([name, check]);
test('ordinary source comparison', async () => {
  const slots = [[9, { count: 20 }], [10, { count: 15 }], [36, { count: 60 }]];
  const options = { itemType: stone, metadata: null, count: 10, sourceStart: 9, sourceEnd: 11, destStart: 36, destEnd: 38 };
  const expected = await pinned(async bot => { assert.equal(await bot.transfer(options), undefined); await bot.moveSlotItem(10, 38); await bot.equip(stone); await bot.toss(stone, null, 2); }, slots);
  const actual = await guest({ slots }, `
    const returned = await bot.transfer(${JSON.stringify(options)});
    await bot.moveSlotItem(10,38); await bot.equip(stone); await bot.toss(stone,null,2);
    const {slots,selected,cursor} = summarize(); return {slots,selected,cursor, returned: returned === undefined};`);
  assert.equal(actual.value.returned, true); delete actual.value.returned;
  assert.deepEqual(actual.value, expected);
});
test('authoritative click barrier', async () => {
  const actual = await guest({ slots: [[9, { count: 7 }]] }, `
    let events = 0; bot.inventory.on('updateSlot', () => { events++; });
    const pending = bot.simpleClick.leftMouse(9);
    const before = bot.inventory.slots[9].count;
    await pending;
    const after = [bot.inventory.slots[9],bot.inventory.selectedItem.count,events];
    await bot.clickWindow(10,1,0); await bot.clickWindow(9,0,0);
    return { before, after, final:summarize() };`);
  assert.equal(actual.value.before, 7); assert.deepEqual(actual.value.after, [null, 7, 1]);
  assert.deepEqual(actual.value.final.slots[10], [stone, 1]);
  const noop = await guest({ noop: true }, `return {result:await bot.clickWindow(9,0,0) === undefined};`);
  assert.equal(noop.value.result, true); assert.equal(noop.native.calls.length, 1);
});
test('serialized operations', async () => {
  const actual = await guest({ slots: [[9, { count: 5 }], [10, { type: dirt, count: 6 }]] }, `
    await Promise.all([bot.moveSlotItem(9,36),bot.moveSlotItem(10,37),bot.clickWindow(38,0,0)]); return summarize();`);
  assert.deepEqual(actual.native.calls.map(a => a.slot), [9,36,10,37,38]);
  assert.deepEqual(actual.value.slots[36], [stone,5]); assert.deepEqual(actual.value.slots[37], [dirt,6]);
  const reentrant = await guest({slots:[[9,{count:5}],[10,{type:dirt,count:6}]]},`
    let queued, observed;
    bot.inventory.once('updateSlot:9',()=>{ observed=bot.inventory.selectedItem.count; queued=bot.moveSlotItem(10,37); });
    await bot.moveSlotItem(9,36); await queued; return {observed,state:summarize()};`);
  assert.equal(reentrant.value.observed,5); assert.deepEqual(reentrant.native.calls.map(a=>a.slot),[9,36,10,37]);
});
test('native merge predicates', async () => {
  const a = [{ type:'custom_name',data:{type:'string',value:'A'} }], b = [{type:'custom_name',data:{type:'string',value:'B'}}];
  const actual = await guest({ container:'minecraft:generic_9x3', slots:[[0,{count:60,components:b}],[27,{count:6,components:a}]] }, `
    await bot.currentWindow.deposit(stone,null,6); return summarize();`);
  assert.deepEqual(actual.value.slots[0],[stone,60]); assert.deepEqual(actual.value.slots[1],[stone,6]);
  const full = Array.from({length:36},(_,i)=>[i+27,{type:i ? dirt:stone,count:i ? 64:60}]);
  const merge = await guest({container:'minecraft:generic_9x3',slots:[[0,{count:4}],...full]},`
    await bot.currentWindow.withdraw(stone,null,4); return summarize();`);
  assert.deepEqual(merge.value.slots[27],[stone,64]); assert.equal(merge.value.cursor,0);
  const limits = await guest({slots:[[9,{count:4,stackSize:16}],[36,{count:14,stackSize:16}]]},`
    await bot.transfer({itemType:stone,count:4,sourceStart:9,destStart:36,destEnd:38}); return summarize();`);
  assert.deepEqual(limits.value.slots[36],[stone,16]); assert.deepEqual(limits.value.slots[37],[stone,2]);
});
test('occupied cursor', async () => {
  const actual = await guest({slots:[[9,{count:3}]],cursor:{type:dirt,count:2}},`
    await bot.moveSlotItem(9,36); return summarize();`);
  assert.equal(actual.value.cursor,0); assert.deepEqual(actual.value.slots[10],[dirt,2]);
  const full = Array.from({length:36},(_,i)=>[i+9,{count:64}]);
  const denied = await guest({slots:full,cursor:{type:dirt,count:2}},`return await caught(()=>bot.moveSlotItem(9,36));`);
  assert.equal(denied.value.code,'OccupiedCursor'); assert.equal(denied.native.calls.length,0);
  const selected = await guest({cursor:{count:4}},`
    await bot.transfer({itemType:stone,count:2,sourceStart:9,sourceEnd:10,destStart:36,destEnd:37}); return summarize();`);
  assert.deepEqual(selected.value.slots[36],[stone,2]); assert.deepEqual(selected.value.slots[9],[stone,2]);
});
test('move swap and result putAway', async () => {
  const swap = await guest({container:'minecraft:generic_9x3',slots:[[0,{count:4}],[1,{type:dirt,count:2}]]},`
    await bot.moveSlotItem(0,1); return summarize();`);
  assert.deepEqual(swap.value.slots.slice(0,2),[[dirt,2],[stone,4]]); assert.equal(swap.value.cursor,0);
  const result = await guest({slots:[[0,{count:4}]]},`await bot.putAway(0); return summarize();`);
  assert(result.native.calls.every(a=>a.clickType!=='QUICK_MOVE')); assert.deepEqual(result.value.slots[9],[stone,4]);
  const empty = await guest({},`await bot.putAway(9); return summarize();`); assert.equal(empty.native.calls.length,0);
});
test('bounded transfer failure', async () => {
  const actual = await guest({slots:[[9,{count:5}]],rules:{36:{mayPlace:false}}},`
    const error=await caught(()=>bot.transfer({itemType:stone,count:4,sourceStart:9,destStart:36})); return {error,state:summarize()};`);
  assert.equal(actual.value.error.code,'DestinationFull'); assert.equal(actual.value.state.cursor,0);
  assert.deepEqual(actual.value.state.slots[9],[stone,5]); assert.equal(actual.native.calls.length,2);
  const noop = await guest({slots:[[9,{count:5}]],noop:true},`return await caught(()=>bot.moveSlotItem(9,36));`);
  assert.equal(noop.value.code,'NoProgress'); assert.equal(noop.native.calls.length,1);
});
test('known and unknown errors', async () => {
  for (const known of [true,false]) {
    const actual = await guest({slots:[[9,{count:5}]],before:({count})=>count===2?{error:{known,message:'fixture rejection'}}:null},`
      const first=bot.moveSlotItem(9,36), later=bot.clickWindow(38,0,0);
      const outcomes=await Promise.allSettled([first,later]);
      return {outcomes:outcomes.map(r=>r.status),state:summarize()};`);
    assert.equal(actual.value.outcomes[0],'rejected');
    assert.equal(actual.native.calls.length,known?4:2);
    assert.equal(actual.value.state.cursor,known?0:5);
    assert.equal(actual.value.outcomes[1],known?'fulfilled':'rejected');
  }
});
test('generation and cancellation', async () => {
  const actual = await guest({container:'minecraft:generic_9x3',slots:[[0,{count:3}]],after:({count,replace})=>{if(count===1)replace();}},`
    const window=bot.currentWindow; const first=bot.moveSlotItem(0,27), later=window.withdraw(stone,null,1);
    return (await Promise.allSettled([first,later])).map(r=>r.status);`);
  assert.deepEqual(actual.value,['rejected','rejected']); assert.equal(actual.native.calls.length,1);
  const canceled = await guest({slots:[[9,{count:3}]],before:({count})=>count===1?{cancel:true}:null},`
    const a=bot.moveSlotItem(9,36), b=bot.clickWindow(10,0,0); return (await Promise.allSettled([a,b])).map(r=>r.status);`);
  assert.deepEqual(canceled.value,['rejected','rejected']); assert.equal(canceled.native.calls.length,1);
});
test('open and close', async () => {
  const actual = await guest({},`
    let ready=false, closes=0;
    bot.on('windowOpen',w=>{ready=w instanceof Window && typeof w.deposit==='function'; w.on('close',()=>closes++);});
    const w=await bot.openBlock({position:new Vec3(1,2,3)},new Vec3(0,0,-1),new Vec3(.2,.3,0));
    const identity=w===bot.currentWindow && w instanceof Window; const title=w.title;
    await w.close(); return {ready,identity,title,closes,current:bot.currentWindow};`);
  assert.deepEqual(actual.native.calls[0],{type:'interact',position:{x:1,y:2,z:3},face:'north',cursorPos:{x:.2,y:.3,z:0}});
  assert.equal(actual.value.identity,true); assert.equal(actual.value.ready,true); assert.equal(actual.value.closes,1);
  assert.deepEqual(actual.value.title,{text:'Native title',extra:[]}); assert.equal(actual.value.current,null);
  const denied=await guest({noOpen:true},`return await caught(()=>bot.openEntity({uuid:'fixture-uuid'}));`);
  assert.equal(denied.value.code,'NoWindowOpened'); assert.equal(denied.native.calls[0].entity,'fixture-uuid');
});
test('storage aliases', async () => {
  const actual=await guest({},`
    class Block { constructor(){this.name='chest';this.position=new Vec3(1,2,3);} }
    const aliases=bot.openChest===bot.openContainer && bot.openDispenser===bot.openContainer;
    const w=await bot.openChest(new Block()); const same=w===bot.currentWindow;
    return {aliases,same,bad:await caught(()=>bot.openChest({name:'chest'}))};`);
  assert.equal(actual.value.aliases,true); assert.equal(actual.value.same,true); assert.equal(actual.value.bad.code,'NotContainer');
});
test('container equipment mappings', async () => {
  const actual=await guest({container:'minecraft:generic_9x3',inventory:[[10,{count:3}],[37,{type:dirt,count:2}]]},`
    await bot.equip(bot.inventory.slots[10],'hand'); const held=bot.heldItem.count;
    await bot.equip(bot.inventory.slots[37],'off-hand'); return {held,state:summarize()};`);
  assert.equal(actual.value.held,3); assert.deepEqual(actual.value.state.inventory[45],[dirt,2]);
  assert(actual.native.calls.some(a=>a.clickType==='SWAP'&&a.button===40));
  const armor=await guest({container:'minecraft:generic_9x3',inventory:[[9,{type:helmet,count:1}]]},`
    await bot.equip(bot.inventory.slots[9],'head'); return summarize();`);
  assert.equal(armor.native.calls[0].type,'menu_close'); assert.deepEqual(armor.value.inventory[5],[helmet,1]);
  const stored = await guest({container:'minecraft:generic_9x3',slots:[[0,{count:2}]]},`
    await bot.equip(bot.currentWindow.slots[0],'hand'); return summarize();`);
  assert.deepEqual(stored.value.inventory[36],[stone,2]); assert.deepEqual(stored.native.calls.map(a=>a.slot),[0,54]);
  const chestArmor = await guest({container:'minecraft:generic_9x3',slots:[[0,{type:helmet,count:1}]]},`
    await bot.equip(bot.currentWindow.slots[0],'head'); await bot.unequip('head'); return summarize();`);
  assert.equal(chestArmor.value.inventory[5],null); assert(chestArmor.value.inventory.some(i=>i?.[0]===helmet));
});
test('synchronous hotbar controls', async () => {
  const actual=await guest({inventory:[[37,{count:2}],[38,{type:dirt,count:3}]]},`
    const events=[]; bot.on('heldItemChanged',item=>events.push(item?.type??null));
    const returned=bot.setQuickBarSlot(1); bot.setQuickBarSlot(2);
    const immediate=[bot.quickBarSlot,bot.heldItem.type,returned===undefined];
    await bot.clickWindow(9,0,0); await api.drainControls(); return {immediate,events,state:summarize(),pending:api.selection()??null};`);
  assert.deepEqual(actual.value.immediate,[2,dirt,true]); assert.equal(actual.value.pending,null);
  assert.deepEqual(actual.value.events,[stone,dirt]);
  assert.deepEqual(actual.native.calls.map(a=>a.type),['select_hotbar','select_hotbar','menu_click']);
  const bad=await guest({before:()=>({error:{known:true,message:'selection denied'}})},`
    bot.setQuickBarSlot(1); const drain=await caught(()=>api.drainControls());
    const next=await caught(()=>bot.clickWindow(9,0,0)); return {drain,next,state:summarize()};`);
  assert.equal(bad.native.calls.length,1); assert.equal(bad.value.state.selected,0); assert.equal(bad.value.drain.message,'selection denied');
});
test('explicit drops and overflow', async () => {
  const tossed=await guest({slots:[[9,{count:7}]]},`await bot.toss(stone,null,2); return summarize();`);
  assert.equal(tossed.native.dropped.reduce((sum,i)=>sum+i.count,0),2); assert.deepEqual(tossed.value.slots[9],[stone,5]);
  const stack=await guest({slots:[[9,{count:7}]]},`await bot.tossStack(bot.inventory.slots[9]); return summarize();`);
  assert.equal(stack.native.dropped[0].count,7); assert.equal(stack.native.calls.at(-1).type,'menu_close');
  const overflow=await guest({slots:[[9,{type:dirt,count:64}]],cursor:{count:3}},`
    await bot.putSelectedItemRange(9,10,bot.inventory,null); return summarize();`);
  assert.equal(overflow.value.cursor,0); assert.equal(overflow.native.dropped[0].count,3);
  // Explicit correction: pinned equipEmpty tosses when full. The native contract
  // forbids implicit drops, so unequip must reject without a mutation instead.
  const full=await guest({slots:Array.from({length:36},(_,i)=>[i+9,{count:64}])},`
    return await caught(()=>bot.unequip('hand'));`);
  assert.equal(full.value.code,'DestinationFull'); assert.equal(full.native.calls.length,0);
});
test('input bounds', async () => {
  const actual=await guest({slots:[[9,{count:5}]]},`
    const errors=[];
    for(const count of [-1,1.5,Infinity]) errors.push(await caught(()=>bot.transfer({itemType:stone,count,sourceStart:9,destStart:36})));
    errors.push(await caught(()=>bot.transfer({itemType:stone,count:1,sourceStart:9,destStart:9})));
    for(const mode of [5,6]) errors.push(await caught(()=>bot.clickWindow(9,0,mode)));
    errors.push(await caught(()=>bot.clickWindow(9,9,0)));
    await bot.transfer({itemType:stone,count:0,sourceStart:9,destStart:36});
    return errors.map(e=>e.code);`);
  assert.equal(actual.native.calls.length,0); assert(actual.value.every(Boolean));
});

const stickRecipe = Recipe.find(stick).find(recipe=>recipe.inShape?.[0][0].id===plank);
const stickAuthority = {width:2,inputs:{0:plank,2:plank},output:{type:stick,count:4}};
const total = (slots,type) => slots.reduce((sum,item)=>sum+(item?.[0]===type?item[1]:0),0);
test('craft source/count/layout',async()=>{
  const trace=[];
  const expected=await pinned(async bot=>{
    reference('mineflayer/lib/plugins/craft')(bot);
    // Feed the result refresh packet the real inventory plugin waits for.
    // Craft's own legacy local result synthesis remains visible to this oracle.
    bot._client.write=(name,packet)=>{
      if(name==='window_click'&&packet.slot>=0&&packet.slot<=4)
        setImmediate(()=>bot._client.emit('set_slot',{windowId:0,stateId:1,slot:0,item:Item.toNotch(bot.inventory.slots[0])}));
    };
    const click=bot.clickWindow; bot.clickWindow=async(...args)=>{trace.push(args); return click(...args);};
    assert.equal(await bot.craft(stickRecipe,2),undefined);
  },[[9,{type:plank,count:4}]]);
  const actual=await guest({slots:[[9,{type:plank,count:4}]],nativeRecipe:stickAuthority},`
    const value=await bot.craft(${JSON.stringify(stickRecipe)},2); return {value:value===undefined,state:summarize()};`);
  assert.equal(actual.value.value,true); assert.equal(actual.native.crafts,2);
  assert.equal(total(actual.value.state.inventory,stick),8); assert.equal(total(expected.slots,stick),8);
  assert.equal(total(actual.value.state.inventory,plank),0);
  // A one-item carried stack may use left-click instead of source's always-right
  // placement; compare actual placement cells and quantities, not click spelling.
  const placements=actual.native.calls.filter(a=>a.type==='menu_click'&&a.slot>=1&&a.slot<=4).map(a=>a.slot);
  assert.deepEqual(placements,trace.filter(a=>a[1]===1&&a[0]>=1&&a[0]<=4).map(a=>a[0]));
  for(const count of [undefined,null,0,1.9]) {
    const value=await guest({slots:[[9,{type:plank,count:4}]],nativeRecipe:stickAuthority},`
      await bot.craft(${JSON.stringify(stickRecipe)},${count===undefined?'undefined':JSON.stringify(count)}); return summarize();`);
    assert.equal(value.native.crafts,count===0?0:1);
  }
  const planksRecipe=Recipe.find(plank)[0], log=planksRecipe.ingredients[0].id;
  const shapeless=await guest({slots:[[9,{type:log,count:1}]],nativeRecipe:{width:2,inputs:{3:log},output:{type:plank,count:4}}},`
    await bot.craft(${JSON.stringify(planksRecipe)}); return summarize();`);
  assert.equal(shapeless.native.crafts,1); assert.equal(total(shapeless.value.inventory,plank),4);
});
test('craft authoritative results',async()=>{
  const cakeRecipe=Recipe.find(cake)[0]; assert.equal(cakeRecipe.outShape,null);
  const inputs={0:milk,1:milk,2:milk,3:sugar,4:egg,5:sugar,6:wheat,7:wheat,8:wheat};
  const milkSlots=Array.from({length:6},(_,i)=>[i+9,{type:milk,count:1}]);
  const actual=await guest({inventory:[...milkSlots,[15,{type:sugar,count:4}],[16,{type:egg,count:2}],[17,{type:wheat,count:6}]],
    openType:'minecraft:crafting',nativeRecipe:{width:3,inputs,output:{type:cake,count:1},remainders:{0:{type:bucket,count:1},1:{type:bucket,count:1},2:{type:bucket,count:1}}}},`
    await bot.craft(${JSON.stringify(cakeRecipe)},2,{position:new Vec3(1,2,3)}); return summarize();`);
  assert.equal(actual.native.crafts,2); assert.equal(total(actual.value.inventory,cake),2);
  assert.equal(total(actual.value.inventory,bucket),6); assert.equal(actual.value.cursor,0);
  assert.equal(actual.native.dropped.length,0); assert.equal(actual.native.calls.at(-1).type,'menu_close');
  const components=[{type:'custom_name',data:{type:'string',value:'Native result'}}];
  const nativeCount=await guest({slots:[[9,{type:plank,count:2}]],nativeRecipe:{...stickAuthority,output:{type:stick,count:2,components}}},`
    await bot.craft(${JSON.stringify(stickRecipe)}); const output=bot.inventory.items().find(item=>item.type===${stick});
    return {count:output.count,components:output.components};`);
  assert.deepEqual(nativeCount.value,{count:2,components});
});
test('craft table lifecycle',async()=>{
  const chest=registry.itemsByName.chest.id;
  const recipe=Recipe.find(chest).find(r=>r.inShape[0][0].id===plank);
  const inputs={0:plank,1:plank,2:plank,3:plank,5:plank,6:plank,7:plank,8:plank};
  const actual=await guest({container:'minecraft:generic_9x3',inventory:[[9,{type:plank,count:8}]],openType:'minecraft:crafting',
    nativeRecipe:{width:3,inputs,output:{type:chest,count:1}}},`
    await bot.craft(${JSON.stringify(recipe)},1,{position:new Vec3(1,2,3)}); return summarize();`);
  assert.equal(actual.native.calls[0].type,'menu_close'); assert.equal(actual.native.calls[1].type,'interact');
  assert.equal(actual.value.current,null); assert.equal(total(actual.value.inventory,chest),1);
  assert(!actual.native.calls.some(a=>a.type==='menu_click'&&a.slot===5&&a.button===1));
  const existing=await guest({slots:[[1,{type:dirt,count:3}],[9,{type:plank,count:2}]],nativeRecipe:stickAuthority},`
    await bot.craft(${JSON.stringify(stickRecipe)}); return summarize();`);
  assert.equal(total(existing.value.inventory,dirt),3); assert.equal(total(existing.value.inventory,stick),4);
  const required=await guest({},`return await caught(()=>bot.craft(${JSON.stringify(recipe)}));`);
  assert.equal(required.value.code,'RequiresCraftingTable'); assert.equal(required.native.calls.length,0);
  const wrong=await guest({},`return await caught(()=>bot.craft(${JSON.stringify(recipe)},1,{position:new Vec3(1,2,3)}));`);
  assert.equal(wrong.value.code,'NotCraftingTable'); assert.equal(wrong.native.calls.length,1);
});
test('craft bounded failure',async()=>{
  for(const options of [{slots:[[9,{type:plank,count:1}]],code:'InsufficientItems'},
    {slots:[[9,{type:plank,count:2}]],disabledRecipe:true,code:'CraftResultUnavailable'},
    {slots:[[9,{type:plank,count:2}]],nativeRecipe:{...stickAuthority,output:{type:dirt,count:1}},code:'CraftResultUnavailable'}]) {
    const value=await guest({nativeRecipe:stickAuthority,...options},`
      const error=await caught(()=>bot.craft(${JSON.stringify(stickRecipe)})); return {error,state:summarize()};`);
    assert.equal(value.value.error.code,options.code); assert.equal(value.value.error.completedCrafts,0);
    assert.equal(value.value.state.cursor,0); assert.equal(value.native.crafts,0); assert.equal(value.native.dropped.length,0);
    assert.equal(total(value.value.state.inventory,plank),options.slots[0][1].count);
  }
  const full=await guest({slots:Array.from({length:36},(_,i)=>[i+9,{type:i===0?plank:dirt,count:64}]),nativeRecipe:stickAuthority},`
    const error=await caught(()=>bot.craft(${JSON.stringify(stickRecipe)})); return {error,state:summarize()};`);
  assert.equal(full.value.error.code,'DestinationFull'); assert.equal(full.value.error.completedCrafts,1);
  assert.equal(full.value.state.cursor,4); assert.equal(full.native.dropped.length,0);
  assert(!full.native.calls.some(a=>a.type==='menu_close'));
});
test('craft cancellation and queue',async()=>{
  const unknown=await guest({slots:[[9,{type:plank,count:4}]],nativeRecipe:stickAuthority,
    after:({args})=>args.type==='menu_click'&&args.slot===0?{error:{message:'lost result reply'}}:undefined},`
    const outcomes=await Promise.allSettled([bot.craft(${JSON.stringify(stickRecipe)},2),bot.clickWindow(10,0,0)]);
    return outcomes.map(r=>r.status);`);
  assert.deepEqual(unknown.value,['rejected','rejected']); assert.equal(unknown.native.crafts,1);
  const last=unknown.native.calls.at(-1); assert.equal(last.type,'menu_click');
  // Actual result pickup executed but its reply failed: no storage, cleanup,
  // second operation or queued click may run.
  assert.equal(last.slot,0); assert.equal(unknown.native.window.selectedItem.count,4);
  const changed=await guest({slots:[[9,{type:plank,count:4}]],nativeRecipe:stickAuthority,
    after:({args,replace})=>{if(args.type==='menu_click'&&args.slot===0) replace();}},`
    return await caught(()=>bot.craft(${JSON.stringify(stickRecipe)},2));`);
  assert.equal(changed.value.code,'WindowChanged'); assert.equal(changed.native.calls.at(-1).slot,0);
  const queued=await guest({slots:[[9,{type:plank,count:4}]],nativeRecipe:stickAuthority},`
    await Promise.all([bot.craft(${JSON.stringify(stickRecipe)},2),bot.clickWindow(40,0,0)]); return summarize();`);
  assert.equal(queued.native.crafts,2); assert.equal(queued.native.calls.at(-1).slot,40);
});

export { guest, pinned, authority, registry, reference, plugin, make, encode, Item, windows, EventEmitter };
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
const results=[];
for(const [name,check] of checks) {
  try { await check(); passed++; results.push({name,status:'passed'}); console.log(`PASS ${name}`); }
  catch(error) { results.push({name,status:'failed',error:error.stack}); console.error(`FAIL ${name}: ${error.message}`); }
}
console.log(JSON.stringify({passed,failed:checks.length-passed,preauthored:scenarios,results,
  quickjs:{memory:64*1024*1024,stack:512*1024,browserBundleBytes:bundle.outputFiles[0].contents.length},
  evidence:'Actual pinned plugins/Window source oracle and bundled guest orchestration only; no native/server/live execution.'},null,2));
process.exitCode=checks.length===passed?0:1;
}
