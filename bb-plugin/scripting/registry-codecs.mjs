// PC1.21.1 registry projections adapted from prismarine-registry 1.12.0.
// Only the native biome and chat_type registries are projected.
const sections = ['minecraft:worldgen/biome', 'minecraft:chat_type'];
const limits = { nodes: 262144, text: 4 * 1024 * 1024 };
const types = new Set(['end', 'byte', 'short', 'int', 'long', 'float', 'double', 'byteArray', 'string', 'list', 'compound', 'intArray', 'longArray']);
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const resourceName = name => {
  if (typeof name !== 'string' || name.length > 32767 || !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(name)) throw new TypeError('Invalid registry resource name');
  return name;
};
const integer = (value, min, max) => {
  if (!Number.isInteger(value) || value < min || value > max) throw new TypeError('Invalid registry NBT integer');
  return value;
};

// Validate and copy together, before any published index changes. This also
// bounds cyclic input and protects compounds with keys such as __proto__.
function copyTag(tag, budget, depth = 0) {
  if (!record(tag)) throw new TypeError('Expected typed registry NBT');
  return { type: tag.type, value: copyValue(tag.type, tag.value, budget, depth) };
}
function copyValue(type, value, budget, depth) {
  if (!types.has(type)) throw new TypeError(`Unsupported registry NBT type: ${type}`);
  if (++budget.nodes > limits.nodes || depth > 64) throw new RangeError('Registry NBT tree exceeds limit');
  const text = value => {
    if (typeof value !== 'string' || value.length > 65535) throw new TypeError('Invalid registry NBT string');
    budget.text += value.length;
    if (budget.text > limits.text) throw new RangeError('Registry NBT text exceeds limit');
    return value;
  };
  const list = (values, elementType) => {
    if (!Array.isArray(values) || values.length > limits.nodes) throw new TypeError('Invalid registry NBT array');
    return values.map(entry => copyValue(elementType, entry, budget, depth + 1));
  };
  switch (type) {
    case 'end': if (value !== null) throw new TypeError('Invalid end tag'); return null;
    case 'byte': return integer(value, -128, 127);
    case 'short': return integer(value, -32768, 32767);
    case 'int': return integer(value, -2147483648, 2147483647);
    case 'long':
      if (!Array.isArray(value) || value.length !== 2) throw new TypeError('Expected NBT long words');
      return value.map(word => integer(word, -2147483648, 2147483647));
    case 'float': case 'double':
      if (typeof value !== 'number') throw new TypeError('Invalid registry NBT number');
      return value;
    case 'string': return text(value);
    case 'byteArray': return list(value, 'byte');
    case 'intArray': return list(value, 'int');
    case 'longArray': return list(value, 'long');
    case 'list':
      if (!record(value) || !types.has(value.type) || (value.type === 'end' && value.value?.length !== 0))
        throw new TypeError('Invalid registry NBT list');
      return { type: value.type, value: list(value.value, value.type) };
    case 'compound':
      if (!record(value)) throw new TypeError('Invalid registry NBT compound');
      return Object.fromEntries(Object.entries(value).map(([key, child]) => [text(key), copyTag(child, budget, depth + 1)]));
    default: throw new TypeError(`Unsupported registry NBT type: ${type}`);
  }
}
function simplify({ type, value }) {
  if (type === 'compound') return Object.fromEntries(Object.entries(value).map(([key, tag]) => [key, simplify(tag)]));
  if (type === 'list') return value.value.map(entry => simplify({ type: value.type, value: entry }));
  if (Array.isArray(value)) return value.map(entry => Array.isArray(entry) ? entry.slice() : entry);
  return value;
}
function copyPackets(packets) {
  const budget = { nodes: 0, text: 0 };
  return Object.fromEntries(Object.entries(packets).map(([id, packet]) => {
    if (!record(packet) || packet.id !== id || !Array.isArray(packet.entries) || packet.entries.length > 4096)
      throw new RangeError('Invalid or oversized registry packet');
    const names = new Set();
    const entries = packet.entries.map(entry => {
      if (!record(entry)) throw new TypeError('Invalid registry entry');
      const key = resourceName(entry.key);
      if (names.has(key)) throw new TypeError(`Duplicate registry entry: ${key}`);
      names.add(key);
      budget.text += key.length;
      if (budget.text > limits.text) throw new RangeError('Registry text exceeds limit');
      if (entry.value?.type !== 'compound') throw new TypeError('Registry entry needs complete compound NBT');
      return { key, value: copyTag(entry.value, budget) };
    });
    return [id, { id, entries }];
  }));
}

function projection(packet, staticData) {
  const entries = packet.entries.map(({ key, value }, id) => ({ id, name: key, element: simplify(value) }));
  if (packet.id === sections[0]) {
    const known = Object.fromEntries(staticData.biomesArray.map(entry => [entry.name, entry]));
    const array = entries.map(({ id, name, element }) => {
      name = name.replace('minecraft:', '');
      return { ...known[name], ...element, id, name, category: element.category, temperature: element.temperature,
        depth: element.depth, scale: element.scale, precipitation: element.precipitation, rainfall: element.downfall };
    });
    return { biomes: array, biomesArray: array, biomesByName: Object.fromEntries(entries.map((entry, id) => [entry.name, array[id]])) };
  }
  const formats = entries.flatMap(({ id, name, element }) => {
    const decoration = element.chat?.decoration ?? element.chat;
    if (!decoration) return [];
    if (typeof decoration.translation_key !== 'string' || !Array.isArray(decoration.parameters)
      || decoration.parameters.some(value => typeof value !== 'string')) throw new TypeError('Invalid chat decoration');
    return [{ id, name, formatString: staticData.language[decoration.translation_key] || decoration.translation_key, parameters: decoration.parameters }];
  });
  // The pinned incrementedChatType flag is false for PC1.21.1.
  return { chatFormattingById: Object.fromEntries(formats.map(entry => [entry.id, entry])) };
}

export function installRegistryCodecs(registry, staticData, nativeCodecs) {
  if (!Array.isArray(nativeCodecs) || nativeCodecs.length !== sections.length
    || sections.some(id => nativeCodecs.filter(packet => packet?.id === id).length !== 1))
    throw new TypeError('Native registry snapshot must contain biome and chat codecs');
  const packets = copyPackets(Object.fromEntries(nativeCodecs.map(packet => [packet.id, packet])));
  Object.assign(registry, ...Object.values(packets).map(packet => projection(packet, staticData)));
}
