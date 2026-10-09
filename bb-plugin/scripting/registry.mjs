// Selected PC registry indexes adapted from minecraft-data 3.117.0 lib/indexes.js;
// its MIT attribution is bundled by build.mjs. Only tables read by the Block,
// Item, Chat, Particle, Chunk and Recipe factories and the retained public
// registry names are kept.
import { installRegistryCodecs } from './registry-codecs.mjs';

export function createRegistry(data, nativeCodecs) {
  const index = (array, field) => Object.fromEntries(array.map(value => [value[field], value]));
  const registry = {
    blocksArray: data.blocksArray, itemsArray: data.itemsArray,
    blockCollisionShapes: data.blockCollisionShapes, materials: data.materials,
    language: data.language, recipes: data.recipes,
    blocksByName: index(data.blocksArray, 'name'),
    items: index(data.itemsArray, 'id'), itemsByName: index(data.itemsArray, 'name'),
    effects: index(data.effectsArray, 'id'), effectsByName: index(data.effectsArray, 'name'),
    enchantmentsByName: index(data.enchantmentsArray, 'name'),
    entitiesByName: index(data.entitiesArray, 'name'),
    foodsByName: index(data.foodsArray, 'name'),
    particles: index(data.particlesArray, 'id'), particlesByName: index(data.particlesArray, 'name'),
    attributesByName: index(data.attributesArray, 'name'),
    instruments: index(data.instrumentsArray, 'id'),
    blockLoot: index(data.blockLootArray, 'block'),
    entityLoot: index(data.entityLootArray, 'entity'),
  };
  installRegistryCodecs(registry, data, nativeCodecs);
  return registry;
}
