// Source-derived fixtures precede decoder implementation. No client/server.
// Run with MINEFLAYER_REFERENCE_ROOT and MINEFLAYER_PLUGIN_ROOT to reuse
// installed packages read-only from another checkout (or use local installs).
// Schema corrections follow generated PC1.21.1 sources: PotionContents,
// FoodProperties, Filterable, BlockPredicate, Instrument, TrimMaterial,
// StatePropertiesPredicate. NBT Unicode follows StringTag.write/DataOutput UTF.
// --reference-only validates fixture roundtrips without loading our decoder.
import { createRequire, Module } from 'node:module';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? fileURLToPath(new URL('.', import.meta.url)));
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? fileURLToPath(new URL('../../bb-plugin', import.meta.url)));
const require = createRequire(resolve(referenceRoot, 'package.json'));
const plugin = createRequire(resolve(pluginRoot, 'package.json'));
for (const [name, version] of Object.entries({ 'minecraft-data': '3.117.0', 'minecraft-protocol': '1.68.0', protodef: '1.19.0', 'prismarine-nbt': '2.8.0' })) assert.equal(require(`${name}/package.json`).version, version);
const mcData = require('minecraft-data')('1.21.1');
const { createSerializer } = require('minecraft-protocol/src/transforms/serializer');
const stock = createSerializer({ state: 'play', isServer: true, version: '1.21.1' }).proto;
const types = structuredClone(mcData.protocol.types);
const fields = types.SlotComponent[1][1].type[1].fields;
fields.potion_contents[1] = fields.potion_contents[1].filter(f => f.name !== 'customName');
fields.food[1].find(f => f.name === 'usingConvertsTo').type = ['option', 'Slot'];
fields.food[1].find(f => f.name === 'effects').type[1].type[1].find(f => f.name === 'effect').type = 'ItemPotionEffect';
types.ItemWrittenBookPage[1].find(f => f.name === 'filteredContent').type = ['option', 'anonymousNbt'];
types.ItemBlockPredicate[1].find(f => f.name === 'nbt').type = ['option', 'anonymousNbt'];
types.InstrumentData[1] = types.InstrumentData[1].filter(f => f.name !== 'description');
types.InstrumentData[1].find(f => f.name === 'useDuration').type = 'varint';
types.ArmorTrimMaterial[1].splice(2, 0, { name: 'itemModelIndex', type: 'f32' });
types.ArmorTrimMaterial[1].find(f => f.name === 'overrideArmorAssets').type[1].type[1].find(f => f.name === 'key').type = 'varint';
for (const f of types.ItemBlockProperty[1].find(f => f.name === 'value').type[1].fields.false[1]) f.type = ['option', 'string'];
const { ProtoDefCompiler } = require('protodef').Compiler;
const compiler = new ProtoDefCompiler();
compiler.addTypes(require('minecraft-protocol/src/datatypes/compiler-minecraft'));
// minecraft-protocol 1.68.0 skips inline holder zeros, leaving allocUnsafe bytes uninitialized.
compiler.writeCompiler.addTypes({
  registryEntryHolder: ['parametrizable', (compiler, opts) => compiler.wrapCode(`
    if (value.${opts.baseName} != null) {
      offset = ${compiler.callType(`value.${opts.baseName} + 1`, 'varint')}
    } else if (value.${opts.otherwise.name}) {
      buffer[offset++] = 0
      offset = ${compiler.callType(`value.${opts.otherwise.name}`, opts.otherwise.type)}
    } else {
      throw new Error('registryEntryHolder requires ${opts.baseName} or ${opts.otherwise.name}')
    }
    return offset
  `)],
});
compiler.addTypesToCompile(types);
require('prismarine-nbt').addTypesToCompiler('big', compiler);
const corrected = compiler.compileProtoDefSync();
const Item = require('prismarine-item')('1.21.1');
const vi = value => {
  const out = []; let n = value >>> 0;
  do { let b = n & 127; n >>>= 7; if (n) b |= 128; out.push(b); } while (n);
  return Buffer.from(out);
};
const cat = (...xs) => Buffer.concat(xs.map(x => typeof x === 'number' ? Buffer.from([x]) : x));
const f32 = value => { const b = Buffer.alloc(4); b.writeFloatBE(value); return b; };
const str = value => cat(vi(Buffer.byteLength(value)), Buffer.from(value));
const nbtStr = value => { const text = Buffer.from(value); const len = Buffer.alloc(2); len.writeUInt16BE(text.length); return cat(8, len, text); };
const slot = (name, added = [], removed = []) => cat(1, vi(mcData.itemsByName[name].id), vi(added.length), vi(removed.length), ...added.flatMap(([id, data]) => [vi(id), data]), ...removed.map(vi));
const details = cat(vi(2), vi(600), 0, 1, 1, 0);
const effect = cat(vi(1), details);
const food = (conversion, effects) => cat(vi(4), f32(0.3), 0, f32(1.6), conversion, vi(effects.length), ...effects);
const fixtures = [
  ['empty', cat(0)],
  ['default item', slot('diamond_sword')],
  ['damage and removal', slot('diamond_sword', [[3, vi(42)]], [5, 9])],
  ['custom name + lore', slot('stone', [[5, nbtStr('Name')], [7, cat(vi(1), nbtStr('Lore'))]])],
  ['potion no trailing field', slot('potion', [[31, cat(1, vi(5), 0, 0)]])],
  ['potion nested effect', slot('potion', [[31, cat(0, 0, 1, effect)]])],
  ['food no conversion', slot('apple', [[20, food(cat(0), [])]])],
  ['food bowl conversion', slot('mushroom_stew', [[20, food(cat(1, slot('bowl')), [])]])],
  ['food effect', slot('apple', [[20, food(cat(0), [cat(effect, f32(0.5))])]])],
  ['filtered book page', slot('written_book', [[34, cat(str('Title'), 0, str('Author'), 0, 1, nbtStr('Raw'), 1, nbtStr('Filtered'), 1)]])],
  ['adventure NBT predicate', slot('stone', [[11, cat(1, 0, 0, 1, 10, 0, 1)]])],
  ['nested shulker potion', slot('shulker_box', [[52, cat(2, 0, slot('potion', [[31, cat(1, vi(5), 0, 0)]]))]])],
  ['holder inline instrument', slot('goat_horn', [[40, cat(0, 0, str('minecraft:test_sound'), 0, vi(140), f32(256))]])],
  ['inline trim material', slot('diamond_chestplate', [[35, cat(0, str('test'), vi(mcData.itemsByName.diamond.id), f32(0.5), 1, vi(3), str('override'), nbtStr('Description'), 1, 1)]])],
  ['ranged block property', slot('stone', [[11, cat(1, 0, 1, 1, str('age'), 0, 0, 1, str('4'), 0, 1)]])],
  ['holder numeric instrument', slot('goat_horn', [[40, vi(123 + 1)]])],
];
const decode = (proto, bytes) => {
  const result = proto.parsePacketBuffer('Slot', bytes);
  assert.equal(result.metadata.size, bytes.length, 'consume full Slot');
  return result.data;
};
// NBT numeric preservation: use the actual installed NBT decoder, not guessed JSON behavior.
const nbt = { type: 'compound', value: {
  max: { type: 'long', value: [0x7fffffff, -1] },
  min: { type: 'long', value: [-2147483648, 0] },
  longs: { type: 'longArray', value: [[1, 2], [-1, -1]] },
  bytes: { type: 'byteArray', value: [-1, 0, 127] },
  infinite: { type: 'double', value: Infinity },
  negativeZero: { type: 'float', value: -0 },
} };
const bytes = corrected.createPacketBuffer('Slot', { itemCount: 1, itemId: mcData.itemsByName.stone.id, addedComponentCount: 1, removedComponentCount: 0, components: [{ type: 'custom_data', data: nbt }], removeComponents: [] });
fixtures.push(['NBT numeric values', bytes]);

