// Prepared only; exact fresh adult Sniffer. See sniffer-route-fixture.md.
const expectedUuid='REPLACE_WITH_NEW_SNIFFER_UUID';
const body=bot.entity,caps=bot.nativeBody,origin=new Vec3(804.5,-60,137.5);
if(body.uuid!==expectedUuid||body.name!=='sniffer'||!body.alive||!body.onGround||
   body.position.distanceTo(origin)>.2||body.isInWater||body.isInLava||body.isSleeping||bot.vehicle||
   Object.keys(body.effects).length||bot.inventory.slots.some(Boolean)||bot.currentWindow||
   bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean))
  throw new Error('Fresh empty dry adult Sniffer fixture differs');
if(caps?.physics!=='native-sniffer-ground-post-tick'||caps.canSwim!==false||caps.canJump!==true||
   !Number.isFinite(caps.jumpHeight)||caps.jumpHeight<1||!Number.isFinite(caps.stepHeight)||caps.stepHeight>=1||caps.stepHeight<0||
   !Number.isFinite(body.width)||body.width<=0||!Number.isFinite(body.height)||body.height<=0||
   !Number.isFinite(body.eyeHeight)||body.eyeHeight<=0)
  throw new Error('Actual native Sniffer cannot establish required one-block jump fixture');
await bot.waitForChunksToLoad();
for(let x=802;x<=824;x++)for(let z=130;z<=144;z++){
  if(bot.blockAt(new Vec3(x,-61,z))?.name!=='stone')throw new Error('Dry Sniffer floor differs');
  for(let y=-60;y<=-52;y++){
    const platform=y===-60&&x>=813&&x<=817&&z>=135&&z<=139;
    if(bot.blockAt(new Vec3(x,y,z))?.name!==(platform?'stone':'air'))throw new Error('Raised support/headroom differs');
  }
}
const inventory=JSON.stringify(bot.inventory.slots),health=bot.health,start=Date.now();
const geometry={width:body.width,height:body.height,eyeHeight:body.eyeHeight};
const trace=[],paths=[],resets=[],arrivals=[];
let phase='horizontal',samples=0,pathEvents=0,escaped=false,airborneAscent=false,dropObserved=false;
const sample=()=>{
  samples++;const p=body.position,h=body.width/2;
  escaped ||= body.isInWater||body.isInLava||p.x-h<801||p.x+h>826||p.z-h<129||p.z+h>146;
  airborneAscent ||= phase==='jump'&&!body.onGround&&p.y>origin.y+.15;
  dropObserved ||= phase==='drop'&&!body.onGround&&body.velocity.y<-.01;
  if(samples%4===0){if(trace.length===64)trace.shift();trace.push({phase,p:p.clone(),v:body.velocity.clone(),ground:body.onGround});}
};
const path=result=>{
  pathEvents++;if(paths.length===16)paths.shift();
  paths.push({phase,status:result.status,totalNodes:result.path.length,
    hasEdits:result.path.some(n=>n.toBreak.length||n.toPlace.length),
    nodes:result.path.slice(0,16).map(n=>({x:n.x,y:n.y,z:n.z,parkour:n.parkour===true}))});
};
const reset=reason=>{if(resets.length<16)resets.push({phase,reason});};
const movements=new Movements(bot);
movements.canDig=false;movements.allowSprinting=false;movements.allowParkour=false;movements.maxDropDown=0;
movements.allow1by1towers=false;movements.scafoldingBlocks=[];movements.allowFreeMotion=false;
movements.exclusionAreasStep.push(b=>!b.position||b.position.x<802||b.position.x>824||
  b.position.z<130||b.position.z>144||b.position.y< -60||b.position.y> -59?200:0);
bot.pathfinder.enablePathShortcut=false;bot.pathfinder.setMovements(movements);
bot.on('physicsTick',sample);bot.on('path_update',path);bot.on('path_reset',reset);
async function arrive(x,y,z){
  await bot.pathfinder.goto(new goals.GoalBlock(x,y,z));
  if(bot.entity!==body||!body.onGround||body.position.distanceTo(new Vec3(x+.5,y,z+.5))>.4||escaped)
    throw new Error('Sniffer actual supported arrival differs: '+phase);
  arrivals.push({phase,at:Date.now(),goal:{x,y,z},p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround});
}
try{
  await arrive(810,-60,137);
  phase='jump';await arrive(815,-59,137);
  if(!airborneAscent)throw new Error('Required rise did not expose actual native airborne ascent');
  // Native feet-to-support drop test includes one block of support depth.
  movements.maxDropDown=2;bot.pathfinder.setMovements(movements);
  phase='drop';await arrive(821,-60,137);await bot.waitForTicks(5);
  if(!dropObserved||escaped||bot.entity!==body||!body.alive||bot.health!==health||
     JSON.stringify(bot.inventory.slots)!==inventory||body.width!==geometry.width||body.height!==geometry.height||
     body.eyeHeight!==geometry.eyeHeight||bot.nativeBody?.physics!=='native-sniffer-ground-post-tick'||
     !body.onGround||bot.vehicle||bot.currentWindow||bot.inventory.selectedItem||
     Object.values(bot.controlState).some(Boolean)||bot.pathfinder.isMoving()||bot.pathfinder.goal!==null||paths.some(p=>p.hasEdits))
    throw new Error('Sniffer horizontal/jump/drop/unchanged state cleanup differs');
  return {start,end:Date.now(),bodyUuid:body.uuid,initialGeometry:geometry,initialCapabilities:{...caps},
    health,inventoryUnchanged:true,airborneAscent,dropObserved,escaped,samples,pathEvents,arrivals,paths,resets,trace,
    final:{p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround},traceLimit:64,pathLimit:16,
    independentChecks:['actual adult state/attributes/controller operation/modifier/yaw unchanged except native preparation',
      'native selected action ledger, body position/items/control ownership and release; conditional kick branch not separately claimed']};
}catch(error){
  console.log(JSON.stringify({phase,p:body.position,v:body.velocity,ground:body.onGround,airborneAscent,dropObserved,
    escaped,samples,pathEvents,arrivals,paths,resets,trace}));throw error;
}finally{
  bot.removeListener('physicsTick',sample);bot.removeListener('path_update',path);bot.removeListener('path_reset',reset);
}
