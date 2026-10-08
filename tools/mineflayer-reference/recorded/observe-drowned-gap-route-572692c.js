// PREPARED ONLY: one NEW disposable Drowned; see fox-drowned-fixture.md.
const expectedUuid = '19c2703e-00fb-4054-a772-b63d53cac1b6';
const body = bot.entity, origin = new Vec3(170.5,-58,90.5);
if (body.uuid!==expectedUuid || body.name!=='drowned' || !body.alive || !body.onGround ||
    body.isSleeping || body.isInWater || body.isInLava || bot.vehicle ||
    body.position.distanceTo(origin)>.2 || bot.inventory.slots.some(Boolean) ||
    bot.currentWindow || bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean))
  throw new Error('Disposable dry Drowned gap fixture differs');
await bot.waitForChunksToLoad();
const caps = bot.nativeBody;
if (!caps || !caps.canJump || caps.maxJumpDistance < 2)
  throw new Error('Actual native body cannot select the prepared two-node gap edge');
for(const p of [new Vec3(173,-59,90),new Vec3(175,-59,90),new Vec3(177,-60,90)])
  if(bot.blockAt(p)?.name!=='stone')throw new Error('Gap support/landing/drop differs');
for(const p of [new Vec3(174,-59,90),new Vec3(174,-60,90)])
  if(bot.blockAt(p)?.name!=='air')throw new Error('Gap is bridged or too shallow');
const inventory=JSON.stringify(bot.inventory.slots),health=bot.health,start=Date.now();
const trace=[],paths=[],resets=[]; let phase='approach';
const sample=()=>{if(trace.length<1200)trace.push({phase,position:body.position.clone(),velocity:body.velocity.clone(),onGround:body.onGround});};
const path=result=>paths.push({phase,status:result.status,nodes:result.path.map(node=>
  ({x:node.x,y:node.y,z:node.z,parkour:node.parkour===true,toBreak:node.toBreak,toPlace:node.toPlace}))});
const reset=reason=>resets.push({phase,reason});
bot.on('physicsTick',sample);bot.on('path_update',path);bot.on('path_reset',reset);
const movements=new Movements(bot);
movements.canDig=false;movements.allowSprinting=false;movements.allowParkour=true;
movements.maxDropDown=0;movements.allow1by1towers=false;
movements.scafoldingBlocks=[];movements.allowFreeMotion=false;
movements.exclusionAreasStep.push(block=>block.position.z!==90 || block.position.x<168 || block.position.x>179 ? 200 : 0);
bot.pathfinder.enablePathShortcut=false;bot.pathfinder.setMovements(movements);
async function arrive(x,y,z){
  await bot.pathfinder.goto(new goals.GoalBlock(x,y,z));await bot.waitForTicks(3);
  if(bot.entity!==body || !body.onGround || body.position.distanceTo(new Vec3(x+.5,y,z+.5))>.4)
    throw new Error('Drowned supported arrival differs: '+phase);
}
try{
  await arrive(173,-58,90);const takeoff=body.position.clone();
  phase='gap';await arrive(175,-58,90);const landing=body.position.clone();
  const airborne=trace.some(row=>row.phase==='gap'&&!row.onGround&&row.position.x>=174&&row.position.x<175);
  const ascent=trace.some(row=>row.phase==='gap'&&!row.onGround&&row.position.y>takeoff.y+.15);
  const selectedJump=paths.some(row=>row.phase==='gap'&&row.status==='success'&&row.nodes.some(node=>node.parkour));
  if(!airborne||!ascent||!selectedJump)throw new Error('Selected dry gap did not expose actual airborne ascent/crossing');
  phase='platform';await arrive(176,-58,90);
  // The proven gap used maxDropDown0. Only this separately observed safe
  // one-block exit now permits a drop. getLandingBlock compares current
  // feet Y with the support-block Y (-58 to -60), hence this limit is2.
  // No body attribute is changed.
  movements.maxDropDown=2;bot.pathfinder.setMovements(movements);
  phase='drop';await arrive(177,-59,90);
  if(!trace.some(row=>row.phase==='drop'&&!row.onGround&&row.velocity.y<-.01)||
      paths.some(row=>row.nodes.some(node=>node.toBreak.length||node.toPlace.length))||
      JSON.stringify(bot.inventory.slots)!==inventory||bot.health!==health||bot.vehicle||
      bot.currentWindow||bot.inventory.selectedItem||Object.values(bot.controlState).some(Boolean)||bot.pathfinder.isMoving())
    throw new Error('Drowned drop/unchanged items/control release differs');
  const settled=body.position.clone();await bot.waitForTicks(5);
  if(!body.onGround||body.position.distanceTo(settled)>.3)throw new Error('Drowned final support did not settle');
  return {start,end:Date.now(),bodyUuid:body.uuid,nativeCapabilities:caps,takeoff,landing,airborne,ascent,selectedJump,
    final:body.position.clone(),inventoryUnchanged:true,paths,resets,trace};
}catch(error){
  console.log(JSON.stringify({phase,position:body.position,nativeCapabilities:caps,paths,resets,trace}));
  throw error; // No recovery, fixture mutation or replay after first failure.
}finally{
  bot.removeListener('physicsTick',sample);bot.removeListener('path_update',path);bot.removeListener('path_reset',reset);
}