// Cover every component codec branch with source-shaped bytes; complex variants
// above cover nested and optional cases. IDs match DataComponents registration.
const i32 = n => { const b = Buffer.alloc(4); b.writeInt32BE(n); return b; };
const f64 = n => { const b = Buffer.alloc(8); b.writeDoubleBE(n); return b; };
const emptyCompound = cat(10, 0);
const explosion = cat(1, 1, i32(0x123456), 0, 1, 0);
const componentPayloads = {
  0: emptyCompound, 1: vi(16), 2: vi(100), 3: vi(2), 4: cat(1),
  5: nbtStr('Name'), 6: nbtStr('Item name'), 7: cat(1, nbtStr('Lore')), 8: vi(2),
  9: cat(2, vi(300), 3, vi(8), 1, 1),
  10: cat(1, 1, 0, str('minecraft:logs'), 1, 1, str('axis'), 1, str('y'), 0, 1),
  11: cat(1, 1, 3, vi(1), vi(2), 0, 0, 1),
  12: cat(1, vi(1), str('minecraft:modifier'), f64(2.5), 2, 1, 1),
  13: vi(123), 14: cat(), 15: cat(), 16: vi(3), 17: cat(), 18: cat(1),
  19: emptyCompound, 20: food(cat(0), []), 21: cat(),
  22: cat(1, 3, 1, 2, 1, f32(4), 1, 1, f32(1), 1),
  23: cat(1, vi(300), 5, 0), 24: cat(i32(0xabcdef), 1), 25: i32(0xabcdef),
  26: vi(23), 27: emptyCompound, 28: vi(1),
  29: cat(1, slot('arrow')), 30: cat(1, slot('stone')), 31: cat(0, 0, 0),
  32: cat(1, vi(1), vi(100)), 33: cat(1, str('raw'), 1, str('filtered')),
  34: cat(str('Title'), 0, str('Author'), 0, 1, nbtStr('Page'), 0, 1),
  35: cat(1, 1, 1), 36: emptyCompound, 37: emptyCompound, 38: emptyCompound,
  39: emptyCompound, 40: vi(2), 41: vi(2),
  42: cat(0, str('minecraft:cat'), 1),
  43: cat(9, 8, i32(0)),
  44: cat(1, str('minecraft:overworld'), Buffer.alloc(8), 1),
  45: explosion, 46: cat(vi(3), 1, explosion),
  47: cat(1, str('Name'), 1, Buffer.from('00112233445566778899aabbccddeeff','hex'), 1, str('textures'), str('value'), 0),
  48: str('minecraft:ambient.cave'),
  49: cat(1, 0, str('minecraft:base'), str('block.minecraft.banner.base'), 1),
  50: vi(15), 51: cat(4, ...Array(4).fill(vi(mcData.itemsByName.brick.id))),
  52: cat(2, slot('stone'), 0), 53: cat(1, str('facing'), str('north')),
  54: cat(1, emptyCompound, vi(0), vi(600)), 55: nbtStr('key'), 56: emptyCompound,
};
for (const [type, payload] of Object.entries(componentPayloads)) {
  fixtures.push([`component:${types.SlotComponentType[1].mappings[type]}`, slot('stone', [[Number(type), payload]])]);
}
// StringTag.write uses DataOutput.writeUTF: modified UTF-8, not Node UTF-8.
// Independent encoded constants include NUL and a surrogate pair (emoji).
const unicodeBytes = slot('stone', [[5, Buffer.from('080008c080eda0bdedb880', 'hex')]]);

