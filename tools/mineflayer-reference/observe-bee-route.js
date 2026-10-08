// Prepared only; reviewed against Bee implementation 510d45c. Native execution remains unrun.
// Provisional dry fixture in bee-route-fixture.md.
const expectedUuid='REPLACE_WITH_NEW_BEE_UUID';
const body=bot.entity,caps=bot.nativeBody,origin=new Vec3(506.5,-60,110.5);
const offset=Math.max(.05,(1-body.height)/2);
if(body.uuid!==expectedUuid||body.name!=='bee'||!body.alive||!body.onGround||
    body.position.distanceTo(origin)>.2||body.isInWater||body.isInLava||body.isSleeping||bot.vehicle||
    Object.keys(body.effects).length||bot.inventory.slots.some(Boolean)||bot.currentWindow||
    bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean))
  throw new Error('Fresh empty supported adult Bee fixture differs');
if(caps?.physics!=='native-bee-flight-post-tick'||caps.locomotion!=='flying'||
    caps.canFly!==true||caps.canSwim!==false||caps.canJump!==false||caps.jumpHeight!==0||
    caps.maxJumpDistance!==0||caps.maxSprintJumpDistance!==0||
    !Number.isFinite(body.width)||body.width<=0||!Number.isFinite(body.height)||body.height<=0||
    !Number.isFinite(body.eyeHeight)||body.eyeHeight<=0||!Number.isFinite(offset)||
    !Number.isFinite(caps.flightTargetYOffset)||Math.abs(caps.flightTargetYOffset-offset)>1e-7)
  throw new Error('Native Bee flight capability/geometry differs');
await bot.waitForChunksToLoad();
for(let x=504;x<=517;x++)for(let z=106;z<=114;z++){
  if(bot.blockAt(new Vec3(x,-61,z))?.name!=='stone')throw new Error('Bee dry support differs');
  for(let y=-60;y<=-51;y++){
    const wall=x===511&&z>=107&&z<=112&&y<=-52;
    if(bot.blockAt(new Vec3(x,y,z))?.name!==(wall?'stone':'air'))
      throw new Error('Bee wall/dry air volume differs');
  }
}
const inventory=JSON.stringify(bot.inventory.slots),health=bot.health,start=Date.now();
const geometry={width:body.width,height:body.height,eyeHeight:body.eyeHeight},capabilities={...caps};
const trace=[],paths=[],resets=[],arrivals=[];
let phase='up',samples=0,pathEvents=0,airborne=false,ascent=false,descent=false;
let obstacleTouched=false,fluidEntered=false,detourSelected=false;
const sample=()=>{
  samples++;
  const p=body.position,half=body.width/2;
  airborne ||= !body.onGround;ascent ||= phase==='up'&&!body.onGround&&p.y>origin.y+.5;
  descent ||= phase==='landing'&&!body.onGround&&body.velocity.y<-.01;
  fluidEntered ||= body.isInWater||body.isInLava;
  obstacleTouched ||= p.x+half>511+1e-6&&p.x-half<512-1e-6&&p.z+half>107+1e-6&&
    p.z-half<113-1e-6&&p.y< -51-1e-6&&p.y+body.height> -60+1e-6;
  if(samples%4===0){
    if(trace.length===64)trace.shift();
    trace.push({phase,p:p.clone(),v:body.velocity.clone(),ground:body.onGround});
  }
};
const path=result=>{
  pathEvents++;
  if(phase==='detour'&&result.status==='success'&&result.path.some(node=>
      Math.floor(node.x)===511&&(Math.floor(node.z)<=106||Math.floor(node.z)>=113)))detourSelected=true;
  if(paths.length===16)paths.shift();
  paths.push({phase,status:result.status,totalNodes:result.path.length,
    hasEdits:result.path.some(node=>node.toBreak.length||node.toPlace.length),
    hasParkour:result.path.some(node=>node.parkour===true),
    nodes:result.path.slice(0,16).map(node=>({x:node.x,y:node.y,z:node.z}))});
};
const reset=reason=>{if(resets.length<16)resets.push({phase,reason});};
const movements=new Movements(bot);
movements.canDig=false;movements.allowParkour=false;movements.allowSprinting=false;
movements.allow1by1towers=false;movements.scafoldingBlocks=[];movements.allowFreeMotion=false;
movements.exclusionAreasStep.push(block=>!block.position||block.position.x<504||block.position.x>517||
  block.position.z<106||block.position.z>114||block.position.y< -60||block.position.y> -55?200:0);
bot.pathfinder.enablePathShortcut=false;bot.pathfinder.setMovements(movements);
bot.on('physicsTick',sample);bot.on('path_update',path);bot.on('path_reset',reset);
async function arrive(x,y,z,landing){
  await bot.pathfinder.goto(new goals.GoalBlock(x,y,z));
  const p=body.position,v=body.velocity,targetY=y+(landing?0:offset);
  const horizontalError=Math.hypot(p.x-(x+.5),p.z-(z+.5)),verticalError=Math.abs(p.y-targetY);
  const speed=Math.hypot(v.x,v.y,v.z);
  if(bot.entity!==body||!p.floored().equals(new Vec3(x,y,z))||horizontalError>=.12||
      !Number.isFinite(verticalError)||verticalError>=.12||!Number.isFinite(speed)||speed>.03||
      landing&&!body.onGround||obstacleTouched||fluidEntered)
    throw new Error('Native Bee selected-cell/offset/speed/support arrival differs: '+phase);
  arrivals.push({phase,at:Date.now(),cell:{x,y,z},targetY,p:p.clone(),v:v.clone(),ground:body.onGround,
    horizontalError,verticalError,speed});
}
try{
  await arrive(506,-57,110,false);
  phase='detour';await arrive(514,-57,110,false);
  phase='landing';await arrive(514,-60,110,true);
  await bot.waitForTicks(5);
  if(!airborne||!ascent||!descent||!detourSelected||obstacleTouched||fluidEntered||!body.onGround||
      bot.entity!==body||!body.alive||bot.health<health||JSON.stringify(bot.inventory.slots)!==inventory||
      body.width!==geometry.width||body.height!==geometry.height||body.eyeHeight!==geometry.eyeHeight||
      bot.nativeBody?.physics!=='native-bee-flight-post-tick'||bot.vehicle||bot.currentWindow||
      bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean)||bot.pathfinder.isMoving()||
      bot.pathfinder.goal!==null||paths.some(row=>row.hasEdits||row.hasParkour))
    throw new Error('Bee flight/landing/public cleanup differs');
  return {start,end:Date.now(),bodyUuid:body.uuid,geometry,capabilities,arrivals,healthBefore:health,healthAfter:bot.health,
    final:{p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround},inventoryUnchanged:true,
    samples,pathEvents,airborne,ascent,descent,detourSelected,obstacleTouched,fluidEntered,paths,resets,trace,
    traceLimit:64,pathLimit:16,nodeLimit:16,independentChecks:['native noGravity and momentum/ownership cleanup',
      'actual controller/navigation/attributes/support and route ledger','native adult Age, nectar/anger/sting state and NoAI timer split (no private guest getters)']};
}catch(error){
  console.log(JSON.stringify({phase,p:body.position,v:body.velocity,samples,pathEvents,airborne,ascent,descent,
    detourSelected,obstacleTouched,fluidEntered,arrivals,paths,resets,trace}));
  throw error; // No retry, reroute or error-handler cleanup mutation.
}finally{
  bot.removeListener('physicsTick',sample);bot.removeListener('path_update',path);bot.removeListener('path_reset',reset);
}
