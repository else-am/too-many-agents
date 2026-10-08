// Prepared only; coordinate the two owning threads in breaking-broadcast-fixture.md.
const expectedUuid = 'REPLACE_WITH_NEW_OBSERVER_UUID';
const minerUuid = 'REPLACE_WITH_NEW_MINER_UUID';
const body = bot.entity, target = new Vec3(382,-60,112);
const miner = Object.values(bot.entities).find(entity => entity.uuid === minerUuid);
if (body.uuid !== expectedUuid || body.name !== 'cow' || !body.alive || !body.onGround ||
    body.isInWater || body.isInLava || bot.vehicle || bot.currentWindow ||
    bot.inventory.slots.some(Boolean) || bot.inventory.selectedItem ||
    Object.values(bot.controlState).some(Boolean) || !(miner instanceof Entity) ||
    miner === body || !miner.alive || body.position.distanceTo(target) >= 24)
  throw new Error('Fresh observer/miner fixture differs');
await bot.waitForChunksToLoad();
if (bot.blockAt(target)?.name !== 'stone') throw new Error('Mining target differs');
const inventory = JSON.stringify(bot.inventory.slots), health = bot.health;
const histogram = Array(10).fill(0), events = [], errors = [];
let progress = 0, ends = 0, lastStage = -1, afterEnd = 0;
const fail = message => { if (errors.length < 8) errors.push(message); };
function inspect(kind, block, breaker, stage) {
  if (!block?.position?.equals(target)) return;
  if (!(block instanceof Block) || !(block.position instanceof Vec3) ||
      breaker !== miner || bot.entities[miner.id] !== miner || !(breaker instanceof Entity) ||
      block.stateId !== bot.blockAt(target)?.stateId)
    fail('Typed current block/shared breaker differs');
  if (kind === 'progress') {
    if (ends) afterEnd++;
    if (!Number.isInteger(stage) || stage < 0 || stage > 9 || stage < lastStage)
      fail('Native progress stage differs');
    else { histogram[stage]++; lastStage = stage; }
    progress++;
  } else {
    ends++;
    if (!progress || block.name !== 'air') fail('End preceded progress or authoritative removal');
  }
  if (events.length < 16 || kind === 'end')
    events.push({kind,stage,name:block.name,stateId:block.stateId,breakerUuid:breaker?.uuid});
}
const progressing = (block, stage, breaker) => inspect('progress',block,breaker,stage);
const ended = (block, breaker) => inspect('end',block,breaker);
bot.on('blockBreakProgressObserved', progressing);
bot.on('blockBreakProgressEnd', ended);
try {
  // Coordinator starts the exact miner script only after this NEW completed action.
  await bot.look(body.yaw,body.pitch,true);
  for (let tick = 0; ends === 0 && tick < 1200; tick++) await bot.waitForTicks(1);
  const atEnd = progress;
  await bot.waitForTicks(10);
  if (!progress || ends !== 1 || afterEnd || progress !== atEnd || errors.length ||
      bot.blockAt(target)?.name !== 'air' || bot.entity !== body || bot.health !== health ||
      JSON.stringify(bot.inventory.slots) !== inventory || bot.currentWindow || bot.inventory.selectedItem)
    throw new Error('Breaking broadcast/terminal hydration differs');
  return {bodyUuid:body.uuid,minerUuid,progress,ends,histogram,lastStage,events,errors,
    inventoryUnchanged:true,finalBlock:bot.blockAt(target).name};
} catch (error) {
  console.log(JSON.stringify({progress,ends,histogram,lastStage,afterEnd,events,errors}));
  throw error;
} finally {
  bot.removeListener('blockBreakProgressObserved',progressing);
  bot.removeListener('blockBreakProgressEnd',ended);
}
