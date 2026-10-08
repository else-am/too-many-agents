import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../..', import.meta.url));
const reference = createRequire(new URL('./package.json', import.meta.url));
const plugin = createRequire(new URL('../../bb-plugin/package.json', import.meta.url));
const minecraftData = reference('minecraft-data');
assert.equal(reference('minecraft-data/package.json').version, '3.117.0');
const native = minecraftData('1.21.1');
const names = Object.keys(minecraftData.versionsByMinecraftVersion.pc);
const operators = ['>=', '>', '<', '<=', '=='];
function inspect(registry, names) {
  const links = [
    ['blocksArray', 'blocks', 'id'], ['blocksArray', 'blocksByName', 'name'],
    ['biomesArray', 'biomes', 'id'], ['biomesArray', 'biomesByName', 'name'],
    ['itemsArray', 'items', 'id'], ['itemsArray', 'itemsByName', 'name'],
    ['foodsArray', 'foods', 'id'], ['foodsArray', 'foodsByName', 'name'],
    ['instrumentsArray', 'instruments', 'id'],
    ['enchantmentsArray', 'enchantments', 'id'], ['enchantmentsArray', 'enchantmentsByName', 'name'],
    ['entitiesArray', 'entities', 'id'], ['entitiesArray', 'entitiesByName', 'name'],
    ['windowsArray', 'windows', 'id'], ['windowsArray', 'windowsByName', 'name'],
    ['effectsArray', 'effects', 'id'], ['effectsArray', 'effectsByName', 'name'],
    ['attributesArray', 'attributes', 'resource'], ['attributesArray', 'attributesByName', 'name'],
    ['particlesArray', 'particles', 'id'], ['particlesArray', 'particlesByName', 'name'],
    ['blockLootArray', 'blockLoot', 'block'], ['entityLootArray', 'entityLoot', 'entity'],
    ['mapIconsArray', 'mapIcons', 'id'], ['mapIconsArray', 'mapIconsByName', 'name'],
    ['soundsArray', 'sounds', 'id'], ['soundsArray', 'soundsByName', 'name'],
  ];
  let linked = 0;
  for (const [array, index, field] of links) for (const value of registry[array] ?? []) {
    if (registry[index][value[field]] !== value) throw new Error(`Lost shared registry entry ${index}:${value[field]}`);
    linked++;
  }
  let states = 0;
  for (const block of registry.blocksArray) for (let id = block.minStateId; id <= block.maxStateId; id++) {
    if (registry.blocksByStateId[id] !== block) throw new Error(`Block state ${id} differs`);
    states++;
  }
  for (const [field, type] of [['mobs', 'mob'], ['objects', 'object']]) {
    const expected = registry.entitiesArray.filter(entity => entity.type === type);
    if (Object.keys(registry[field]).length !== expected.length || expected.some(entity => registry[field][entity.id] !== entity))
      throw new Error(`Entity type index ${field} differs`);
  }
  return { linked, states, version: JSON.parse(JSON.stringify(registry.version)),
    comparisons: names.map(name => [...['>=', '>', '<', '<=', '=='].map(op => registry.version[op](name)), registry.isOlderThan(name), registry.isNewerOrEqualTo(name)]) };
}
const expected = inspect(native, names);
assert(expected.states > 20000 && expected.linked > 5000);
for (const operator of operators) assert.throws(() => native.version[operator]('not-a-version'), RangeError);
console.log(JSON.stringify({ phase: 'reference', linked: expected.linked, states: expected.states, versions: names.length }));
if (process.argv.includes('--reference-only')) process.exit(0);
const { selectRegistryData } = await import('../../bb-plugin/scripting/registry-data.mjs');
const data = selectRegistryData(minecraftData);
const { build } = plugin('esbuild');
const built = await build({ stdin: { contents: `import {createRegistry} from './bb-plugin/scripting/registry.mjs'; globalThis.registry=createRegistry(${JSON.stringify(data)}); globalThis.result=(${inspect.toString()})(registry,${JSON.stringify(names)});`, resolveDir: root }, bundle: true, write: false, platform: 'browser', format: 'iife', metafile: true });
assert(Object.keys(built.metafile.inputs).every(path => !path.includes('node_modules/minecraft-data')));
assert(Object.values(built.metafile.outputs).every(output => output.imports.length === 0));
const vm = (await plugin('quickjs-emscripten').getQuickJS()).newContext();
vm.runtime.setMemoryLimit(64 * 1024 * 1024); vm.runtime.setMaxStackSize(512 * 1024);
const deadline = Date.now() + 10000;
vm.runtime.setInterruptHandler(() => Date.now() > deadline);
function evaluate(code) {
  const result = vm.evalCode(code);
  if (result.error) { const failure = vm.dump(result.error); result.error.dispose(); throw new Error(JSON.stringify(failure)); }
  const value = vm.dump(result.value); result.value.dispose(); return value;
}
try {
  evaluate(built.outputFiles[0].text);
  assert.deepEqual(evaluate('result'), expected);
  for (const [key, value] of Object.entries(native)) {
    if (typeof value === 'function' || key === 'blocksByStateId') continue;
    assert.equal(evaluate(`JSON.stringify(registry[${JSON.stringify(key)}])`), JSON.stringify(value), key);
  }
  for (const operator of operators) assert.equal(evaluate(`(()=>{try{registry.version[${JSON.stringify(operator)}]('not-a-version');return false}catch(e){return e instanceof RangeError}})()`), true);
  const memory = vm.runtime.computeMemoryUsage(); const usage = vm.dump(memory); memory.dispose();
  console.log(JSON.stringify({ phase: 'adapter', allStaticKeys: Object.keys(native).filter(key => typeof native[key] !== 'function').length, linked: expected.linked, states: expected.states, versions: names.length, bundleBytes: built.outputFiles[0].contents.length, memoryUsed: usage.memory_used_size, imports: 0 }));
} finally { vm.dispose(); }
