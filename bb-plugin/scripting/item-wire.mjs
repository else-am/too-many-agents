/*!
 * Bounded PC 1.21.1 Item wire decoder. Portions adapted from ProtoDef 1.19.0
 * (MIT, Copyright (c) 2018 ProtoDef-io), prismarine-nbt 2.8.0 (MIT), and
 * minecraft-protocol 1.68.0 (BSD-3-Clause). See item-wire.LICENSE.
 */
import minecraftData from 'minecraft-data';
import protodef from 'protodef';
import nbt from 'prismarine-nbt';
import minecraftTypes from 'minecraft-protocol/src/datatypes/compiler-minecraft.js';

const data = minecraftData('1.21.1');
const guestItemIds = new Map(data.itemsArray.map(item => [`minecraft:${item.name}`, item.id]));
const decoders = new Map();
const MAX_BYTES = 2 * 1024 * 1024;
const MAX_ENTRIES = 65536;
const MAX_READS = 200000;
const MAX_DEPTH = 128;

// registries = {
//   items: ['minecraft:air', 'minecraft:stone', ...],
//   components: ['minecraft:custom_data', 'minecraft:max_stack_size', ...]
// }
// Array indices are native IDs; entries are full resource names or null holes.
// Supply immutable observations from the same world session as the bytes.
// We copy both maps. Unknown names can exist in the maps, but referencing them
// in a Slot fails explicitly. Custom component binary codecs cannot be guessed.
// Additional registry fields (such as references) are ignored.
// Item IDs (including nested Slots, trim ingredients/templates, decorations)
// become minecraft-data guest item IDs. All other registry IDs remain native;
// integration must expose their world-specific name/ID maps separately.
//
// Call once per world mapping. Up to four identical registry fingerprints are
// cached; nothing is compiled per item. Encode Java bytes with the current
// RegistryAccess and NeoForge ConnectionType.OTHER, not NEOFORGE.
export function createItemDecoder (registries) {
  const items = registryEntries(registries?.items, 'items');
  const components = registryEntries(registries?.components, 'components');
  const fingerprint = JSON.stringify([items, components]);
  if (decoders.has(fingerprint)) return decoders.get(fingerprint);
  const itemIds = new Map(items.map(([id, name]) => [id, guestItemIds.get(name)]));
  const types = correctedTypes();
  const knownComponents = new Set(Object.values(types.SlotComponentType[1].mappings));
  const componentNames = new Map(components.filter(([, name]) => name.startsWith('minecraft:') && knownComponents.has(name.slice(10)))
    .map(([id, name]) => [id, name.slice(10)]));
  types.SlotComponentType[1].mappings = Object.fromEntries(componentNames);
  types.itemWireItemId = 'varint';
  types.Slot[1][1].type[1].default[1].find(field => field.name === 'itemId').type = 'itemWireItemId';
  types.ArmorTrimMaterial[1].find(field => field.name === 'ingredientId').type = 'itemWireItemId';
  types.ArmorTrimPattern[1].find(field => field.name === 'templateItemId').type = 'itemWireItemId';
  const componentFields = types.SlotComponent[1][1].type[1].fields;
  componentFields.pot_decorations[1][0].type[1].type = 'itemWireItemId';

  const compiler = new protodef.Compiler.ProtoDefCompiler();
  compiler.addTypes(minecraftTypes);
  compiler.addTypesToCompile(types);
  nbt.addTypesToCompiler('big', compiler);
  // Check lengths before looping, including arrays of zero-byte values.
  compiler.readCompiler.addTypes({
    array: ['parametrizable', (compiler, array) => {
      let code;
      if (array.countType) code = `const { value: count, size: countSize } = ${compiler.callType(array.countType)}\n`;
      else if (array.count !== undefined) code = `const count = ${array.count}\nconst countSize = 0\n`;
      else throw new Error('Array lacks a count');
      code += `if (!Number.isInteger(count) || count < 0 || count > ${MAX_ENTRIES}) throw new Error('Item array count exceeds bounds')\n`;
      code += 'const data = []\nlet size = countSize\n';
      code += `for (let i = 0; i < count; i++) {\nconst elem = ${compiler.callType(array.type, 'offset + size')}\ndata.push(elem.value)\nsize += elem.size\n}\n`;
      return compiler.wrapCode(code + 'return { value: data, size }');
    }],
    registryEntryHolderSet: ['parametrizable', (compiler, opts) => compiler.wrapCode(`
      const { value: n, size: nSize } = ${compiler.callType('varint')}
      if (n < 0 || n > ${MAX_ENTRIES + 1}) throw new Error('Unsupported or oversized holder set')
      if (n === 0) {
        const base = ${compiler.callType(opts.base.type, 'offset + nSize')}
        return { value: { ${opts.base.name}: base.value }, size: base.size + nSize }
      }
      const set = []
      let size = nSize
      for (let i = 0; i < n - 1; i++) {
        const entry = ${compiler.callType(opts.otherwise.type, 'offset + size')}
        set.push(entry.value)
        size += entry.size
      }
      return { value: { ${opts.otherwise.name}: set }, size }
    `)],
  });
  const proto = compiler.compileProtoDefSync();
  const readers = proto.readCtx;
  readers.itemWireItemId = (buffer, offset) => {
    const result = readers.varint(buffer, offset);
    const mapped = itemIds.get(result.value);
    if (mapped === undefined) throw new Error(`Unsupported native item ID ${result.value}`);
    return { value: mapped, size: result.size };
  };
  readers.SlotComponentType = (buffer, offset) => {
    const result = readers.varint(buffer, offset);
    const name = componentNames.get(result.value);
    if (name === undefined) throw new Error(`Unsupported native component ID ${result.value}`);
    return { value: name, size: result.size };
  };
  const readSlot = readers.Slot;
  readers.Slot = (buffer, offset) => {
    const count = readers.varint(buffer, offset).value;
    if (count < 0 || count > MAX_ENTRIES) throw new Error('Item count exceeds bounds');
    return readSlot(buffer, offset);
  };
  // StringTag.write and NbtIo compound keys use DataOutput.writeUTF (MUTF-8).
  // Normal UTF-8 decoding corrupts NUL and UTF-16 surrogate code units.
  readers.shortString = (buffer, offset) => {
    if (offset + 2 > buffer.length) throw new Error('Truncated NBT string');
    const length = buffer.readUInt16BE(offset);
    const end = offset + 2 + length;
    if (end > buffer.length) throw new Error('Truncated NBT string');
    const units = [];
    for (let cursor = offset + 2; cursor < end;) {
      const first = buffer[cursor++];
      if (first <= 0x7f) units.push(first);
      else if ((first & 0xe0) === 0xc0) {
        if (cursor >= end || (buffer[cursor] & 0xc0) !== 0x80) throw new Error('Invalid modified UTF-8');
        units.push(((first & 31) << 6) | (buffer[cursor++] & 63));
      } else if ((first & 0xf0) === 0xe0) {
        if (cursor + 1 >= end || (buffer[cursor] & 0xc0) !== 0x80 || (buffer[cursor + 1] & 0xc0) !== 0x80) throw new Error('Invalid modified UTF-8');
        units.push(((first & 15) << 12) | ((buffer[cursor++] & 63) << 6) | (buffer[cursor++] & 63));
      } else throw new Error('Invalid modified UTF-8');
    }
    let value = '';
    for (let i = 0; i < units.length; i += 4096) value += String.fromCharCode(...units.slice(i, i + 4096));
    return { value, size: length + 2 };
  };
  readers.nbtTagName = (buffer, offset) => readers.shortString(buffer, offset);
  const readNbtType = readers.nbtMapper;
  readers.nbtMapper = (buffer, offset) => {
    const result = readNbtType(buffer, offset);
    if (!['end', 'byte', 'short', 'int', 'long', 'float', 'double', 'byteArray', 'string', 'list', 'compound', 'intArray', 'longArray'].includes(result.value)) {
      throw new Error('Unknown NBT tag type');
    }
    return result;
  };
  // Upstream accepts a missing terminator and assigns __proto__ as a setter.
  // Preserve every own NBT key and require an actual compound end tag.
  readers.compound = (buffer, offset) => {
    const start = offset;
    const value = {};
    while (true) {
      if (offset >= buffer.length) throw new Error('Unterminated NBT compound');
      if (buffer[offset] === 0) return { value, size: offset - start + 1 };
      const result = readers.nbt(buffer, offset);
      Object.defineProperty(value, result.value.name, {
        value: { type: result.value.type, value: result.value.value },
        enumerable: true, writable: true, configurable: true,
      });
      offset += result.size;
    }
  };
  let depth = 0;
  let reads = 0;
  for (const [name, read] of Object.entries(readers)) {
    if (typeof read !== 'function') continue;
    readers[name] = (buffer, offset, ...args) => {
      if (++reads > MAX_READS || ++depth > MAX_DEPTH) throw new Error('Item structure exceeds bounds');
      try {
        if (!Number.isInteger(offset) || offset < 0 || offset > buffer.length) throw new Error('Item read outside buffer');
        const result = read(buffer, offset, ...args);
        if (!Number.isInteger(result.size) || result.size < 0 || offset + result.size > buffer.length) throw new Error('Truncated item wire');
        return result;
      } finally { depth--; }
    };
  }
  const decode = base64 => {
    // Buffer.from alone accepts malformed base64. Require a canonical envelope.
    if (typeof base64 !== 'string' || !base64.length || base64.length > 4 * Math.ceil(MAX_BYTES / 3)
      || base64.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(base64)) throw new Error('Invalid item base64');
    const buffer = Buffer.from(base64, 'base64');
    if (buffer.length > MAX_BYTES || buffer.toString('base64') !== base64) throw new Error('Invalid item base64');
    depth = 0;
    reads = 0;
    const result = proto.read(buffer, 0, 'Slot');
    if (result.size !== buffer.length) throw new Error('Trailing item wire bytes');
    return result.value;
  };
  if (decoders.size === 4) decoders.delete(decoders.keys().next().value);
  decoders.set(fingerprint, decode);
  return decode;
}