// Invalid inputs are authored before implementation, alongside the byte fixtures.
const malformed = [
  ['trailing bytes', cat(slot('stone'), 0)],
  ['truncated item', slot('stone').subarray(0, 2)],
  ['unknown addition with no payload', slot('stone', [[999, Buffer.alloc(0)]])],
  ['unknown removal', slot('stone', [], [999])],
  ['unknown item', cat(1, vi(99999), 0, 0)],
  ['negative count', cat(vi(-1), 1, 0, 0)],
  ['huge component count', cat(1, 1, vi(0x7fffffff), 0)],
  ['huge nested array', slot('shulker_box', [[52, vi(0x7fffffff)]])],
  ['negative NBT array', slot('stone', [[0, cat(7, Buffer.from('ffffffff', 'hex'))]])],
  ['unterminated compound', slot('stone', [[0, cat(10)]])],
  ['unknown NBT tag', slot('stone', [[0, cat(13)]])],
];
let deep = slot('stone');
for (let i = 0; i < 150; i++) deep = slot('shulker_box', [[52, cat(1, deep)]]);
malformed.push(['recursive slots', deep]);
let hidden = cat(0, 0, 0, 0, 0, 0);
for (let i = 0; i < 150; i++) hidden = cat(0, 0, 0, 0, 0, 1, hidden);
malformed.push(['recursive hidden effects', slot('potion', [[31, cat(0, 0, 1, 1, hidden)]])]);
const registryMaps = {
  items: mcData.itemsArray.map(item => `minecraft:${item.name}`),
  components: Object.values(types.SlotComponentType[1].mappings).map(name => `minecraft:${name}`),
};
// A compound key may resemble a transport tag or an Object prototype property.
const oddNbt = { type: 'compound', value: Object.fromEntries([
  ['__proto__', { type: 'string', value: 'own data' }],
  ['constructor', { type: 'string', value: 'ordinary data' }],
  ['$itemWire', { type: 'string', value: 'not a tag' }],
]) };
const oddBytes = corrected.createPacketBuffer('Slot', { itemCount: 1, itemId: 1, addedComponentCount: 1, removedComponentCount: 0, components: [{ type: 'custom_data', data: oddNbt }], removeComponents: [] });

