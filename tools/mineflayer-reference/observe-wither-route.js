// Prepared only; integratedfc5117f source reviewed; provisional dry fixture in wither-route-fixture.md.
const expectedUuid='REPLACE_WITH_NEW_WITHER_UUID';
const body=bot.entity,caps=bot.nativeBody,origin=new Vec3(1006.5,-60,139.5);
const offset=Math.max(.05,(1-body.height)/2);
if(body.uuid!==expectedUuid||body.name!=='wither'||!body.alive||!body.onGround||
    body.position.distanceTo(origin)>.2||body.isInWater||body.isInLava||body.isSleeping||bot.vehicle||
    Object.keys(body.effects).length||bot.inventory.slots.some(Boolean)||bot.currentWindow||
    bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean))
  throw new Error('Fresh empty supported Wither fixture differs');
if(!['easy','normal','hard'].includes(bot.game?.difficulty))throw new Error('Native non-Peaceful prerequisite differs');
const initialVelocity=body.velocity.clone();
const initialSpeed=Math.hypot(initialVelocity.x,initialVelocity.y,initialVelocity.z);
const initialHorizontalSpeed=Math.hypot(initialVelocity.x,initialVelocity.z);
// Supported rest can retain the native gravity tail; do not demand near-zero Y velocity.
if(!Number.isFinite(initialSpeed)||initialHorizontalSpeed>.03)throw new Error('Actual quiet supported Wither start differs');
if(caps?.physics!=='native-wither-flight-post-tick'||caps.locomotion!=='flying'||
    caps.canFly!==true||caps.canSwim!==false||caps.canJump!==false||caps.jumpHeight!==0||
    caps.maxJumpDistance!==0||caps.maxSprintJumpDistance!==0||
    !Number.isFinite(body.width)||body.width<=0||!Number.isFinite(body.height)||body.height<=0||
    !Number.isFinite(body.eyeHeight)||body.eyeHeight<=0||!Number.isFinite(offset)||
    !Number.isFinite(caps.flightTargetYOffset)||Math.abs(caps.flightTargetYOffset-offset)>1e-7)
  throw new Error('Native Wither flight capability/geometry differs');
await bot.waitForChunksToLoad();
for(let x=1002;x<=1035;x++)for(let z=130;z<=148;z++){
  if(bot.blockAt(new Vec3(x,-61,z))?.name!=='stone')throw new Error('Wither dry support differs');
  for(let y=-60;y<=-45;y++){
    const wall=x===1017&&z>=135&&z<=143&&y<=-49;
    if(bot.blockAt(new Vec3(x,y,z))?.name!==(wall?'stone':'air'))
      throw new Error('Wither wall/dry air volume differs');
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
  obstacleTouched ||= p.x+half>1017+1e-6&&p.x-half<1018-1e-6&&p.z+half>135+1e-6&&
    p.z-half<144-1e-6&&p.y< -48-1e-6&&p.y+body.height> -60+1e-6;
  if(samples%4===0){
    if(trace.length===64)trace.shift();
    trace.push({phase,p:p.clone(),v:body.velocity.clone(),ground:body.onGround});
  }
};
const path=result=>{
  pathEvents++;
  if(phase==='detour'&&result.status==='success'&&result.path.some(node=>
      Math.floor(node.x)===1017&&(Math.floor(node.z)<=130||Math.floor(node.z)>=144)))detourSelected=true;
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
movements.exclusionAreasStep.push(block=>!block.position||block.position.x<1002||block.position.x>1035||
  block.position.z<130||block.position.z>148||block.position.y< -60||block.position.y> -55?200:0);
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
    throw new Error('Native Wither selected-cell/offset/speed/support arrival differs: '+phase);
  arrivals.push({phase,at:Date.now(),cell:{x,y,z},targetY,p:p.clone(),v:v.clone(),ground:body.onGround,
    horizontalError,verticalError,speed});
}
try{
  await arrive(1006,-56,139,false);
  phase='detour';await arrive(1028,-56,139,false);
  phase='landing';await arrive(1028,-60,139,true);
  await bot.waitForTicks(5);
  if(!airborne||!ascent||!descent||!detourSelected||obstacleTouched||fluidEntered||!body.onGround||
      bot.entity!==body||!body.alive||bot.health!==health||JSON.stringify(bot.inventory.slots)!==inventory||
      body.width!==geometry.width||body.height!==geometry.height||body.eyeHeight!==geometry.eyeHeight||
      bot.nativeBody?.physics!=='native-wither-flight-post-tick'||bot.vehicle||bot.currentWindow||
      bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean)||bot.pathfinder.isMoving()||
      bot.pathfinder.goal!==null||paths.some(row=>row.hasEdits||row.hasParkour))
    throw new Error('Wither flight/landing/public cleanup differs');
  return {start,end:Date.now(),bodyUuid:body.uuid,origin,initialVelocity,initialSpeed,initialHorizontalSpeed,difficulty:bot.game.difficulty,geometry,capabilities,arrivals,healthBefore:health,healthAfter:bot.health,
    final:{p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround},inventoryUnchanged:true,
    samples,pathEvents,airborne,ascent,descent,detourSelected,obstacleTouched,fluidEntered,paths,resets,trace,
    traceLimit:64,pathLimit:16,nodeLimit:16,independentChecks:['native WAIT gravity release and actual outer momentum/ownership cleanup',
      'actual controller/navigation/attributes/support and route ledger','captured actual invulnerability/powered threshold/three head IDs and resolved targets; NoAI timers untouched']};
}catch(error){
  console.log(JSON.stringify({phase,p:body.position,v:body.velocity,samples,pathEvents,airborne,ascent,descent,
    detourSelected,obstacleTouched,fluidEntered,arrivals,paths,resets,trace}));
  throw error; // No retry, reroute or error-handler cleanup mutation.
}finally{
  bot.removeListener('physicsTick',sample);bot.removeListener('path_update',path);bot.removeListener('path_reset',reset);
}
