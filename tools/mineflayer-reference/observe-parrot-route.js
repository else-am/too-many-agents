// Prepared only. Fresh exact Parrot and inspected fixture in parrot-route-fixture.md.
const expectedUuid = 'REPLACE_WITH_NEW_PARROT_UUID';
const body = bot.entity, caps = bot.nativeBody, origin = new Vec3(280.5,-60,110.5);
if (body.uuid !== expectedUuid || body.name !== 'parrot' || !body.alive || !body.onGround ||
    body.position.distanceTo(origin) > .2 || body.isInWater || body.isInLava || bot.vehicle ||
    bot.inventory.slots.some(Boolean) || bot.currentWindow || bot.inventory.selectedItem ||
    Object.values(bot.controlState).some(Boolean)) throw new Error('Empty supported Parrot fixture differs');
if (caps?.physics !== 'native-parrot-flight-post-tick' || caps.locomotion !== 'flying' ||
    caps.canFly !== true || caps.canJump !== false || caps.canSwim !== false ||
    !Number.isFinite(body.width) || !Number.isFinite(body.height) || !Number.isFinite(body.eyeHeight) ||
    !Number.isFinite(caps.flightTargetYOffset) ||
    Math.abs(caps.flightTargetYOffset-Math.max(.05,(1-body.height)/2)) > 1e-6)
  throw new Error('Parrot native capability contract differs');
await bot.waitForChunksToLoad();
for (let z=108;z<=112;z++) for (let y=-60;y<=-55;y++)
  if (bot.blockAt(new Vec3(285,y,z))?.name !== 'stone') throw new Error('Parrot wall differs');
if (bot.blockAt(new Vec3(288,-61,110))?.name !== 'stone') throw new Error('Parrot landing support differs');
const inventory=JSON.stringify(bot.inventory.slots), health=bot.health, start=Date.now();
let phase='up', samples=0, airborne=false, ascent=false, descent=false, obstacleTouched=false;
const trace=[], paths=[], arrivals=[], resets=[];
const sample=()=>{
  samples++;
  const p=body.position, half=body.width/2;
  airborne ||= !body.onGround; ascent ||= !body.onGround&&body.velocity.y>.01;
  descent ||= phase==='landing'&&!body.onGround&&body.velocity.y<-.01;
  obstacleTouched ||= p.x+half>285+1e-6&&p.x-half<286-1e-6&&p.z+half>108+1e-6&&
    p.z-half<113-1e-6&&p.y< -54-1e-6&&p.y+body.height> -60+1e-6;
  if (samples%8===0) { if(trace.length===64)trace.shift(); trace.push({phase,p:p.clone(),v:body.velocity.clone(),ground:body.onGround}); }
};
const path=r=>{ if(paths.length===16)paths.shift();paths.push({phase,status:r.status,nodes:r.path.slice(0,64).map(n=>
  ({x:n.x,y:n.y,z:n.z,edits:n.toBreak.length+n.toPlace.length,parkour:n.parkour===true}))}); };
const reset=reason=>{if(resets.length<16)resets.push({phase,reason});};
const m=new Movements(bot);m.canDig=false;m.allowParkour=false;m.allowSprinting=false;
m.allow1by1towers=false;m.scafoldingBlocks=[];m.allowFreeMotion=false;
m.exclusionAreasStep.push(b=>!b.position||b.position.x<278||b.position.x>290||
  b.position.z<106||b.position.z>114||b.position.y< -60||b.position.y> -54?200:0);
bot.pathfinder.enablePathShortcut=false;bot.pathfinder.setMovements(m);
bot.on('physicsTick',sample);bot.on('path_update',path);bot.on('path_reset',reset);
try {
  for (const [name,x,y,z] of [['up',280,-57,110],['detour',288,-57,110],['landing',288,-60,110]]) {
    phase=name;await bot.pathfinder.goto(new goals.GoalBlock(x,y,z));
    arrivals.push({phase,p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround});
    if(bot.entity!==body||obstacleTouched||!body.position.floored().equals(new Vec3(x,y,z)))
      throw new Error('Parrot logical arrival differs: '+phase);
  }
  await bot.waitForTicks(5);
  if(!airborne||!ascent||!descent||!body.onGround||body.position.distanceTo(new Vec3(288.5,-60,110.5))>.4||
    obstacleTouched||bot.health!==health||JSON.stringify(bot.inventory.slots)!==inventory||bot.pathfinder.isMoving()||
    paths.some(r=>r.nodes.some(n=>n.edits||n.parkour))||Object.values(bot.controlState).some(Boolean))
    throw new Error('Parrot native flight/landing/restoration differs');
  return {start,end:Date.now(),bodyUuid:body.uuid,caps,arrivals,samples,airborne,ascent,descent,obstacleTouched,
    health,inventoryUnchanged:true,paths,resets,trace,traceLimit:64};
} catch(error) {
  console.log(JSON.stringify({phase,p:body.position,samples,airborne,ascent,descent,obstacleTouched,paths,resets,trace}));
  throw error;
} finally {
  bot.removeListener('physicsTick',sample);bot.removeListener('path_update',path);bot.removeListener('path_reset',reset);
}
