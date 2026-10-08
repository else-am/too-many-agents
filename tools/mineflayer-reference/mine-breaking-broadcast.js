// Prepared only; invoke after the observer's native readiness gate, on this body's thread.
const expectedUuid = 'REPLACE_WITH_NEW_MINER_UUID';
const body = bot.entity, target = new Vec3(382,-60,112);
if (body.uuid !== expectedUuid || body.name !== 'cow' || !body.alive || !body.onGround ||
    body.isInWater || body.isInLava || bot.vehicle || bot.currentWindow || bot.heldItem ||
    bot.inventory.slots.some(Boolean) || bot.inventory.selectedItem ||
    Object.values(bot.controlState).some(Boolean)) throw new Error('Fresh empty miner fixture differs');
await bot.waitForChunksToLoad();
const block = bot.blockAt(target);
if (block?.name !== 'stone' || !bot.canDigBlock(block)) throw new Error('Native mining fixture/reach differs');
const inventory = JSON.stringify(bot.inventory.slots), health = bot.health;
let selfProgress = 0, selfEnd = 0;
const progressing = block => { if (block?.position?.equals(target)) selfProgress++; };
const ended = block => { if (block?.position?.equals(target)) selfEnd++; };
bot.on('blockBreakProgressObserved',progressing);
bot.on('blockBreakProgressEnd',ended);
try {
  await bot.dig(block,true);
  await bot.waitForTicks(10);
  if (bot.blockAt(target)?.name !== 'air' || selfProgress || selfEnd ||
      bot.entity !== body || bot.health !== health || JSON.stringify(bot.inventory.slots) !== inventory ||
      bot.currentWindow || bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean))
    throw new Error('Native mining/self-breaker suppression differs');
  return {bodyUuid:body.uuid,finalBlock:'air',selfProgress,selfEnd,inventoryUnchanged:true};
} catch (error) {
  console.log(JSON.stringify({selfProgress,selfEnd,block:bot.blockAt(target)?.name}));
  throw error;
} finally {
  bot.removeListener('blockBreakProgressObserved',progressing);
  bot.removeListener('blockBreakProgressEnd',ended);
}