function registryEntries (mapping, label) {
  if (!Array.isArray(mapping) || !mapping.length || mapping.length > MAX_ENTRIES) throw new Error(`Invalid ${label} registry array`);
  const entries = [];
  const names = new Set();
  for (let id = 0; id < mapping.length; id++) {
    const name = mapping[id];
    if (name == null) continue;
    if (typeof name !== 'string' || name.length > 512 || !/^[a-z0-9_.-]+:[a-z0-9/_.-]+$/.test(name) || names.has(name)) throw new Error(`Invalid or duplicate ${label} registry name`);
    names.add(name);
    entries.push([id, name]);
  }
  if (!entries.length) throw new Error(`Empty ${label} registry`);
  return entries;
}

function correctedTypes () {
  const types = structuredClone(data.protocol.types);
  const fields = types.SlotComponent[1][1].type[1].fields;
  // PotionContents.STREAM_CODEC: no customName until after PC1.21.1.
  fields.potion_contents[1] = fields.potion_contents[1].filter(field => field.name !== 'customName');
  // FoodProperties: optional nonempty stack, then full MobEffectInstances.
  fields.food[1].find(field => field.name === 'usingConvertsTo').type = ['option', 'Slot'];
  fields.food[1].find(field => field.name === 'effects').type[1].type[1].find(field => field.name === 'effect').type = 'ItemPotionEffect';
  // Filterable<Component> and BlockPredicate write a boolean before optional NBT.
  types.ItemWrittenBookPage[1].find(field => field.name === 'filteredContent').type = ['option', 'anonymousNbt'];
  types.ItemBlockPredicate[1].find(field => field.name === 'nbt').type = ['option', 'anonymousNbt'];
  // Instrument.DIRECT_STREAM_CODEC: sound holder, VarInt ticks, float range.
  types.InstrumentData[1] = types.InstrumentData[1].filter(field => field.name !== 'description');
  types.InstrumentData[1].find(field => field.name === 'useDuration').type = 'varint';
  // TrimMaterial: model index float, and armor-material registry IDs as map keys.
  types.ArmorTrimMaterial[1].splice(2, 0, { name: 'itemModelIndex', type: 'f32' });
  types.ArmorTrimMaterial[1].find(field => field.name === 'overrideArmorAssets').type[1].type[1].find(field => field.name === 'key').type = 'varint';
  // StatePropertiesPredicate.RangedMatcher: both endpoints are optional strings.
  for (const field of types.ItemBlockProperty[1].find(field => field.name === 'value').type[1].fields.false[1]) field.type = ['option', 'string'];
  return types;
}

