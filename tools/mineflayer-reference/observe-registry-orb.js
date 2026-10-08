// Read-only registry checks, then one coordinator-created XP orb after a NEW look.
// Fixture: ordinary overworld, no nearby old orbs; native Value7/Count3 >=5 blocks away.
const before = JSON.stringify(bot.inventory.slots), start = Date.now();
const registry = bot.registry, codecs = registry.writeDimensionCodec();
const ids = ['minecraft:dimension_type', 'minecraft:worldgen/biome', 'minecraft:chat_type'];
if (bot.game.dimension !== 'overworld' || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Registry/orb fixture prerequisites differ');
for (const id of ids) {
  const section = codecs[id];
  if (section?.id !== id || !Array.isArray(section.entries) || !section.entries.length
    || section.entries.some(e => typeof e.key !== 'string' || e.value?.type !== 'compound'))
    throw new Error('Incomplete native registry codec: ' + id);
}
const dimension = registry.dimensionsByName.overworld;
if (!dimension || dimension.minY !== bot.game.minY || dimension.height !== bot.game.height)
  throw new Error('Native dimension codec and game bounds differ');
const column = bot.world.getColumnAt(bot.entity.position);
const block = bot.blockAt(bot.entity.position);
const biome = registry.biomes[block?.biome?.id];
const biomeEntry = codecs['minecraft:worldgen/biome'].entries[biome?.id];
if (!column || !biome || block.biome !== biome || !biomeEntry
  || biomeEntry.key.replace(/^minecraft:/, '') !== biome.name
  || JSON.stringify(biomeEntry.value.value.effects?.value) === undefined)
  throw new Error('Native biome ID/object/codec differs');
const chat = Object.values(registry.chatFormattingById).find(e => e.name === 'minecraft:chat');
if (!chat || registry.chatFormattingByName[chat.name] !== chat
  || codecs['minecraft:chat_type'].entries[chat.id]?.key !== chat.name)
  throw new Error('Native chat registry IDs differ');
const message = ChatMessage.fromNetwork(chat.id, {sender:'Registry fixture', content:'Hello'});
if (!(message instanceof ChatMessage) || message.toString() !== '<Registry fixture> Hello')
  throw new Error('Native chat format differs');
const exportedCount = codecs['minecraft:chat_type'].entries.length;
codecs['minecraft:chat_type'].entries.length = 0;
if (registry.writeDimensionCodec()['minecraft:chat_type'].entries.length !== exportedCount)
  throw new Error('Returned codec alias mutated retained native data');
if (Object.values(bot.entities).some(e => e.name === 'experience_orb'))
  throw new Error('Existing orb would make fixture ambiguous');
let orb;
const errors = [];
bot.on('entitySpawn', entity => {
  if (entity.name !== 'experience_orb') return;
  if (orb) errors.push('Unexpected second orb');
  orb = entity;
  if (!(entity instanceof Entity) || !(entity.position instanceof Vec3)
    || bot.entities[entity.id] !== entity || entity.type !== 'orb' || entity.count !== 7)
    errors.push('XP orb callback not hydrated with actual packet value');
});
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
for (let tick = 0; !orb && tick < 1200; tick++) await bot.waitForTicks(1);
if (!orb || errors.length) throw new Error('Orb observation differs: ' + JSON.stringify(errors));
await bot.waitForTicks(5);
if (bot.entities[orb.id] !== orb || orb.count !== 7 || orb.type !== 'orb'
  || JSON.stringify(bot.inventory.slots) !== before || errors.length)
  throw new Error('Orb identity/value, replay or inventory differs');
return {start, end:Date.now(), registry:{dimensions:registry.dimensionsArray.length,
  biomes:registry.biomesArray.length, chatTypes:exportedCount,
  dimension, biome:{id:biome.id,name:biome.name,effects:biome.effects}, formatted:message.toString()},
  orb:{id:orb.id,uuid:orb.uuid,type:orb.type,count:orb.count,position:orb.position.clone()},
  inventoryUnchanged:true};
