// Prepared only; integrated13ef17e/current3605a84 source reviewed; provisional dry fixture in ghast-route-fixture.md.
const expectedUuid='REPLACE_WITH_NEW_GHAST_UUID';
const body=bot.entity,caps=bot.nativeBody,origin=new Vec3(894.5,-59,139.5);
const offset=Math.max(.05,(1-body.height)/2);
if(body.uuid!==expectedUuid||body.name!=='ghast'||!body.alive||
    body.position.distanceTo(origin)>.2||body.isInWater||body.isInLava||body.isSleeping||bot.vehicle||
    Object.keys(body.effects).length||bot.inventory.slots.some(Boolean)||bot.currentWindow||
    bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean))
  throw new Error('Fresh empty dry Ghast fixture differs');
if(!['easy','normal','hard'].includes(bot.game?.difficulty))throw new Error('Native non-Peaceful difficulty prerequisite differs');
const initialSpeed=Math.hypot(body.velocity.x,body.velocity.y,body.velocity.z);
if(!Number.isFinite(initialSpeed)||initialSpeed>.03)throw new Error('Actual quiet Ghast start differs');
if(caps?.physics!=='native-ghast-flight-post-tick'||caps.locomotion!=='flying'||
    caps.canFly!==true||caps.canSwim!==false||caps.canJump!==false||caps.jumpHeight!==0||
    caps.maxJumpDistance!==0||caps.maxSprintJumpDistance!==0||
    !Number.isFinite(body.width)||body.width<=0||!Number.isFinite(body.height)||body.height<=0||
    !Number.isFinite(body.eyeHeight)||body.eyeHeight<=0||!Number.isFinite(offset)||
    !Number.isFinite(caps.flightTargetYOffset)||Math.abs(caps.flightTargetYOffset-offset)>1e-7)
  throw new Error('Native Ghast flight capability/geometry differs');
await bot.waitForChunksToLoad();
for(let x=886;x<=921;x++)for(let z=128;z<=150;z++){
  if(bot.blockAt(new Vec3(x,-61,z))?.name!=='stone')throw new Error('Ghast dry support differs');
  for(let y=-60;y<=-45;y++){
    const wall=x===903&&z>=135&&z<=143&&y<=-49;
    if(bot.blockAt(new Vec3(x,y,z))?.name!==(wall?'stone':'air'))
      throw new Error('Ghast wall/dry air volume differs');
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
  obstacleTouched ||= p.x+half>903+1e-6&&p.x-half<904-1e-6&&p.z+half>135+1e-6&&
    p.z-half<144-1e-6&&p.y< -48-1e-6&&p.y+body.height> -60+1e-6;
  if(samples%4===0){
    if(trace.length===64)trace.shift();
    trace.push({phase,p:p.clone(),v:body.velocity.clone(),ground:body.onGround});
  }
};
const path=result=>{
  pathEvents++;
  if(phase==='detour'&&result.status==='success'&&result.path.some(node=>
      Math.floor(node.x)===903&&(Math.floor(node.z)<=128||Math.floor(node.z)>=144)))detourSelected=true;
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
movements.exclusionAreasStep.push(block=>!block.position||block.position.x<886||block.position.x>921||
  block.position.z<128||block.position.z>150||block.position.y< -60||block.position.y> -55?200:0);
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
    throw new Error('Native Ghast selected-cell/offset/speed/support arrival differs: '+phase);
  arrivals.push({phase,at:Date.now(),cell:{x,y,z},targetY,p:p.clone(),v:v.clone(),ground:body.onGround,
    horizontalError,verticalError,speed});
}
try{
  await arrive(894,-56,139,false);
  phase='detour';await arrive(914,-56,139,false);
  phase='landing';await arrive(914,-60,139,true);
  await bot.waitForTicks(5);
  if(!airborne||!ascent||!descent||!detourSelected||obstacleTouched||fluidEntered||!body.onGround||
      bot.entity!==body||!body.alive||bot.health!==health||JSON.stringify(bot.inventory.slots)!==inventory||
      body.width!==geometry.width||body.height!==geometry.height||body.eyeHeight!==geometry.eyeHeight||
      bot.nativeBody?.physics!=='native-ghast-flight-post-tick'||bot.vehicle||bot.currentWindow||
      bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean)||bot.pathfinder.isMoving()||
      bot.pathfinder.goal!==null||paths.some(row=>row.hasEdits||row.hasParkour))
    throw new Error('Ghast flight/landing/public cleanup differs');
  return {start,end:Date.now(),bodyUuid:body.uuid,origin,initialSpeed,difficulty:bot.game.difficulty,geometry,capabilities,arrivals,healthBefore:health,healthAfter:bot.health,
    final:{p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround},inventoryUnchanged:true,
    samples,pathEvents,airborne,ascent,descent,detourSelected,obstacleTouched,fluidEntered,paths,resets,trace,
    traceLimit:64,pathLimit:16,nodeLimit:16,independentChecks:['native noGravity and momentum/ownership cleanup',
      'actual controller/navigation/attributes/support and route ledger','actual native pulse countdown/RNG history and target/charging state; private diagnostic limits explicit']};
}catch(error){
  console.log(JSON.stringify({phase,p:body.position,v:body.velocity,samples,pathEvents,airborne,ascent,descent,
    detourSelected,obstacleTouched,fluidEntered,arrivals,paths,resets,trace}));
  throw error; // No retry, reroute or error-handler cleanup mutation.
}finally{
  bot.removeListener('physicsTick',sample);bot.removeListener('path_update',path);bot.removeListener('path_reset',reset);
}
