// Prepared only; provisional isolated fixture in drowned-water-fixture.md.
const expectedUuid='REPLACE_WITH_NEW_DROWNED_WATER_UUID';
const body=bot.entity,caps=bot.nativeBody,origin=new Vec3(402.5,-60,110.5);
if(body.uuid!==expectedUuid||body.name!=='drowned'||!body.alive||!body.onGround||
    body.position.distanceTo(origin)>.2||!body.isInWater||body.isInLava||body.isSleeping||bot.vehicle||
    Object.keys(body.effects).length||bot.inventory.slots.some(Boolean)||bot.currentWindow||
    bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean))
  throw new Error('Fresh empty settled adult Drowned water fixture differs');
if(caps?.physics!=='native-drowned-water-post-tick'||caps.locomotion!=='submerged'||
    caps.canSwim!==true||caps.canJump!==false||caps.jumpHeight!==0||
    caps.maxJumpDistance!==0||caps.maxSprintJumpDistance!==0||caps.swimTargetYOffset!==.5||
    !Number.isFinite(body.width)||body.width<=0||!Number.isFinite(body.height)||body.height<=0||
    !Number.isFinite(body.eyeHeight)||body.eyeHeight<=0)
  throw new Error('Native no-intent Drowned water capabilities/geometry differ');
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
for(let x=400;x<=413;x++)for(let z=106;z<=114;z++){
  if(bot.blockAt(new Vec3(x,-61,z))?.name!=='stone')throw new Error('Drowned pool bottom differs');
  for(let y=-60;y<=-52;y++){
    const wall=x===407&&z>=107&&z<=112;
    if(wall?bot.blockAt(new Vec3(x,y,z))?.name!=='stone':!sourceWater(new Vec3(x,y,z)))
      throw new Error('Full-depth wall/source-water volume differs');
  }
}
if(!submerged())throw new Error('Actual Drowned body/eyes not wholly submerged');
const inventory=JSON.stringify(bot.inventory.slots),health=bot.health,start=Date.now();
const initialGeometry={width:body.width,height:body.height,eyeHeight:body.eyeHeight};
const initialCapabilities={...caps},trace=[],paths=[],resets=[],arrivals=[];
let phase='up',samples=0,pathEvents=0,escaped=false,obstacleTouched=false,detourSelected=false;
let upObserved=false,downObserved=false,downStartY=null;
const sample=()=>{
  samples++;
  const p=body.position,half=body.width/2;
  escaped ||= !body.isInWater||!submerged();
  obstacleTouched ||= p.x+half>407+1e-6&&p.x-half<408-1e-6&&p.z+half>107+1e-6&&
    p.z-half<113-1e-6&&p.y< -51-1e-6&&p.y+body.height> -60+1e-6;
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
      Math.floor(node.x)===407&&(Math.floor(node.z)<=106||Math.floor(node.z)>=113)))detourSelected=true;
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
movements.exclusionAreasStep.push(block=>!block.position||block.position.x<400||block.position.x>413||
  block.position.z<106||block.position.z>114||block.position.y< -60||block.position.y> -55?200:0);
bot.pathfinder.enablePathShortcut=false;bot.pathfinder.setMovements(movements);
bot.on('physicsTick',sample);bot.on('path_update',path);bot.on('path_reset',reset);
async function arrive(x,y,z){
  await bot.pathfinder.goto(new goals.GoalBlock(x,y,z));
  const p=body.position,v=body.velocity;
  const horizontalError=Math.hypot(p.x-(x+.5),p.z-(z+.5)),speed=Math.hypot(v.x,v.y,v.z);
  // Drowned water promises a selected feet cell + low motion, not Fish center-Y tolerance.
  if(bot.entity!==body||!p.floored().equals(new Vec3(x,y,z))||horizontalError>=.12||
      !Number.isFinite(speed)||speed>.03||escaped||obstacleTouched||!submerged())
    throw new Error('Native Drowned selected-cell/XZ/residual-speed arrival differs: '+phase);
  arrivals.push({phase,at:Date.now(),cell:{x,y,z},p:p.clone(),v:v.clone(),horizontalError,speed,offset:.5});
}
try{
  await arrive(402,-57,110);
  phase='detour';await arrive(410,-57,110);
  phase='down';downStartY=body.position.y;await arrive(410,-59,110);
  await bot.waitForTicks(5);
  if(!upObserved||!downObserved||!detourSelected||escaped||obstacleTouched||!submerged()||
      bot.entity!==body||!body.alive||bot.health!==health||JSON.stringify(bot.inventory.slots)!==inventory||
      body.width!==initialGeometry.width||body.height!==initialGeometry.height||body.eyeHeight!==initialGeometry.eyeHeight||
      bot.nativeBody?.physics!=='native-drowned-water-post-tick'||bot.vehicle||bot.currentWindow||
      bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean)||bot.pathfinder.isMoving()||
      bot.pathfinder.goal!==null||paths.some(row=>row.hasEdits||row.hasParkour))
    throw new Error('Drowned selected up/detour/down/public cleanup differs');
  return {start,end:Date.now(),bodyUuid:body.uuid,initialGeometry,initialCapabilities,arrivals,
    final:{p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround},health,inventoryUnchanged:true,
    samples,pathEvents,upObserved,downObserved,detourSelected,escaped,obstacleTouched,paths,resets,trace,
    traceLimit:64,pathLimit:16,independentChecks:['actual native intent/navigation/canFloat admission (no invented getter)',
      'exact route ledger and arrival position/velocity','unchanged native attributes/items and release']};
}catch(error){
  console.log(JSON.stringify({phase,p:body.position,v:body.velocity,samples,pathEvents,upObserved,downObserved,
    detourSelected,escaped,obstacleTouched,arrivals,paths,resets,trace}));
  throw error; // Preserve the first failure; no retry, rollback or cleanup mutation.
}finally{
  bot.removeListener('physicsTick',sample);bot.removeListener('path_update',path);bot.removeListener('path_reset',reset);
}
