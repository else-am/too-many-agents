// Authored against pinned upstream before the guest implementation. No game or client.
// Set MINEFLAYER_REFERENCE_ROOT and MINEFLAYER_PLUGIN_ROOT to installed packages.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../..', import.meta.url));
const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? resolve(root, 'tools/mineflayer-reference'));
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? resolve(root, 'bb-plugin'));
const reference = createRequire(resolve(referenceRoot, 'package.json'));
const plugin = createRequire(resolve(pluginRoot, 'package.json'));
for (const [name, version] of Object.entries({ 'prismarine-recipe': '1.5.0', mineflayer: '4.39.0', 'minecraft-data': '3.117.0', 'prismarine-windows': '2.10.0', 'prismarine-item': '1.18.0' })) {
  assert.equal(reference(resolve(referenceRoot, 'node_modules', name, 'package.json')).version, version, name);
}
const registry = reference('minecraft-data')('1.21.1');
const createReference = reference('prismarine-recipe');
const injectCraft = reference('mineflayer/lib/plugins/craft');
const referenceWindows = reference('prismarine-windows')('1.21.1');
const ReferenceItem = reference('prismarine-item')('1.21.1');
const selected = Object.fromEntries(['recipes', 'items', 'itemsArray', 'enchantmentsByName'].map(k => [k, registry[k]]));
function encode(value) {
  if (value === undefined) return ['undefined'];
  if (typeof value === 'number' && !Number.isFinite(value)) return ['number', String(value)];
  if (Array.isArray(value)) return value.map(encode);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, encode(value[k])]));
  return value;
}
function exercise(createFactory, install, windows, Item, registry, s) {
  const data = s.registry ? { ...registry, ...s.registry } : registry;
  const { Recipe, RecipeItem } = createFactory(data);
  const describe = r => ({ fields: r, instance: r instanceof Recipe, resultInstance: r.result instanceof RecipeItem,
    cellInstances: [r.inShape, r.outShape].every(shape => !shape || shape.every(row => row.every(i => i instanceof RecipeItem))),
    deltaInstances: r.delta.every(i => i instanceof RecipeItem), ingredientInstances: !r.ingredients || r.ingredients.every(i => i instanceof RecipeItem) });
  try {
    if (s.kind === 'item') {
      const input = s.value;
      let value;
      if (s.method === 'constructor') value = new RecipeItem(...s.args);
      else if (s.method === 'clone') value = RecipeItem.clone(input);
      else value = RecipeItem.fromEnum(input);
      return { value, instance: value instanceof RecipeItem, distinct: value !== input, input };
    }
    if (s.kind === 'construct') {
      const raw = s.raw;
      const before = JSON.stringify(raw);
      const r = new Recipe(raw);
      const desc = describe(r);
      if (s.project === 'delta') return r.delta;
      // Each occurrence, including duplicate ingredients, owns a separate object.
      const cells = [...(r.inShape ?? []).flat(), ...(r.outShape ?? []).flat(), ...(r.ingredients ?? []), r.result, ...r.delta];
      return { ...desc, unchanged: JSON.stringify(raw) === before, distinct: new Set(cells).size === cells.length };
    }
    if (s.kind === 'find') {
      const result = Recipe.find(...s.args);
      if (s.project === 'counts') return result.map(r => r.result.count);
      const again = Recipe.find(...s.args);
      return { result: result.map(describe), fresh: result !== again && result.every((r, i) => r !== again[i] && r.result !== again[i].result) };
    }
    if (s.kind === 'isolation') {
      createFactory({ recipes: {}, items: {} });
      return Recipe.find(s.id).length;
    }
    if (s.kind === 'surface') return { exports: Object.keys(createFactory(data)).sort(), recipeStatic: Object.keys(Recipe).sort(), itemStatic: Object.keys(RecipeItem).sort(),
      arities: [Recipe.length, Recipe.find.length, RecipeItem.length, RecipeItem.fromEnum.length, RecipeItem.clone.length],
      prototypes: [Object.getOwnPropertyNames(Recipe.prototype), Object.getOwnPropertyNames(RecipeItem.prototype)] };
    const bot = { registry: data, inventory: windows.createWindow(0, 'minecraft:inventory') };
    for (const spec of s.inventory ?? []) {
      const item = new Item(spec.id, spec.count, spec.metadata ?? 0);
      if (spec.components) item.components = spec.components;
      bot.inventory.updateSlot(spec.slot, item);
    }
    install(bot, { Recipe, RecipeItem });
    const inventoryBefore = JSON.stringify(bot.inventory.slots);
    const calls = [], count = bot.inventory.count.bind(bot.inventory);
    bot.inventory.count = (id, metadata) => { calls.push([id, metadata]); return count(id, metadata); };
    if (s.replaceInventory) {
      const replacement = windows.createWindow(0, 'minecraft:inventory');
      const entry = s.replaceInventory;
      replacement.updateSlot(9, new Item(entry.id, entry.count, entry.metadata ?? 0));
      bot.inventory = replacement;
    }
    const result = bot[s.method ?? 'recipesFor'](...s.args);
    if (s.project === 'counts') return result.map(r => r.result.count);
    return { result: result.map(describe), calls, synchronous: Array.isArray(result),
      unchanged: s.replaceInventory ? null : inventoryBefore === JSON.stringify(bot.inventory.slots),
      arities: [bot.recipesFor.length, bot.recipesAll.length] };
  } catch (error) { return { error: error.name }; } // Engine TypeError messages differ.
}
const cases = [], fixes = [];
const add = (label, s) => cases.push({ label, s });
const fix = (label, s, expected) => fixes.push({ label, s, expected });
const id = name => registry.itemsByName[name].id;
add('public constructors/statics/prototypes', { kind: 'surface' });
for (const value of [undefined, null, -1, 0, 100, NaN, Infinity, false, true, 'stone', [], [1, 2], {}, { id: 3 }, { id: 3, metadata: 0, count: 0 }, { id: 3, metadata: 2, count: -3 }, { id: 3, metadata: 32767, count: 5 }]) {
  for (const method of ['fromEnum', 'clone']) add(`RecipeItem ${method} ${String(value)}/${JSON.stringify(value)}`, { kind: 'item', method, value });
}
for (const args of [[], [1], [1, null, 2], [-1, 0, -3]]) add(`RecipeItem constructor ${args}`, { kind: 'item', method: 'constructor', args });
for (const raw of [undefined, null, {}, { result: null }, { result: 10 }, { result: {} }, { result: { id: 10, count: 0 } },
  { result: 10, inShape: [] }, { result: 10, ingredients: [] }, { result: 10, ingredients: [null, 1, 1] },
  { result: 10, inShape: [[1, null], [null, 1]] }, { result: 1, ingredients: [1] },
  { result: 10, inShape: [[{ id: 1, count: 9 }]], ingredients: [{ id: 2, count: 7 }] },
  { result: 10, inShape: [[null, 1], [null, null]], ingredients: [2] },
  { result: 10, inShape: [[1, 2, 3]] }, { result: 10, inShape: [[1], [1], [1]] },
  { result: 10, ingredients: [1, 2, 3, 4] }, { result: 10, ingredients: [1, 2, 3, 4, 5] },
  { result: 10, ingredients: [undefined] }, { result: 10, inShape: [null] }, { result: 10, ingredients: [false] }]) {
  add(`constructor ${JSON.stringify(raw)}`, { kind: 'construct', raw });
}
for (const metadata of [null, 0, 1, 2, -1, 32766, 32767, 65535]) {
  add(`ingredient metadata normalization ${metadata}`, { kind: 'construct', registry: { items: { 1: { variations: [{ metadata: 0 }, { metadata: 1 }] } } },
    raw: { result: { id: 10, metadata }, inShape: [[{ id: 1, metadata }, { id: 2, metadata }, { id: -1, metadata }]], ingredients: [{ id: 1, metadata }] } });
}
for (const args of [[], [-1], [null], [999999], [id('stone')], [id('oak_planks'), null], [id('oak_planks'), 3]]) add(`find edge ${args}`, { kind: 'find', args });
// Sweep every raw recipe and every recipe-bearing item; no sampled registry subset.
for (const [item, recipes] of Object.entries(registry.recipes)) {
  for (const [i, raw] of recipes.entries()) add(`registry constructor ${item}/${i}`, { kind: 'construct', raw });
  for (const metadata of [null, 0, 7]) add(`registry find ${item}/${metadata}`, { kind: 'find', args: [Number(item), metadata] });
  add(`registry empty inventory query ${item}`, { args: [Number(item), null, 1, {}] });
  add(`registry tableless listing ${item}`, { method: 'recipesAll', args: [Number(item), null, null] });
}
// Real upstream Window and Item instances provide inventory counting in both VMs.
for (const name of ['oak_planks', 'stick', 'crafting_table', 'chest', 'cake', 'bucket', 'iron_pickaxe', 'bread', 'white_wool', 'diamond_block', 'diamond']) {
  const recipes = registry.recipes[id(name)] ?? [];
  const ingredients = [...new Set(recipes.flatMap(r => [...(r.inShape ?? []).flat(), ...(r.ingredients ?? [])]).filter(v => v != null).map(v => typeof v === 'number' ? v : v.id))];
  const inventory = ingredients.map((item, i) => ({ id: item, slot: 9 + i, count: 64 }));
  assert(ingredients.length <= 36, name);
  for (const table of [null, false, {}, { name: 'stone' }]) for (const count of [undefined, null, 0, -1, 1, 2, 4, 5, 64, 65, 1000, 1.5, '2', NaN, Infinity]) {
    add(`inventory query ${name}/${String(table)}/${String(count)}`, { inventory, args: [id(name), null, count, table] });
  }
}
const queryRecipe = { result: { id: id('stick'), count: 4 }, ingredients: [{ id: id('oak_planks'), metadata: 0 }, { id: id('oak_planks'), metadata: 0 }] };
const queryRegistry = { recipes: { [id('stick')]: [queryRecipe] } };
for (const slot of [0, 1, 5, 8, 9, 35, 36, 44, 45]) for (const metadata of [0, 1]) {
  add(`inventory slot ${slot} metadata ${metadata}`, { registry: queryRegistry, inventory: [{ slot, metadata, id: id('oak_planks'), count: 2 }], args: [id('stick'), null, 4, null] });
}
add('inventory combines split stacks and ignores component distinction as upstream', { registry: queryRegistry,
  inventory: [{ id: id('oak_planks'), count: 1, slot: 9 }, { id: id('oak_planks'), count: 1, slot: 44, components: [{ type: 'custom_name', data: 'Different' }] }], args: [id('stick'), null, 4] });
