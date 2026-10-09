// Library-only differential check; no Minecraft server, world, or EULA required.
// Dependencies may be reused read-only from another checkout:
// MINEFLAYER_REFERENCE_ROOT=/path/to/tools/mineflayer-reference \
// MINEFLAYER_PLUGIN_ROOT=/path/to/bb-plugin node tools/mineflayer-reference/blocks.mjs
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? fileURLToPath(new URL('.', import.meta.url)));
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? fileURLToPath(new URL('../../bb-plugin', import.meta.url)));
const reference = createRequire(resolve(referenceRoot, 'package.json'));
const plugin = createRequire(resolve(pluginRoot, 'package.json'));
for (const [name, version] of Object.entries({ mineflayer: '4.39.0', 'prismarine-block': '1.23.0', 'prismarine-biome': '1.4.0', 'prismarine-chat': '1.13.0', 'prismarine-nbt': '2.8.0', 'minecraft-data': '3.117.0', vec3: '0.1.10' })) {
  assert.equal(reference(resolve(referenceRoot, 'node_modules', name, 'package.json')).version, version, `Pinned ${name} reference`);
}
const registry = reference('minecraft-data')('1.21.1');
// This is deliberately JSON data, exactly as a build-time guest bundle receives it.
const data = JSON.parse(JSON.stringify({
  blocksArray: registry.blocksArray, biomesArray: registry.biomesArray,
  blockCollisionShapes: registry.blockCollisionShapes, materials: registry.materials,
  effectsByName: registry.effectsByName, enchantmentsByName: registry.enchantmentsByName,
  language: registry.language,
}));
const ReferenceBlock = reference('prismarine-block')('1.21.1');
const { Vec3 } = reference('vec3');

// Authored before the guest implementation. Every expected result comes from
// the installed upstream class, including undefined values and upstream quirks.
// Fields below were deliberately removed from the reviewed Block surface and are not compared.
const removedBlockFields = new Set(['_properties', 'computedStates', 'metadata', 'harvestTools', 'missingStateShape']);
function exercise(Block, Vec3, scenario, digTime = (block, ...args) => block.digTime(...args)) {
  const capture = fn => {
    try { return { value: fn() }; }
    catch (error) { return { error: error.name, message: error instanceof TypeError || error instanceof SyntaxError ? undefined : error.message }; }
  };
  const describe = block => ({
    own: Object.fromEntries(Object.getOwnPropertyNames(block).filter(key => !removedBlockFields.has(key)).map(key => [key, block[key]])),
    properties: block.getProperties(), blockEntity: block.blockEntity,
    signText: block.getSignText?.(), instance: block instanceof Block,
  });
  if (scenario.kind === 'state') return describe(Block.fromStateId(scenario.state, scenario.biome));
  if (scenario.kind === 'constructor') return capture(() => describe(new Block(...scenario.args)));
  if (scenario.kind === 'dig') {
    const block = Block.fromStateId(scenario.state, 0);
    return { harvest: block.canHarvest(scenario.tool), time: digTime(block, scenario.tool, ...scenario.args) };
  }
  if (scenario.kind === 'nbt') {
    const block = Block.fromStateId(scenario.state, 0);
    block.entity = scenario.entity === undefined ? undefined : JSON.parse(JSON.stringify(scenario.entity));
    return capture(() => ({ blockEntity: block.blockEntity, sign: block.getSignText?.() }));
  }
  if (scenario.kind === 'sign') {
    const block = Block.fromStateId(scenario.state, 0);
    const before = describe(block);
    if (scenario.entity) block.entity = scenario.entity === undefined ? undefined : JSON.parse(JSON.stringify(scenario.entity));
    const outcome = capture(() => {
      if (scenario.deprecated) block.signText = scenario.front;
      else block.setSignText(scenario.front, scenario.back);
      if (scenario.object) block.setSignText([{ toJSON: () => scenario.object }]);
      return { text: block.getSignText(), deprecated: block.signText, entity: block.entity, simplified: block.blockEntity };
    });
    return { before, outcome };
  }
  throw new Error(`Unknown scenario ${scenario.kind}`);
}

