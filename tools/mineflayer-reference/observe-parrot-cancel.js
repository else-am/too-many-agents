// Prepared only: separate fresh Parrot; cancellation fixture in parrot-route-fixture.md.
const expectedUuid = 'REPLACE_WITH_NEW_PARROT_CANCEL_UUID';
const body = bot.entity, caps = bot.nativeBody, origin = new Vec3(280.5,-60,126.5);
if (body.uuid!==expectedUuid || body.name!=='parrot' || !body.alive || !body.onGround ||
    body.isSleeping || body.isInWater || body.isInLava || bot.vehicle ||
    body.position.distanceTo(origin)>.2 || bot.inventory.slots.some(Boolean) ||
    bot.currentWindow || bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean))
  throw new Error('Fresh supported empty Parrot cancellation fixture differs');
if (caps?.physics!=='native-parrot-flight-post-tick' || caps.locomotion!=='flying' ||
    caps.canFly!==true || caps.canJump!==false || caps.canSwim!==false ||
    !Number.isFinite(body.width) || body.width<=0 || !Number.isFinite(body.height) || body.height<=0 ||
    !Number.isFinite(body.eyeHeight) || body.eyeHeight<=0 || !Number.isFinite(caps.flightTargetYOffset) ||
    Math.abs(caps.flightTargetYOffset-Math.max(.05,(1-body.height)/2))>1e-6)
  throw new Error('Native Parrot flight capability/geometry differs');
await bot.waitForChunksToLoad();
for (let x=278;x<=301;x++) for (let z=124;z<=128;z++) {
  if (bot.blockAt(new Vec3(x,-61,z))?.name!=='stone') throw new Error('Cancellation support differs');
  for (let y=-60;y<=-52;y++)
    if (bot.blockAt(new Vec3(x,y,z))?.name!=='air') throw new Error('Cancellation flight volume differs');
}
const inventory=JSON.stringify(bot.inventory.slots), health=bot.health, start=Date.now();
const target=new goals.GoalBlock(298,-55,126), trace=[], paths=[], resets=[], goalEvents=[];
let samples=0, pathEvents=0, goalEventCount=0, cancelledAt=null, pathsAtCancel=null, phase='takeoff';
let settled=false, outcome=null, cancelReturn;
const movements=new Movements(bot);
movements.canDig=false;movements.allowParkour=false;movements.allowSprinting=false;
movements.allow1by1towers=false;movements.scafoldingBlocks=[];movements.allowFreeMotion=false;
movements.exclusionAreasStep.push(block=>!block.position || block.position.x<278 || block.position.x>301 ||
  block.position.z<124 || block.position.z>128 || block.position.y< -60 || block.position.y> -53 ? 200 : 0);
bot.pathfinder.enablePathShortcut=false;bot.pathfinder.setMovements(movements);
const sample=()=>{
  samples++;
  const p=body.position;
  if (trace.length===64) trace.shift();
  trace.push({phase,p:p.clone(),v:body.velocity.clone(),ground:body.onGround});
  if (!cancelledAt && bot.entity===body && bot.pathfinder.goal===target && bot.pathfinder.isMoving() &&
      !body.onGround && p.y>origin.y+.2 && p.distanceTo(origin)>.25) {
    cancelledAt={at:Date.now(),p:p.clone(),v:body.velocity.clone(),ground:body.onGround};
    pathsAtCancel=pathEvents;phase='cancel';
    cancelReturn=bot.pathfinder.setGoal(null);
  }
};
const path=result=>{
  pathEvents++;
  if (paths.length===16) paths.shift();
  paths.push({phase,status:result.status,totalNodes:result.path.length,
    hasEdits:result.path.some(node=>node.toBreak.length||node.toPlace.length),
    hasParkour:result.path.some(node=>node.parkour===true),
    nodes:result.path.slice(0,16).map(node=>({x:node.x,y:node.y,z:node.z,
      edits:node.toBreak.length+node.toPlace.length,parkour:node.parkour===true}))});
};
const reset=reason=>{if(resets.length<16)resets.push({phase,reason});};
const changed=next=>{
  goalEventCount++;
  if(goalEvents.length<16)goalEvents.push(next===target?'target':next===null?'null':'other');
};
bot.on('physicsTick',sample);bot.on('path_update',path);bot.on('path_reset',reset);bot.on('goal_updated',changed);
try {
  // Attach both handlers immediately; await this one goto, never issue a replacement route.
  const route=bot.pathfinder.goto(target);
  const drained=route.then(()=>{settled=true;outcome={name:'resolved'};},error=>{
    settled=true;outcome={name:error.name,message:error.message,code:error.code};
  });
  for(let ticks=0;ticks<400&&!settled;ticks++) await bot.waitForTicks(1);
  if(!settled)throw new Error('Parrot cancellation observer deadline exceeded');
  await drained;
  if(!cancelledAt || cancelledAt.ground || cancelReturn!==undefined || outcome.name!=='GoalChanged')
    throw new Error('Actual airborne cancellation/GoalChanged drain missing');
  phase='after-drain';
  const afterDrain={at:Date.now(),p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround};
  await bot.waitForTicks(10);
  if(bot.entity!==body || !body.alive || bot.health!==health || JSON.stringify(bot.inventory.slots)!==inventory ||
      bot.vehicle || bot.currentWindow || bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean) ||
      bot.pathfinder.goal!==null || bot.pathfinder.isMoving() || pathEvents!==pathsAtCancel ||
      goalEventCount!==2 || goalEvents.join(',')!=='target,null' ||
      paths.some(row=>row.hasEdits||row.hasParkour))
    throw new Error('Cancellation public goal/control/item/no-replan state differs');
  return {start,end:Date.now(),bodyUuid:body.uuid,caps,origin,target:{x:298,y:-55,z:126},
    cancelledAt,outcome,afterDrain,final:{p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround},
    health,inventoryUnchanged:true,samples,pathEvents,pathsAtCancel,goalEventCount,goalEvents,paths,resets,trace,
    traceLimit:64,pathLimit:16,independentChecks:['native exact route ledger/no additional route','lease release','NoGravity false']};
} catch(error) {
  console.log(JSON.stringify({phase,p:body.position,samples,cancelledAt,outcome,pathEvents,pathsAtCancel,
    goalEventCount,goalEvents,paths,resets,trace}));
  throw error; // Preserve failure; no retry, rollback or cleanup mutation.
} finally {
  bot.removeListener('physicsTick',sample);bot.removeListener('path_update',path);
  bot.removeListener('path_reset',reset);bot.removeListener('goal_updated',changed);
}
