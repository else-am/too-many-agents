// PREPARED ONLY: NEW empty disposable body, two guarded worlds; see fixture notes.
const expectedUuid='aea89644-6c19-4162-9e5e-f8951744cbcd';
const body=bot.entity,position=body.position,world=bot.world;
if(body.uuid!==expectedUuid || !body.alive || !body.onGround || body.isSleeping || bot.vehicle ||
    bot.inventory.slots.some(Boolean) || bot.currentWindow || bot.inventory.selectedItem ||
    Object.values(bot.controlState).some(Boolean))throw new Error('World-switch disposable fixture differs');
const initial={bodyUuid:body.uuid,position:position.clone(),dimension:bot.game.dimension,
  levelType:bot.game.levelType,gameMode:bot.game.gameMode,inventory:bot.inventory.slots,
  yaw:body.yaw,pitch:body.pitch,at:Date.now()};
console.log(JSON.stringify({kind:'world-switch-armed',initial}));
// Coordinator must observe this NEW completed action ID, then issue exactly
// one guarded dev_switch_world. A pre-tool READY message is not readiness.
await bot.look(body.yaw,body.pitch,true);
console.log(JSON.stringify({kind:'world-switch-waiting',at:Date.now(),bodyUuid:body.uuid,
  sameEntity:bot.entity===body,sameWorld:bot.world===world,samePosition:body.position===position}));
await bot.waitForTicks(400);
console.log(JSON.stringify({kind:'UNEXPECTED-after-switch-wait',at:Date.now(),bodyUuid:bot.entity.uuid}));
// This distinct yaw is a sentinel that must never be queued after session loss.
// No catch, retry, cleanup action or new-session request is allowed here.
await bot.look(initial.yaw+.37,initial.pitch,true);
throw new Error('World-switch script survived its original session and reached forbidden later action');