if (process.argv.includes('--reference-only')) {
  for (const [name, bytes] of fixtures) {
    const result = decode(corrected, bytes);
    assert.deepEqual(corrected.createPacketBuffer('Slot', result), bytes, name);
  }
  console.log(`Source-derived/reference fixtures ready: ${fixtures.length}; malformed inputs: ${malformed.length}. No implementation evaluated.`);
} else {
  const built = await plugin('esbuild').build({
    entryPoints: [fileURLToPath(new URL('../../bb-plugin/scripting/item-wire.mjs', import.meta.url))],
    bundle: true, packages: 'external', platform: 'node', format: 'cjs', write: false,
  });
  const loaded = new Module(resolve(referenceRoot, 'item-wire-check.cjs'));
  loaded.paths = require.resolve.paths('minecraft-data');
  loaded._compile(built.outputFiles[0].text, resolve(referenceRoot, 'item-wire-check.cjs'));
  const { createItemDecoder, createItemEncoder, encodeItemTransport, decodeItemTransport } = loaded.exports;
  const actualEncode = process.argv.includes("--encode") ? createItemEncoder(registryMaps) : null;
  const actualDecode = createItemDecoder(registryMaps);
  assert.equal(createItemDecoder(structuredClone(registryMaps)), actualDecode, 'reuse identical registry fingerprint');
  const vm = (await plugin('quickjs-emscripten').getQuickJS()).newContext();
  const evaluate = code => {
    const result = vm.evalCode(code);
    if (result.error) { const e = vm.dump(result.error); result.error.dispose(); throw new Error(JSON.stringify(e)); }
    const value = vm.dump(result.value); result.value.dispose(); return value;
  };
  try {
    evaluate(`globalThis.revive = (${decodeItemTransport.toString()});`);
    if (actualEncode) {
      const valid = actualDecode(fixtures[1][1].toString('base64'));
      for (const changed of [{ itemCount: -1 }, { itemId: -1 }, { addedComponentCount: 999 },
        { components: [{ type: 'unknown_component', data: {} }], addedComponentCount: 1 }])
        assert.throws(() => actualEncode({ ...valid, ...changed }));
      const cyclic = { ...valid }; cyclic.components = [cyclic];
      assert.throws(() => actualEncode(cyclic));
      assert.throws(() => actualEncode({ ...valid, extra: 'x'.repeat(3 * 1024 * 1024) }));
    }
    let upstreamMatches = 0;
    for (const [name, bytes] of fixtures) {
      const result = actualDecode(bytes.toString('base64'));
      assert.deepEqual(result, decode(corrected, bytes), name);
      if (actualEncode) assert.deepEqual(Buffer.from(actualEncode(result), "base64"), bytes, `${name}: native encoding bytes`);
      try { assert.deepEqual(result, decode(stock, bytes)); upstreamMatches++; } catch {}
      const transport = encodeItemTransport(result);
      const revived = decodeItemTransport(JSON.parse(JSON.stringify(transport)));
      assert.deepEqual(corrected.createPacketBuffer('Slot', revived), bytes, `${name}: host transport`);
      const roundtrip = evaluate(`JSON.stringify((${encodeItemTransport.toString()})(revive(${JSON.stringify(transport)})))`);
      assert.deepEqual(JSON.parse(roundtrip), transport, `${name}: QuickJS transport`);
      if (name === 'NBT numeric values') {
        assert.equal(evaluate(`String(revive(${JSON.stringify(transport)}).components[0].data.value.max.value)`), '9223372036854775807');
        assert.equal(evaluate(`String(revive(${JSON.stringify(transport)}).components[0].data.value.min.value + 1n)`), '-9223372036854775807');
      }
    }
    assert.equal(actualDecode(unicodeBytes.toString('base64')).components[0].data.value, '\0😀', 'native modified UTF-8');
    if (actualEncode) assert.equal(actualEncode(actualDecode(unicodeBytes.toString('base64'))), unicodeBytes.toString('base64'), 'encode native modified UTF-8');
    const odd = actualDecode(oddBytes.toString('base64'));
    assert.equal(odd.components[0].data.value.__proto__.value, 'own data');
    const oddRevived = decodeItemTransport(JSON.parse(JSON.stringify(encodeItemTransport(odd))));
    assert(Object.hasOwn(oddRevived.components[0].data.value, '__proto__'));
    assert.deepEqual(corrected.createPacketBuffer('Slot', oddRevived), oddBytes);
    if (actualEncode) assert.equal(actualEncode(oddRevived), oddBytes.toString('base64'), 'encode prototype key as own data');
    for (const [name, bytes] of malformed) {
      const expected = name.startsWith('recursive') ? /structure exceeds bounds/
        : name.startsWith('huge') || name === 'negative NBT array' ? /array count exceeds bounds/
          : name.startsWith('unknown') && !name.includes('NBT') ? /Unsupported native/
            : undefined;
      assert.throws(() => actualDecode(bytes.toString('base64')), expected, name);
    }
    for (const invalid of ['', 'AA', 'AB==', 'AA==\n', '@@==', 4, 'A'.repeat(3 * 1024 * 1024)]) assert.throws(() => actualDecode(invalid));
    // Component and item IDs are remapped by registry name, at every nested Slot.
    const shifted = { items: [], components: [] };
    for (const [id, name] of Object.entries(registryMaps.items)) shifted.items[Number(id) + 2000] = name;
    for (const [id, name] of Object.entries(registryMaps.components)) shifted.components[Number(id) + 1000] = name;
    const shiftedBytes = cat(1, vi(mcData.itemsByName.shulker_box.id + 2000), 1, 1, vi(1052), 1, 1, vi(mcData.itemsByName.diamond_sword.id + 2000), 1, 0, vi(1003), vi(42), vi(1005));
    const remapped = createItemDecoder(shifted)(shiftedBytes.toString('base64'));
    if (actualEncode) assert.equal(createItemEncoder(shifted)(remapped), shiftedBytes.toString('base64'), 'reverse registry remapping');
    assert.equal(remapped.itemId, mcData.itemsByName.shulker_box.id);
    assert.equal(remapped.components[0].data.contents[0].itemId, mcData.itemsByName.diamond_sword.id);
    assert.equal(remapped.components[0].data.contents[0].components[0].data, 42);
    assert.equal(remapped.removeComponents[0].type, 'custom_name');
    const snapshot = structuredClone(registryMaps);
    const stable = createItemDecoder(snapshot);
    snapshot.items[1] = 'minecraft:dirt';
    assert.equal(stable(slot('stone').toString('base64')).itemId, 1);
    for (const maps of [{}, { ...registryMaps, items: [null, 'stone'] }, { ...registryMaps, items: [null, 'minecraft:stone', 'minecraft:stone'] }]) assert.throws(() => createItemDecoder(maps));
    console.log(`PASS: ${fixtures.length} source-derived/NBT fixtures; ${upstreamMatches} stock decoder matches; ${malformed.length} malformed-wire rejections; registry remapping, prototype keys, and actual QuickJS transport. No Java encoder or live world tested.`);
  } finally { vm.dispose(); }
}
