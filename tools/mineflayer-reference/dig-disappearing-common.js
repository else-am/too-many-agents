const position = new Vec3(60,-60,3), target = bot.blockAt(position);
if (target?.name !== 'stone' || bot.heldItem || bot.inventory.items().length ||
    bot.game.gameMode !== 'survival' || !bot.entity.onGround || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Disappearing target fixture differs');
const completed = [], aborted = [];
const onComplete = block => completed.push({name:block.name,position:block.position.clone(),
  targetCleared:bot.targetDigBlock === null && bot.targetDigFace === null});
const onAbort = block => aborted.push(block.name);
bot.on('diggingCompleted',onComplete);bot.on('diggingAborted',onAbort);
let settled = false;
const started = Date.now();
try {
  const pending = bot.dig(target,true,new Vec3(0,0,-1)).then(
    value => {settled=true;return {resolved:true,void:value === undefined};},
    error => {settled=true;return {resolved:false,name:error.name,message:error.message};});
  for (let tick=0;tick<40 && !settled && !bot.targetDigBlock;tick++) await bot.waitForTicks(1);
  if (settled || !bot.targetDigBlock?.position.equals(position)) throw new Error('Running dig not observed');
  await bot.waitForTicks(3);
  if (settled || bot.blockAt(position)?.name !== 'stone') throw new Error('Target changed before fixture gate');
  await removeFixtureTarget();
  const outcome = await pending;
  if (!outcome.resolved || !outcome.void || completed.length !== 1 || aborted.length ||
      completed[0].name !== 'air' || !completed[0].position.equals(position) || !completed[0].targetCleared)
    throw new Error('Disappearance completion differs: '+JSON.stringify({outcome,completed,aborted}));
  await bot.waitForTicks(160);
  if (completed.length !== 1 || aborted.length || bot.blockAt(position)?.name !== 'air' || bot.inventory.items().length)
    throw new Error('Disappearance replay or item gain');
  return {outcome,completed,aborted,inventoryEmpty:true,postChangeTicks:160,elapsedMs:Date.now()-started};
} finally {bot.removeListener('diggingCompleted',onComplete);bot.removeListener('diggingAborted',onAbort);}