// Fully tagged trees cannot collide with strings, arrays, objects or NBT keys.
// Both transport functions are self-contained: at guest bundle BUILD time,
// emit `export const decodeItemTransport = ${decodeItemTransport.toString()}`
// from the trusted host into a virtual module. Do not bundle this host module
// (and its protocol imports) into QuickJS or give scripts a loader/eval hook.
// Numeric long bits and valueOf/toString survive; raw bytes remain the caller's
// separate diagnostic data. JS numbers cannot retain NaN payload bit patterns.
export function encodeItemTransport (value) {
  let nodes = 0;
  const ancestors = new Set();
  function visit (value, depth) {
    if (++nodes > 200000 || depth > 128) throw new Error('Item transport exceeds bounds');
    if (value === undefined) return ['u'];
    if (value === null) return ['null'];
    if (typeof value === 'boolean') return ['b', value];
    if (typeof value === 'string') return ['s', value];
    if (typeof value === 'number') return ['n', Number.isNaN(value) ? 'NaN' : value === Infinity ? '+Infinity' : value === -Infinity ? '-Infinity' : Object.is(value, -0) ? '-0' : value];
    if (typeof value !== 'object' || ancestors.has(value)) throw new Error('Unsupported or cyclic item transport value');
    if (Array.isArray(value) && value.length === 2 && typeof value.valueOf() === 'bigint') {
      if (!value.every(word => Number.isInteger(word) && word >= -2147483648 && word <= 2147483647)) throw new Error('Invalid signed long');
      return ['i64', value[0], value[1]];
    }
    ancestors.add(value);
    try {
      return Array.isArray(value) ? ['a', value.map(child => visit(child, depth + 1))]
        : ['o', Object.keys(value).map(key => [key, visit(value[key], depth + 1)])];
    } finally { ancestors.delete(value); }
  }
  return ['item-wire-v1', visit(value, 0)];
}

