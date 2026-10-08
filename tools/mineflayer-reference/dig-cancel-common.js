const position = new Vec3(60,-60,3), target = bot.blockAt(position);
if (target?.name !== 'stone' || bot.heldItem || bot.inventory.items().length ||
    bot.game.gameMode !== 'survival' || !bot.entity.onGround || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Dig cancellation fixture differs');
const aborted = [], completed = [];
const onAbort = block => aborted.push({sameTarget:block === target,name:block.name,
  position:block.position.clone(),targetCleared:bot.targetDigBlock === null && bot.targetDigFace === null});
const onComplete = block => completed.push(block.name);
bot.on('diggingAborted',onAbort);bot.on('diggingCompleted',onComplete);
const started = Date.now();
let settled = false;
try {
  const pending = bot.dig(target,true,new Vec3(0,0,-1)).then(
    value => { settled=true;return {resolved:true,value}; },
    error => { settled=true;return {resolved:false,name:error.name,message:error.message}; });
  for (let tick=0; tick<40 && !settled && !bot.targetDigBlock; tick++) await bot.waitForTicks(1);
  if (settled || !bot.targetDigBlock?.position.equals(position)) throw new Error('Active dig was not observed');
  await bot.waitForTicks(3);
  if (settled || bot.blockAt(position)?.name !== 'stone') throw new Error('Dig finished before cancellation');
  if (bot.stopDigging() !== undefined || bot.stopDigging() !== undefined) throw new Error('stopDigging must return void');
  const outcome = await pending;
  if (outcome.resolved || aborted.length !== 1 || completed.length || !aborted[0].sameTarget ||
      !aborted[0].targetCleared || !aborted[0].position.equals(position) ||
      bot.targetDigBlock !== null || bot.targetDigFace !== null || !Number.isFinite(bot.lastDigTime))
    throw new Error('Dig cancellation promise/event contract differs');
  await bot.waitForTicks(160);
  if (bot.blockAt(position)?.name !== 'stone' || bot.inventory.items().length || aborted.length !== 1 || completed.length)
    throw new Error('Cancelled dig continued or replayed an event');
  return {outcome,aborted,completed,postCancelTicks:160,inventoryEmpty:true,target:'stone',elapsedMs:Date.now()-started};
} finally {bot.removeListener('diggingAborted',onAbort);bot.removeListener('diggingCompleted',onComplete);}
