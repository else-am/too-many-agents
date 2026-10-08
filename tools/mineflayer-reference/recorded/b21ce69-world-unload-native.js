// Existing world-cache-scenarios.md contract; guest cache only, no native action.
const position = bot.entity.position.floored().offset(0, -1, 0);
const cx = Math.floor(position.x / 16), cz = Math.floor(position.z / 16);
const column = bot.world.getColumn(cx, cz), before = bot.world.getBlock(position);
if (!column || !before || bot.world.async.sync !== bot.world || bot.world.async.storageProvider)
  throw new Error('World cache prerequisites differ');
let callbackUnknown = false;
const unloaded = corner => {
  if (corner.x === cx * 16 && corner.z === cz * 16)
    callbackUnknown = bot.world.getBlock(position) === null;
};
bot.world.on('chunkColumnUnload', unloaded);
try {
  bot.world.unloadColumn(cx, cz);
  const getters = ['getBlock', 'getBlockStateId', 'getBlockType', 'getBlockData',
    'getBlockLight', 'getSkyLight', 'getBiome'];
  if (bot.world.getColumn(cx, cz) || bot.world.async.getLoadedColumn(cx, cz) ||
      !callbackUnknown || getters.some(name => bot.world[name](position) !== null))
    throw new Error('Unloaded World column still supplies cached values');
  if (bot.blockAt(position)?.stateId !== before.stateId)
    throw new Error('Separate native observation was lost');
} finally {
  bot.world.off('chunkColumnUnload', unloaded);
  bot.world.setColumn(cx, cz, column, false);
}
if (bot.world.getColumn(cx, cz) !== column || bot.world.getBlock(position)?.stateId !== before.stateId)
  throw new Error('Guest column restoration differs');
return { position, callbackUnknown, columnRestored: true, nativeRequests: 0 };