export function decodeItemTransport (encoded) {
  // Same observable arithmetic/string behavior as protodef's SignedBigInt.
  class SignedBigInt extends Array {
    valueOf () {
      // QuickJS 0.32.0's bundled runtime mishandles asUintN(32, -1n).
      const bits = ((BigInt(this[0]) << 32n) | (BigInt(this[1]) & 0xffffffffn)) & 0xffffffffffffffffn;
      return (bits & 0x8000000000000000n) ? bits - 0x10000000000000000n : bits;
    }
    toString () { return this.valueOf().toString(); }
    [Symbol.for('nodejs.util.inspect.custom')] () { return this.valueOf(); }
  }
  if (!Array.isArray(encoded) || encoded.length !== 2 || encoded[0] !== 'item-wire-v1') throw new Error('Invalid item transport envelope');
  let nodes = 0;
  function visit (node, depth) {
    if (++nodes > 200000 || depth > 128) throw new Error('Item transport exceeds bounds');
    if (!Array.isArray(node)) throw new Error('Invalid item transport node');
    const [tag, value] = node;
    if (tag === 'u' && node.length === 1) return undefined;
    if (tag === 'null' && node.length === 1) return null;
    if (tag === 'b' && node.length === 2 && typeof value === 'boolean') return value;
    if (tag === 's' && node.length === 2 && typeof value === 'string') return value;
    if (tag === 'n' && node.length === 2) {
      if (typeof value === 'number' && Number.isFinite(value)) return value;
      if (value === 'NaN') return NaN;
      if (value === '+Infinity') return Infinity;
      if (value === '-Infinity') return -Infinity;
      if (value === '-0') return -0;
    }
    if (tag === 'i64' && node.length === 3 && node.slice(1).every(word => Number.isInteger(word) && word >= -2147483648 && word <= 2147483647)) return new SignedBigInt(node[1], node[2]);
    if (tag === 'a' && node.length === 2 && Array.isArray(value)) return value.map(child => visit(child, depth + 1));
    if (tag === 'o' && node.length === 2 && Array.isArray(value)) {
      const result = {};
      for (const pair of value) {
        if (!Array.isArray(pair) || pair.length !== 2 || typeof pair[0] !== 'string' || Object.hasOwn(result, pair[0])) throw new Error('Invalid item transport property');
        Object.defineProperty(result, pair[0], { value: visit(pair[1], depth + 1), enumerable: true, writable: true, configurable: true });
      }
      return result;
    }
    throw new Error('Invalid item transport node');
  }
  return visit(encoded[1], 0);
}
