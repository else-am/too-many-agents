const position = new Vec3(60,-60,3), target = bot.blockAt(position);
if (target?.name !== 'stone' || bot.heldItem || bot.inventory.items().length ||
    bot.game.gameMode !== 'survival' || !bot.entity.onGround || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Unsuitable tool fixture differs');
if (!bot.canDigBlock(target)) throw new Error('Reachable stone should be diggable without a harvest tool');
const estimateMs = bot.digTime(target), events = [];
if (!Number.isFinite(estimateMs) || estimateMs <= 0) throw new Error('Finite survival dig estimate required');
const completed = block => events.push({name:block.name,position:block.position.clone(),
  targetCleared:bot.targetDigBlock === null && bot.targetDigFace === null});
bot.on('diggingCompleted', completed);
const started = Date.now();
try {
  const result = await bot.dig(target,true,new Vec3(0,0,-1));
  if (result !== undefined || bot.blockAt(position)?.name !== 'air' ||
      events.length !== 1 || events[0].name !== 'air' || !events[0].position.equals(position) ||
      !events[0].targetCleared || !Number.isFinite(bot.lastDigTime))
    throw new Error('Dig completion/event contract differs');
  const elapsedMs = Date.now()-started;
  await bot.waitForTicks(10);
  if (events.length !== 1 || bot.inventory.items().length || bot.heldItem)
    throw new Error('Unsuitable tool yielded items or replayed completion');
  return {estimateMs,elapsedMs,resultWasVoid:true,events,inventoryEmpty:true,target:'air'};
} finally { bot.removeListener('diggingCompleted',completed); }
