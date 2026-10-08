// PREPARED ONLY. See fox-drowned-fixture.md; substitute only the NEW Drowned UUID.
const expectedUuid = 'REPLACE_WITH_NEW_DROWNED_UUID';
const body = bot.entity, origin = new Vec3(170.5,-60,90.5);
if (body.uuid!==expectedUuid || body.name!=='drowned' || !body.alive || !body.onGround || body.isSleeping ||
    bot.vehicle || body.position.distanceTo(origin)>.2 || bot.inventory.slots.some(Boolean) ||
    bot.currentWindow || bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean))
  throw new Error('Disposable dry empty Drowned fixture differs');
await bot.waitForChunksToLoad();
for (const position of [new Vec3(174,-60,90),new Vec3(175,-60,90)])
  if (bot.blockAt(position)?.name!=='stone') throw new Error('Drowned jump platform differs');
const inventory = JSON.stringify(bot.inventory.slots), health = bot.health;
const start=Date.now(), trace=[], paths=[], resets=[];
let phase='approach';
const sample=()=>{if(trace.length<1200)trace.push({phase,position:body.position.clone(),velocity:body.velocity.clone(),onGround:body.onGround});};
const path=result=>paths.push({phase,status:result.status,nodes:result.path.map(node=>
  ({x:node.x,y:node.y,z:node.z,toBreak:node.toBreak,toPlace:node.toPlace}))});
const reset=reason=>resets.push({phase,reason});
bot.on('physicsTick',sample); bot.on('path_update',path); bot.on('path_reset',reset);
const movements=new Movements(bot);
movements.canDig=false; movements.allowSprinting=false; movements.allowParkour=false;
movements.allow1by1towers=false; movements.scafoldingBlocks=[]; movements.allowFreeMotion=false;
bot.pathfinder.enablePathShortcut=false; bot.pathfinder.setMovements(movements);
async function arrive(x,y,z) {
  await bot.pathfinder.goto(new goals.GoalBlock(x,y,z));
  await bot.waitForTicks(3);
  if(bot.entity!==body || !body.onGround || body.position.distanceTo(new Vec3(x+.5,y,z+.5))>.4)
    throw new Error('Drowned selected arrival differs: '+phase);
}
try {
  await arrive(173,-60,90); const approach=body.position.clone();
  phase='jump'; await arrive(174,-59,90); const landing=body.position.clone();
  if(!trace.some(row=>row.phase==='jump' && !row.onGround && row.position.y>-59.65))
    throw new Error('Drowned full-block ascent lacked observed airborne motion');
  phase='platform'; await arrive(175,-59,90);
  phase='drop'; await arrive(176,-60,90);
  if(!trace.some(row=>row.phase==='drop' && !row.onGround && row.velocity.y<-.01) ||
      paths.some(row=>row.nodes.some(node=>node.toBreak.length || node.toPlace.length)) ||
      JSON.stringify(bot.inventory.slots)!==inventory || bot.health!==health || bot.vehicle ||
      bot.currentWindow || bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean) || bot.pathfinder.isMoving())
    throw new Error('Drowned drop/edit/inventory/release differs');
  const settled=body.position.clone(); await bot.waitForTicks(5);
  if(!body.onGround || body.position.distanceTo(settled)>.3) throw new Error('Drowned landing did not settle');
  return {start,end:Date.now(),bodyUuid:body.uuid,approach,landing,final:body.position.clone(),inventoryUnchanged:true,paths,resets,trace};
} catch(error) {
  console.log(JSON.stringify({phase,position:body.position,paths,resets,trace}));
  throw error; // Retain partial native progress; no speculative recovery.
} finally {
  bot.removeListener('physicsTick',sample); bot.removeListener('path_update',path); bot.removeListener('path_reset',reset);
}
