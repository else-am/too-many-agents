// Selected PC registry indexes/version helpers adapted from minecraft-data
// 3.117.0 lib/indexes.js and index.js; its MIT attribution is bundled by build.mjs.
export function createRegistry(data) {
  const { versionDataVersions, ...registry } = data;
  const index = (array, field) => array === undefined ? undefined
    : Object.fromEntries(array.map(value => [value[field], value]));
  for (const name of ['blocks', 'biomes', 'items', 'foods', 'enchantments', 'entities',
    'windows', 'effects', 'particles', 'mapIcons', 'sounds']) {
    registry[name] = index(registry[`${name}Array`], 'id');
    registry[`${name}ByName`] = index(registry[`${name}Array`], 'name');
  }
  registry.instruments = index(registry.instrumentsArray, 'id');
  registry.attributes = index(registry.attributesArray, 'resource');
  registry.attributesByName = index(registry.attributesArray, 'name');
  registry.blockLoot = index(registry.blockLootArray, 'block');
  registry.entityLoot = index(registry.entityLootArray, 'entity');
  registry.mobs = index(registry.entitiesArray?.filter(entity => entity.type === 'mob'), 'id');
  registry.objects = index(registry.entitiesArray?.filter(entity => entity.type === 'object'), 'id');
  registry.blocksByStateId = {};
  for (const block of registry.blocksArray)
    for (let id = block.minStateId; id <= block.maxStateId; id++) registry.blocksByStateId[id] = block;

  const version = registry.version = { ...registry.version };
  const current = version.dataVersion ?? 0;
  const other = name => {
    if (!Object.hasOwn(versionDataVersions, name)) throw new RangeError(`Unknown pinned PC version: ${name}`);
    return versionDataVersions[name];
  };
  version['>='] = name => current >= other(name);
  version['>'] = name => current > other(name);
  version['<'] = name => current < other(name);
  version['<='] = name => current <= other(name);
  version['=='] = name => current === other(name);
  registry.isNewerOrEqualTo = name => version['>='](name);
  registry.isOlderThan = name => version['<'](name);
  return registry;
}
