// Prepared only; source reviewed against integrated Turtle 6ff60fb / root1938c0f. Native execution remains unrun.
// Provisional source-water fixture in turtle-route-fixture.md.
const expectedUuid='REPLACE_WITH_NEW_TURTLE_UUID';
const body=bot.entity,caps=bot.nativeBody,origin=body.position.clone();
// Actual quiet full-water Y is recorded; neither floor-rest nor floating is assumed.
const offset=Math.max(.05,(1-body.height)/2);
if(body.uuid!==expectedUuid||body.name!=='turtle'||!body.alive||
    Math.hypot(origin.x-844.5,origin.z-137.5)>.2||!Number.isFinite(origin.y)||origin.y< -60||origin.y> -59.25||!body.isInWater||body.isInLava||body.isSleeping||bot.vehicle||
    Object.keys(body.effects).length||bot.inventory.slots.some(Boolean)||bot.currentWindow||
    bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean))
  throw new Error('Fresh empty quiet submerged Turtle fixture differs');
if(caps?.physics!=='native-turtle-submerged-post-tick'||caps.locomotion!=='submerged'||
    caps.canSwim!==true||caps.canJump!==false||caps.jumpHeight!==0||
    caps.maxJumpDistance!==0||caps.maxSprintJumpDistance!==0||!Number.isFinite(offset)||
    !Number.isFinite(caps.swimTargetYOffset)||Math.abs(caps.swimTargetYOffset-offset)>1e-7||
    !Number.isFinite(body.width)||body.width<=0||!Number.isFinite(body.height)||body.height<=0||
    !Number.isFinite(body.eyeHeight)||body.eyeHeight<=0)
  throw new Error('Native ordinary Turtle water capabilities/geometry differ');