add('queries observe replacement inventory synchronously', { registry: queryRegistry, replaceInventory: { id: id('oak_planks'), count: 2 }, args: [id('stick'), null, 4] });
add('net-delta seed limitation is retained', { registry: { recipes: { 1: [{ result: { id: 1, count: 2 }, ingredients: [1] }] } }, args: [1, null, 1] });
// Named source defects: independent expectations from documented metadata/deltas.
const ri = (id, count, metadata = null) => ({ id, metadata, count });
fix('outShape contributes returned items, not a second copy of inShape', { kind: 'construct', project: 'delta', raw: {
  result: { id: 10, count: 1 }, inShape: [[1, 1, 1], [2, 3, 2]], outShape: [[4, 4, 4], [null, null, null]] } }, [ri(1, -3), ri(2, -2), ri(3, -1), ri(4, 3), ri(10, 1)]);
fix('outShape alone has a defined delta', { kind: 'construct', project: 'delta', raw: { result: 10, outShape: [[4]] } }, [ri(4, 1), ri(10, 1)]);
fix('explicit result metadata matches only requested metadata', { kind: 'find', project: 'counts', args: [10, 1], registry: { recipes: { 10: [{ result: { id: 10, metadata: 0, count: 2 } }, { result: { id: 10, metadata: 1, count: 3 } }] } } }, [3]);
fix('numeric result accepts metadata wildcard without in-operator TypeError', { kind: 'find', project: 'counts', args: [10, 0], registry: { recipes: { 10: [{ result: 10 }] } } }, [1]);
fix('factory registry isolation', { kind: 'isolation', id: 10, registry: { recipes: { 10: [{ result: 10 }] } } }, 1);
fix('query uses corrected outShape consumption', { project: 'counts', args: [10, null, 1, {}], registry: { recipes: { 10: [{ result: { id: 10, count: 1 }, inShape: [[1]], outShape: [[2]] }] } } }, []);
const referenceInstall = bot => injectCraft(bot);
const runReference = s => encode(exercise(createReference, referenceInstall, referenceWindows, ReferenceItem, registry, structuredClone(s)));
const expected = cases.map(({ s }) => runReference(s));
for (const { label, s, expected } of fixes) assert.notDeepEqual(runReference(s), encode(expected), `Upstream defect: ${label}`);
if (process.argv.includes('--reference-only')) {
  console.log(`Reference ready: ${cases.length} parity scenarios, ${fixes.length} source defects. Guest not loaded.`);
} else {
  const { build } = plugin('esbuild');
  const { getQuickJS } = plugin('quickjs-emscripten');
  const bundle = await build({ stdin: { contents: `import {createRecipeFactory,installRecipeQueries} from './bb-plugin/scripting/recipes.mjs'; import {createItemClass} from './bb-plugin/scripting/items.mjs'; import {createWindowFactory} from './bb-plugin/scripting/windows.mjs'; globalThis.registry=${JSON.stringify(selected)}; globalThis.createFactory=createRecipeFactory; globalThis.install=installRecipeQueries; globalThis.Item=createItemClass(registry); globalThis.windows=createWindowFactory(Item);`, resolveDir: root }, bundle: true, write: false, platform: 'browser', format: 'iife', target: 'es2022', nodePaths: [resolve(pluginRoot, 'node_modules'), resolve(referenceRoot, 'node_modules')] });
  const vm = (await getQuickJS()).newContext();
  vm.runtime.setMemoryLimit(64 * 1024 * 1024);
  let deadline;
  vm.runtime.setInterruptHandler(() => Date.now() > deadline);
  const evaluate = code => {
    deadline = Date.now() + 10000;
    const result = vm.evalCode(code), handle = result.error ?? result.value;
    const value = vm.dump(handle); handle.dispose();
    if (result.error) throw new Error(JSON.stringify(value));
    return value;
  };
  const literal = value => value === undefined ? 'undefined' : typeof value === 'number' && !Number.isFinite(value) ? String(value)
    : Array.isArray(value) ? `[${value.map(literal).join(',')}]`
      : value && typeof value === 'object' ? `{${Object.entries(value).map(([k, v]) => `${JSON.stringify(k)}:${literal(v)}`).join(',')}}` : JSON.stringify(value);
  try {
    evaluate(bundle.outputFiles[0].text);
    evaluate(`globalThis.exercise=${exercise}; globalThis.encode=${encode};`);
    for (let start = 0; start < cases.length; start += 100) {
      const batch = cases.slice(start, start + 100);
      const actual = JSON.parse(evaluate(`JSON.stringify(${literal(batch.map(c => c.s))}.map(s=>encode(exercise(createFactory,install,windows,Item,registry,s))))`));
      batch.forEach(({ label }, i) => assert.deepEqual(actual[i], expected[start + i], label));
    }
    for (const { label, s, expected } of fixes) {
      const actual = JSON.parse(evaluate(`JSON.stringify(encode(exercise(createFactory,install,windows,Item,registry,${literal(s)})))`));
      assert.deepEqual(actual, encode(expected), label);
    }
    console.log(`PASS: ${cases.length} differential scenarios (all 1470 recipes / 782 result IDs), ${fixes.length} explicit defect corrections in bundled QuickJS at 64 MiB. No native inventory, crafting or live reference conformance claimed.`);
  } finally { vm.dispose(); }
}
