// PREPARED ONLY: fresh empty size-one Slime; see slime-route-fixture.md.
const expectedUuid = '404ed097-1522-4ec3-be02-df21e83c6123';
const body = bot.entity, origin = new Vec3(238.5,-60,110.5), caps = bot.nativeBody;
if (body.uuid!==expectedUuid || body.name!=='slime' || !body.alive || !body.onGround ||
    body.isSleeping || body.isInWater || body.isInLava || bot.vehicle ||
    body.position.distanceTo(origin)>.2 || bot.inventory.slots.some(Boolean) ||
    bot.currentWindow || bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean))
  throw new Error('Empty dry size-one Slime fixture differs');
if (caps?.physics!=='native-slime-hop-post-tick' || caps.locomotion!=='hopping' ||
    caps.canJump!==true || caps.canSwim!==false || !Number.isFinite(caps.jumpHeight) || caps.jumpHeight<=0 ||
    !Number.isFinite(caps.maxJumpDistance) || caps.maxJumpDistance<=0 ||
    !Number.isFinite(body.width) || Math.abs(body.width-.52)>.01 ||
    !Number.isFinite(body.height) || Math.abs(body.height-.52)>.01 ||
    !Number.isFinite(body.eyeHeight) || body.eyeHeight<=0)
  throw new Error('Native Slime capability/size geometry differs');
await bot.waitForChunksToLoad();
for (let x=238;x<=242;x++)
  if (bot.blockAt(new Vec3(x,-61,110))?.name!=='stone') throw new Error('Slime dry support differs');
const slab=bot.blockAt(new Vec3(241,-60,110));
if (slab?.name!=='stone_slab' || slab.getProperties().type!=='bottom' || slab.getProperties().waterlogged!==false)
  throw new Error('Slime slab fixture differs');
for (let x=238;x<=242;x++)
  for (let y=-59;y<=-55;y++)
    if (bot.blockAt(new Vec3(x,y,110))?.name!=='air') throw new Error('Slime hop headroom differs');
const inventory=JSON.stringify(bot.inventory.slots),health=bot.health,start=Date.now();
const trace=[],paths=[],resets=[],arrivals=[];
let phase='hop',excludedEntered=false;
const sample=()=>{
  const p=body.position;
  if (Math.floor(p.x)===239 && Math.floor(p.z)===111) excludedEntered=true;
  if (trace.length<2400) trace.push({phase,position:p.clone(),velocity:body.velocity.clone(),onGround:body.onGround});
};
const path=result=>paths.push({phase,status:result.status,nodes:result.path.map(node=>
  ({x:node.x,y:node.y,z:node.z,parkour:node.parkour===true,toBreak:node.toBreak,toPlace:node.toPlace}))});
const reset=reason=>resets.push({phase,reason});
const movements=new Movements(bot);
movements.canDig=false;movements.allowParkour=false;movements.allowSprinting=false;
movements.allow1by1towers=false;movements.scafoldingBlocks=[];movements.allowFreeMotion=false;
// The slab logical node is-59; dry support after the half-block drop is-61.
movements.maxDropDown=2;
movements.exclusionAreasStep.push(block=>!block.position || block.position.x<236 || block.position.x>245 ||
  block.position.z<107 || block.position.z>113 || (block.position.x===239&&block.position.z===111) ? 200 : 0);
bot.pathfinder.enablePathShortcut=false;bot.pathfinder.setMovements(movements);
bot.on('physicsTick',sample);bot.on('path_update',path);bot.on('path_reset',reset);
async function arrive(x,y,z,feetY=y){
  await bot.pathfinder.goto(new goals.GoalBlock(x,y,z));await bot.waitForTicks(3);
  if (bot.entity!==body || !body.onGround || body.position.distanceTo(new Vec3(x+.5,feetY,z+.5))>.4 ||
      excludedEntered || body.isInWater || body.isInLava)
    throw new Error('Native Slime supported arrival differs: '+phase);
  arrivals.push({phase,position:body.position.clone()});
}
try{
  await arrive(240,-60,110);
  const airborne=trace.some(row=>row.phase==='hop'&&!row.onGround&&row.position.y>origin.y+.15);
  const rising=trace.some(row=>row.phase==='hop'&&!row.onGround&&row.velocity.y>.01);
  if (!airborne||!rising||body.position.x-origin.x<.5) throw new Error('Real Slime horizontal hop/landing missing');
  phase='step';await arrive(241,-59,110,-59.5);
  phase='drop';await arrive(242,-60,110);
  const downward=trace.some(row=>row.phase==='drop'&&!row.onGround&&row.velocity.y<-.01);
  if (!downward || paths.some(row=>row.nodes.some(node=>node.parkour||node.toBreak.length||node.toPlace.length)) ||
      bot.health!==health || JSON.stringify(bot.inventory.slots)!==inventory || bot.vehicle ||
      bot.currentWindow || bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean) ||
      bot.pathfinder.isMoving()) throw new Error('Slime supported drop/items/release differs');
  await bot.waitForTicks(5);
  if (!body.onGround || body.position.distanceTo(new Vec3(242.5,-60,110.5))>.4)
    throw new Error('Final Slime landing did not settle');
  return {start,end:Date.now(),bodyUuid:body.uuid,nativeCapabilities:caps,
    dimensions:{width:body.width,height:body.height,eyeHeight:body.eyeHeight},
    airborne,rising,downward,excludedEntered,arrivals,final:body.position.clone(),health,
    inventoryUnchanged:true,paths,resets,trace};
}catch(error){
  console.log(JSON.stringify({phase,position:body.position,nativeCapabilities:caps,excludedEntered,paths,resets,trace}));
  throw error; // No retry or cleanup action after an unknown outcome.
}finally{
  bot.removeListener('physicsTick',sample);bot.removeListener('path_update',path);bot.removeListener('path_reset',reset);
}
