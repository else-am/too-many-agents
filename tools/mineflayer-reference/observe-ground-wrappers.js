// Prepared only: separate exact Panda/Camel invocations; fixture notes authoritative.
const selectedMode='panda'; // Coordinator substitutes only explicit 'panda' or 'camel'.
const expectedUuid='REPLACE_WITH_NEW_GROUND_WRAPPER_UUID';
if(!['panda','camel'].includes(selectedMode))throw new Error('Explicit ground-wrapper mode required');
const base=selectedMode==='panda'?704:744;
const body=bot.entity,caps=bot.nativeBody,origin=new Vec3(base+3.5,-60,137.5);
if(body.uuid!==expectedUuid||body.name!==selectedMode||!body.alive||!body.onGround||
   body.position.distanceTo(origin)>.2||body.isInWater||body.isInLava||body.isSleeping||bot.vehicle||
   Object.keys(body.effects).length||bot.inventory.slots.some(Boolean)||bot.currentWindow||
   bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean))
  throw new Error('Fresh empty eligible ground-wrapper fixture differs');
if(caps?.physics!==`native-${selectedMode}-ground-post-tick`||caps.canSwim!==false||
   !Number.isFinite(caps.stepHeight)||caps.stepHeight<.5||
   !Number.isFinite(body.width)||body.width<=0||!Number.isFinite(body.height)||body.height<=0||
   !Number.isFinite(body.eyeHeight)||body.eyeHeight<=0)
  throw new Error('Actual ground-wrapper capabilities/geometry differ');
await bot.waitForChunksToLoad();
const slab=(x,z)=>x>=base+12&&x<=base+15&&z>=135&&z<=139;
for(let x=base+2;x<=base+22;x++)for(let z=130;z<=144;z++){
  if(bot.blockAt(new Vec3(x,-61,z))?.name!=='stone')throw new Error('Dry supported floor differs');
  for(let y=-60;y<=-52;y++){
    const block=bot.blockAt(new Vec3(x,y,z));
    if(x===base+6&&z>=134&&z<=139&&y<=-55){
      if(block?.name!=='stone')throw new Error('Detour wall differs');
    }else if(y===-60&&slab(x,z)){
      if(block?.name!=='stone_slab'||block.getProperties().type!=='bottom')throw new Error('Supported bottom slab differs');
    }else if(block?.name!=='air')throw new Error('Body-sized dry clearance differs');
  }
}
const initialGeometry={width:body.width,height:body.height,eyeHeight:body.eyeHeight};
const inventory=JSON.stringify(bot.inventory.slots),health=bot.health,start=Date.now();
const trace=[],paths=[],resets=[],arrivals=[];
let phase='detour',samples=0,pathEvents=0,detourSelected=false,wallTouched=false,escaped=false;
const sample=()=>{
  samples++;const p=body.position,h=body.width/2;
  escaped ||= body.isInWater||body.isInLava||p.x-h<base+1||p.x+h>base+24||p.z-h<129||p.z+h>146;
  wallTouched ||= p.x+h>base+6+1e-6&&p.x-h<base+7-1e-6&&p.z+h>134+1e-6&&p.z-h<140-1e-6&&p.y< -54&&p.y+body.height> -60;
  if(samples%4===0){if(trace.length===64)trace.shift();trace.push({phase,p:p.clone(),v:body.velocity.clone(),ground:body.onGround});}
};
const path=result=>{
  pathEvents++;
  if(phase==='detour'&&result.status==='success'&&result.path.some(n=>Math.floor(n.x)===base+6&&(Math.floor(n.z)<=133||Math.floor(n.z)>=140)))detourSelected=true;
  if(paths.length===16)paths.shift();
  paths.push({phase,status:result.status,totalNodes:result.path.length,
    hasEdits:result.path.some(n=>n.toBreak.length||n.toPlace.length),hasParkour:result.path.some(n=>n.parkour===true),
    nodes:result.path.slice(0,16).map(n=>({x:n.x,y:n.y,z:n.z}))});
};
const reset=reason=>{if(resets.length<16)resets.push({phase,reason});};
const movements=new Movements(bot);
movements.canDig=false;movements.allowParkour=false;movements.allowSprinting=false;
movements.allow1by1towers=false;movements.scafoldingBlocks=[];movements.allowFreeMotion=false;
movements.exclusionAreasStep.push(b=>!b.position||b.position.x<base+2||b.position.x>base+22||
  b.position.z<130||b.position.z>144||b.position.y< -60||b.position.y> -59?200:0);
bot.pathfinder.enablePathShortcut=false;bot.pathfinder.setMovements(movements);
bot.on('physicsTick',sample);bot.on('path_update',path);bot.on('path_reset',reset);
async function arrive(x,y,z,feetY=y){
  await bot.pathfinder.goto(new goals.GoalBlock(x,y,z));
  if(bot.entity!==body||!body.onGround||body.position.distanceTo(new Vec3(x+.5,feetY,z+.5))>.4||escaped||wallTouched)
    throw new Error('Ground-wrapper supported arrival differs: '+phase);
  arrivals.push({phase,at:Date.now(),logicalGoal:{x,y,z},p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround});
}
try{
  await arrive(base+10,-60,137);
  phase='step';await arrive(base+13,-59,137,-59.5);
  phase='drop';await arrive(base+18,-60,137);
  await bot.waitForTicks(5);
  if(!detourSelected||escaped||wallTouched||bot.entity!==body||!body.alive||bot.health!==health||
     JSON.stringify(bot.inventory.slots)!==inventory||body.width!==initialGeometry.width||
     body.height!==initialGeometry.height||body.eyeHeight!==initialGeometry.eyeHeight||
     bot.nativeBody?.physics!==caps.physics||bot.vehicle||bot.currentWindow||bot.inventory.selectedItem||
     Object.values(bot.controlState).some(Boolean)||bot.pathfinder.isMoving()||bot.pathfinder.goal!==null||
     paths.some(p=>p.hasEdits||p.hasParkour))throw new Error('Ground-wrapper detour/step/drop/cleanup differs');
  return {start,end:Date.now(),selectedMode,bodyUuid:body.uuid,initialGeometry,initialCapabilities:{...caps},
    health,inventoryUnchanged:true,detourSelected,wallTouched,escaped,samples,pathEvents,arrivals,paths,resets,trace,
    final:{p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround},traceLimit:64,pathLimit:16,
    independentChecks:['adult eligibility/native flags, controller/navigation and unchanged attributes',
      'exact selected route ledger, position/items/controls/ownership/release; stepping does not prove jumping']};
}catch(error){
  console.log(JSON.stringify({selectedMode,phase,p:body.position,v:body.velocity,ground:body.onGround,
    detourSelected,wallTouched,escaped,samples,pathEvents,arrivals,paths,resets,trace}));
  throw error; // Preserve first failure; no retry or error-handler mutation.
}finally{
  bot.removeListener('physicsTick',sample);bot.removeListener('path_update',path);bot.removeListener('path_reset',reset);
}