// Preserve non-JSON values in both VMs; JSON alone would hide null/undefined,
// NaN/Infinity, and the actual tri-state canHarvest return value.
function encode(value) {
  if (value === undefined) return ['undefined'];
  if (typeof value === 'function') return ['function'];
  if (typeof value === 'number' && !Number.isFinite(value)) return ['number', String(value)];
  if (Array.isArray(value)) return ['array', value.map(encode)];
  if (value && typeof value === 'object') return ['object', Object.keys(value).sort().map(key => [key, encode(value[key])])];
  return value;
}
const cases = [];
const add = (label, scenario) => cases.push({ label, scenario });
const state = name => registry.blocksByName[name].defaultState;
for (const block of registry.blocksArray) {
  for (let id = block.minStateId; id <= block.maxStateId; id++) add(`${block.name}:${id}`, { kind: 'state', state: id, biome: 0 });
}
for (const biome of [...registry.biomesArray.map(b => b.id), -1, 100000, null, undefined]) {
  add(`biome:${biome}`, { kind: 'state', state: state('stone'), biome });
}
for (const id of [-1, 100000, null, undefined, NaN]) add(`unknown state:${id}`, { kind: 'state', state: id });
for (const name of ['air', 'stone', 'water', 'lava', 'oak_slab', 'oak_stairs', 'oak_fence', 'oak_sign', 'oak_wall_sign', 'oak_hanging_sign']) {
  const block = registry.blocksByName[name];
  for (const metadata of [undefined, null, -1, 0, 1, block.maxStateId - block.minStateId + 10]) {
    add(`constructor:${name}:${metadata}`, { kind: 'constructor', args: [block.id, 1, metadata] });
  }
}
for (const args of [[999999, 1, 0], [undefined, 1, 0], [0, 1, 0, state('oak_stairs')]]) add(`constructor:${args}`, { kind: 'constructor', args });
const effects = (...entries) => Object.fromEntries(entries.map(([name, amplifier]) => [registry.effectsByName[name].id, { amplifier }]));
const conditions = [
  [false, false, false], [true, false, false], [false, true, false], [false, false, true], [false, true, true],
  [false, false, false, [{ name: 'efficiency', lvl: 5 }]],
  [false, true, true, [{ name: 'minecraft:efficiency', lvl: 3 }, { name: 'aqua_affinity', lvl: 1 }]],
  [false, false, false, [], effects(['Haste', 1])],
  [false, false, false, [], effects(['Haste', 0], ['ConduitPower', 2])],
  ...[0, 1, 2, 3, 4].map(level => [false, false, false, [], effects(['MiningFatigue', level])]),
  [false, true, true, [{ name: 'efficiency', lvl: 4 }], effects(['Haste', 1], ['MiningFatigue', 0])],
];
for (const name of ['air', 'stone', 'obsidian', 'bedrock', 'water', 'lava', 'dirt', 'oak_log', 'cobweb', 'white_wool', 'oak_leaves', 'diamond_ore', 'oak_slab']) {
  for (const tool of [null, undefined, 0, -1, ...['wooden_pickaxe', 'stone_pickaxe', 'iron_pickaxe', 'diamond_pickaxe', 'netherite_pickaxe', 'golden_pickaxe', 'diamond_axe', 'diamond_shovel', 'shears', 'diamond_sword'].map(name => registry.itemsByName[name].id)]) {
    for (const [index, args] of conditions.entries()) add(`dig:${name}:${tool}:${index}`, { kind: 'dig', state: state(name), tool, args });
  }
}
for (const name of ['oak_sign', 'oak_wall_sign', 'oak_hanging_sign', 'oak_wall_hanging_sign']) {
  for (const [index, text] of [
    {}, { front: 'One\nTwo\n\nFour', back: 'Back' }, { front: ['literal', { text: 'Hello', extra: [{ text: ' world', color: 'red' }] }, { translate: 'chat.type.text', with: ['Alex', 'Hello'] }, { translate: 'missing', fallback: '%2$s / %1$s %%', with: ['a', 'b'] }] },
    { front: [{ selector: '@p' }, { keybind: 'key.jump' }, { score: { name: 'Alex', objective: 'points', value: 99 } }, ['§agreen', { text: ' 😀\u0000' }]] },
    { front: 'Legacy\n', deprecated: true }, { object: { text: 'toJSON value' } }, { back: [], front: [] },
    { front: [{ text: 10 }, { '': 1.234567 }, { '': 'empty key' }, { text: 'x'.repeat(5000) }] },
    { front: [{ translate: 'missing', with: 'invalid' }] }, { front: [{ text: 'invalid', extra: {} }] },
    { front: [{ text: 'bad click', clickEvent: {} }] }, { front: [{ text: 'bad hover', hoverEvent: {} }] },
    { front: [null, true] }, { front: 42 },
  ].entries()) add(`sign:${name}:${index}`, { kind: 'sign', state: state(name), ...text });
}
const stringTag = value => ({ type: 'string', value });
const entity = { type: 'compound', name: '', value: {
  id: stringTag('minecraft:sign'), is_waxed: { type: 'byte', value: 1 },
  front_text: { type: 'compound', value: { color: stringTag('blue'), has_glowing_text: { type: 'byte', value: 1 }, messages: { type: 'list', value: { type: 'string', value: ['"Raw §atext"', '{"text":"Front"}'] } } } },
  nested: { type: 'list', value: { type: 'compound', value: [{ value: { type: 'long', value: [1, 2] }, bytes: { type: 'byteArray', value: [1, -1] } }] } },
} };
add('existing sign NBT', { kind: 'nbt', state: state('oak_sign'), entity });
add('sign preserves unrelated NBT', { kind: 'sign', state: state('oak_sign'), entity, back: 'New back' });
add('container NBT', { kind: 'nbt', state: state('chest'), entity });
add('missing NBT', { kind: 'nbt', state: state('chest') });

