// Build-time selection: canonical tables from the one pinned PC version.
// Indexes are rebuilt in the guest so block-state ranges don't repeat 18MB of JSON.
export function selectRegistryData(minecraftData) {
  const registry = minecraftData('1.21.1');
  const fields = [
    'blocksArray', 'blockCollisionShapes',
    'biomesArray', 'itemsArray', 'foodsArray', 'recipes', 'instrumentsArray',
    'materials', 'enchantmentsArray', 'entitiesArray',
    'effectsArray', 'attributesArray', 'particlesArray', 'language',
    'blockLootArray', 'entityLootArray',
  ];
  return Object.fromEntries(fields.map(field => [field, registry[field]]));
}