const initialSpeed=Math.hypot(body.velocity.x,body.velocity.y,body.velocity.z);
if(!Number.isFinite(initialSpeed)||initialSpeed>.03)throw new Error('Quiet Turtle start differs');
await bot.waitForChunksToLoad();
const sourceWater=p=>{
  const block=bot.blockAt(p);
  return block?.name==='water'&&Number(block.getProperties().level)===0;
};
function submerged(){
  const p=body.position,half=body.width/2,top=p.y+Math.max(body.height,body.eyeHeight);
  for(let y=Math.floor(p.y+1e-7);y<Math.ceil(top-1e-7);y++)
    for(let x=Math.floor(p.x-half+1e-7);x<Math.ceil(p.x+half-1e-7);x++)
      for(let z=Math.floor(p.z-half+1e-7);z<Math.ceil(p.z+half-1e-7);z++)
        if(!sourceWater(new Vec3(x,y,z))||!sourceWater(new Vec3(x,y+1,z)))return false;
  return true;
}
for(let x=842;x<=863;x++)for(let z=132;z<=142;z++){
  if(bot.blockAt(new Vec3(x,-61,z))?.name!=='stone')throw new Error('Turtle pool bottom differs');
  for(let y=-60;y<=-52;y++){
    const wall=x===849&&z>=134&&z<=139;
    if(wall?bot.blockAt(new Vec3(x,y,z))?.name!=='stone':!sourceWater(new Vec3(x,y,z)))
      throw new Error('Full-depth wall/source-water volume differs');
  }
}
if(!submerged())throw new Error('Actual Turtle body/eyes not wholly submerged');
const inventory=JSON.stringify(bot.inventory.slots),health=bot.health,start=Date.now();
const initialGeometry={width:body.width,height:body.height,eyeHeight:body.eyeHeight};
const initialCapabilities={...caps},trace=[],paths=[],resets=[],arrivals=[];
let phase='up',samples=0,pathEvents=0,escaped=false,obstacleTouched=false,detourSelected=false;
let upObserved=false,downObserved=false,downStartY=null;
const sample=()=>{
  samples++;
  const p=body.position,half=body.width/2;
  escaped ||= !body.isInWater||!submerged();
  obstacleTouched ||= p.x+half>849+1e-6&&p.x-half<850-1e-6&&p.z+half>134+1e-6&&
    p.z-half<140-1e-6&&p.y< -51-1e-6&&p.y+body.height> -60+1e-6;
  upObserved ||= phase==='up'&&p.y>origin.y+.5;
  downObserved ||= phase==='down'&&downStartY!==null&&p.y<downStartY-.5;
  if(samples%4===0){
    if(trace.length===64)trace.shift();
    trace.push({phase,p:p.clone(),v:body.velocity.clone(),ground:body.onGround});
  }
};
const path=result=>{
  pathEvents++;
  if(phase==='detour'&&result.status==='success'&&result.path.some(node=>
      Math.floor(node.x)===849&&(Math.floor(node.z)<=133||Math.floor(node.z)>=140)))detourSelected=true;
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
movements.exclusionAreasStep.push(block=>!block.position||block.position.x<842||block.position.x>863||
  block.position.z<132||block.position.z>142||block.position.y< -60||block.position.y> -55?200:0);
bot.pathfinder.enablePathShortcut=false;bot.pathfinder.setMovements(movements);
bot.on('physicsTick',sample);bot.on('path_update',path);bot.on('path_reset',reset);
async function arrive(x,y,z){
  await bot.pathfinder.goto(new goals.GoalBlock(x,y,z));
  const p=body.position,v=body.velocity;
  const horizontalError=Math.hypot(p.x-(x+.5),p.z-(z+.5)),speed=Math.hypot(v.x,v.y,v.z);
  const verticalError=Math.abs(p.y-(y+offset));
  // Check the native horizontal and vertical tolerances separately.
  if(bot.entity!==body||!p.floored().equals(new Vec3(x,y,z))||horizontalError>=.12||!Number.isFinite(verticalError)||verticalError>=.12||
      !Number.isFinite(speed)||speed>.03||escaped||obstacleTouched||!submerged())
    throw new Error('Native Turtle selected-cell/XZ/residual-speed arrival differs: '+phase);
  arrivals.push({phase,at:Date.now(),cell:{x,y,z},p:p.clone(),v:v.clone(),horizontalError,verticalError,speed,offset});
}
try{
  await arrive(844,-57,137);
  phase='detour';await arrive(858,-57,137);
  phase='down';downStartY=body.position.y;await arrive(858,-59,137);
  await bot.waitForTicks(5);
  if(!upObserved||!downObserved||!detourSelected||escaped||obstacleTouched||!submerged()||
      bot.entity!==body||!body.alive||bot.health!==health||JSON.stringify(bot.inventory.slots)!==inventory||
      body.width!==initialGeometry.width||body.height!==initialGeometry.height||body.eyeHeight!==initialGeometry.eyeHeight||
      bot.nativeBody?.physics!=='native-turtle-submerged-post-tick'||bot.vehicle||bot.currentWindow||
      bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean)||bot.pathfinder.isMoving()||
      bot.pathfinder.goal!==null||paths.some(row=>row.hasEdits||row.hasParkour))
    throw new Error('Turtle selected up/detour/down/public cleanup differs');
  return {start,end:Date.now(),bodyUuid:body.uuid,origin,initialSpeed,initialGeometry,initialCapabilities,arrivals,
    final:{p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround},health,inventoryUnchanged:true,
    samples,pathEvents,upObserved,downObserved,detourSelected,escaped,obstacleTouched,paths,resets,trace,
    traceLimit:64,pathLimit:16,independentChecks:['native home/Age/target/going-home/controller/navigation and speed history; no private guest getters',
      'exact route ledger and arrival position/velocity','unchanged native attributes/items and release']};
}catch(error){
  console.log(JSON.stringify({phase,p:body.position,v:body.velocity,samples,pathEvents,upObserved,downObserved,
    detourSelected,escaped,obstacleTouched,arrivals,paths,resets,trace}));
  throw error; // Preserve the first failure; no retry, rollback or cleanup mutation.
}finally{
  bot.removeListener('physicsTick',sample);bot.removeListener('path_update',path);bot.removeListener('path_reset',reset);
}