if (process.argv.includes('--reference-only')) {
  for (const { scenario } of cases) encode(exercise(ReferenceBlock, Vec3, scenario));
  console.log(`Reference scenarios ready: ${cases.length}; no guest implementation evaluated.`);
} else {
  const { build } = plugin('esbuild');
  const { getQuickJS } = plugin('quickjs-emscripten');
  const bundle = await build({
    stdin: { contents: `import { createBlockClass } from './bb-plugin/scripting/blocks.mjs'; import { Vec3 } from 'vec3'; const created = createBlockClass(${JSON.stringify(data)}); globalThis.Block = created.Block; globalThis.digTime = created.digTime; globalThis.Vec3 = Vec3;`, resolveDir: fileURLToPath(new URL('../..', import.meta.url)) },
    bundle: true, write: false, platform: 'browser', format: 'iife', target: 'es2022',
    nodePaths: [resolve(referenceRoot, 'node_modules')],
  });
  const vm = (await getQuickJS()).newContext();
  vm.runtime.setMemoryLimit(512 * 1024 * 1024);
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
  // JSON cannot carry undefined/NaN in scenario arguments either.
  const literal = value => {
    if (value === undefined) return 'undefined';
    if (typeof value === 'number' && !Number.isFinite(value)) return String(value);
    if (Array.isArray(value)) return `[${value.map(literal).join(',')}]`;
    if (value && typeof value === 'object') return `{${Object.entries(value).map(([key, value]) => `${JSON.stringify(key)}:${literal(value)}`).join(',')}}`;
    return JSON.stringify(value);
  };
  try {
    evaluate(bundle.outputFiles[0].text);
    evaluate(`globalThis.exercise = ${exercise}; globalThis.encode = ${encode};`);
    for (let start = 0; start < cases.length; start += 250) {
      const batch = cases.slice(start, start + 250);
      const actual = JSON.parse(evaluate(`JSON.stringify(${literal(batch.map(c => c.scenario))}.map(s => encode(exercise(Block, Vec3, s, digTime))))`));
      for (const [index, { label, scenario }] of batch.entries()) {
        assert.deepEqual(actual[index], encode(exercise(ReferenceBlock, Vec3, scenario)), label);
      }
    }
    console.log(`PASS: ${cases.length} differential Block scenarios in bundled QuickJS (prismarine-block 1.23.0, Minecraft 1.21.1). Library behavior only; no world behavior tested.`);
  } finally {
    vm.dispose();
  }
}
