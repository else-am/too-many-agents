// PREPARED ONLY. Public held-control rejection; see fox-drowned-fixture.md.
const expectedUuid='cc204df2-d044-4135-877b-d8fc08f138b2';
const body=bot.entity,origin=body.position.clone(),inventory=JSON.stringify(bot.inventory.slots);
if(body.uuid!==expectedUuid || !body.alive || !body.onGround || body.isSleeping || bot.vehicle ||
    bot.inventory.slots.some(Boolean) || bot.currentWindow || bot.inventory.selectedItem ||
    Object.values(bot.controlState).some(Boolean))throw new Error('Empty dry grounded rejection fixture differs');
await bot.waitForChunksToLoad();
const target=origin.floored().offset(2,0,0);
for(let dx=0;dx<=2;dx++){
  const p=origin.floored().offset(dx,0,0);
  if(bot.blockAt(p)?.name!=='air' || bot.blockAt(p.offset(0,1,0))?.name!=='air' ||
      bot.blockAt(p.offset(0,-1,0))?.name!=='stone')throw new Error('Reachable noncurrent route fixture differs');
}
const movements=new Movements(bot);
movements.canDig=false;movements.allowParkour=false;movements.allow1by1towers=false;
movements.scafoldingBlocks=[];movements.allowFreeMotion=false;movements.allowSprinting=false;
bot.pathfinder.enablePathShortcut=false;bot.pathfinder.setMovements(movements);
const counters={pathUpdates:0,successfulPlans:0,goalReached:0,pathStops:0};const resets=[];
bot.on('path_update',result=>{counters.pathUpdates++;if(result.status==='success')counters.successfulPlans++;});
bot.on('goal_reached',()=>counters.goalReached++);bot.on('path_stop',()=>counters.pathStops++);
bot.on('path_reset',reason=>resets.push(reason));
const start=Date.now();
bot.setControlState('sneak',true);await bot.waitForTicks(1);
if(!bot.getControlState('sneak'))throw new Error('Native held sneak was not observed before request');
let rejection;
try{await bot.pathfinder.goto(new goals.GoalBlock(target.x,target.y,target.z));}
catch(error){
  rejection={name:error.name,code:error.code??null,message:error.message,phase:error.phase??null,detail:error.detail??null};
  // Current host maps native HTTP400 before-start to these public Error fields.
  // BlockChanged/NoPath/unknown failures never authorize a recovery action.
  if(error.phase!=='before_start' || error.detail!=='release_manual_controls_before_navigation')throw error;
}
if(!rejection || !counters.successfulPlans || counters.goalReached || counters.pathStops!==1 ||
    !resets.includes('execution_error') || body.position.distanceTo(origin)>.05 ||
    JSON.stringify(bot.inventory.slots)!==inventory)
  throw new Error('Known native route rejection/no-mutation witness differs');
// Only the proven before-start rejection permits this deliberate continuation.
bot.setControlState('sneak',false);await bot.waitForTicks(1);
await bot.look(body.yaw,body.pitch,true);
if(bot.entity!==body || bot.getControlState('sneak') || Object.values(bot.controlState).some(Boolean) ||
    body.position.distanceTo(origin)>.05 || JSON.stringify(bot.inventory.slots)!==inventory ||
    bot.currentWindow || bot.inventory.selectedItem || bot.pathfinder.isMoving())
  throw new Error('Safe continuation/release after known rejection differs');
return {start,end:Date.now(),bodyUuid:body.uuid,origin,target,rejection,counters,resets,
  final:body.position.clone(),inventoryUnchanged:true,continuedLookCompleted:true};
