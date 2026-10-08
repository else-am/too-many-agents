// Build-time selection: canonical tables from the one pinned PC version.
// Indexes are rebuilt in the guest so block-state ranges don't repeat 18MB of JSON.
export function selectRegistryData(minecraftData) {
  const registry = minecraftData('1.21.1');
  const fields = [
    'blocksArray', 'blockMappings', 'blockStates', 'blockCollisionShapes',
    'biomesArray', 'itemsArray', 'foodsArray', 'recipes', 'instrumentsArray',
    'materials', 'enchantmentsArray', 'entitiesArray', 'windowsArray',
    'protocol', 'protocolComments', 'protocolYaml', 'defaultSkin', 'version',
    'effectsArray', 'attributesArray', 'particlesArray', 'language',
    'blockLootArray', 'entityLootArray', 'commands', 'loginPacket',
    'mapIconsArray', 'tints', 'soundsArray', 'type',
  ];
  return {
    ...Object.fromEntries(fields.map(field => [field, registry[field]])),
    // Comparisons need only names and ordering, never other versions' game data.
    versionDataVersions: Object.fromEntries(Object.entries(minecraftData.versionsByMinecraftVersion.pc)
      .map(([name, version]) => [name, version.dataVersion])),
  };
}
