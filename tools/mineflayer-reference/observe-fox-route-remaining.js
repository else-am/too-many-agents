// PREPARED ONLY: unreached step/dig only; see fox-drowned-fixture.md.
// Substitute only a NEW disposable Fox UUID; no previous detour replay.
const expectedUuid = 'REPLACE_WITH_NEW_FOX_UUID';
const body = bot.entity, origin = new Vec3(153.5, -60, 80.5);
if (body.uuid !== expectedUuid || body.name !== 'fox' || !body.alive || !body.onGround ||
    body.isSleeping || bot.vehicle || body.position.distanceTo(origin) > 0.2 ||
    bot.currentWindow || bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean))
  throw new Error('Disposable awake Fox fixture differs');
const tool = bot.inventory.items().find(item => item.name === 'iron_pickaxe');
if (!(tool instanceof Item) || tool.count !== 1 || tool.durabilityUsed !== 0 ||
    bot.inventory.items().some(item => item !== tool))
  throw new Error('Fox requires only one real undamaged iron pickaxe');
await bot.equip(tool, 'hand');
await bot.waitForChunksToLoad();
const obstacle = new Vec3(157, -60, 80), excluded = new Vec3(151, -60, 80);
if (bot.blockAt(obstacle)?.name !== 'stone' || bot.blockAt(new Vec3(154, -60, 80))?.name !== 'stone_slab')
  throw new Error('Fox step/dig fixture differs');
const health = bot.health, start = Date.now(), trace = [], paths = [], resets = [];
let phase = 'step', excludedEntered = false;
const sample = () => {
  const p = body.position;
  // Upstream exclusion is a logical occupied-cell policy, not an AABB fence.
  if (p.floored().equals(excluded)) excludedEntered = true;
  if (trace.length < 1200) trace.push({phase, position: p.clone(), velocity: body.velocity.clone(), onGround: body.onGround});
};
const path = result => paths.push({phase, status: result.status, nodes: result.path.map(node =>
  ({x:node.x,y:node.y,z:node.z,toBreak:node.toBreak,toPlace:node.toPlace}))});
const reset = reason => resets.push({phase, reason});
bot.on('physicsTick', sample); bot.on('path_update', path); bot.on('path_reset', reset);
const movements = new Movements(bot);
movements.canDig = false; movements.allowSprinting = false;
movements.allowParkour = false; movements.allow1by1towers = false;
movements.scafoldingBlocks = []; movements.allowFreeMotion = false;
movements.exclusionAreasStep.push(block => !block.position ? 200 : block.position.equals(excluded) ||
  (block.position.x >= 156 && (block.position.z !== 80 || block.position.y !== -60)) ? 200 : 0);
movements.exclusionAreasBreak.push(block => !block.position ? 200 : block.position.equals(obstacle) ? 0 : 200);
bot.pathfinder.enablePathShortcut = false;
bot.pathfinder.setMovements(movements);
async function arrive(x,y,z,feetY=y) {
  await bot.pathfinder.goto(new goals.GoalBlock(x,y,z));
  await bot.waitForTicks(3);
  if (bot.entity !== body || !body.onGround || body.position.distanceTo(new Vec3(x+.5,feetY,z+.5)) > .4 || excludedEntered)
    throw new Error('Fox selected route arrival/exclusion differs: '+phase);
}
try {
  await arrive(154,-59,80,-59.5);
  const step = body.position.clone();
  phase = 'dig-approach'; await arrive(156,-60,80);
  phase = 'dig'; movements.canDig = true; bot.pathfinder.setMovements(movements);
  await arrive(158,-60,80);
  await bot.waitForTicks(20);
  const currentTool = bot.inventory.items().find(item => item.name === 'iron_pickaxe');
  const plannedBreaks = paths.filter(row => row.phase === 'dig' && row.status === 'success')
    .flatMap(row => row.nodes.flatMap(node => node.toBreak));
  if (!plannedBreaks.some(p => p.x===157 && p.y===-60 && p.z===80) ||
      plannedBreaks.some(p => p.x!==157 || p.y!==-60 || p.z!==80) ||
      bot.blockAt(obstacle)?.name !== 'air' || !currentTool || currentTool.count!==1 || currentTool.durabilityUsed!==1 ||
      bot.inventory.items().some(item => item.name!=='iron_pickaxe' && item.name!=='cobblestone') ||
      bot.inventory.items().filter(item=>item.name==='cobblestone').reduce((n,item)=>n+item.count,0)>1 ||
      bot.health!==health || bot.vehicle || bot.currentWindow || bot.inventory.selectedItem ||
      Object.values(bot.controlState).some(Boolean) || bot.pathfinder.isMoving())
    throw new Error('Fox selected break/tool/inventory/release differs');
  const drops = Object.values(bot.entities).map(entity=>({uuid:entity.uuid,item:entity.getDroppedItem()}))
    .filter(row=>row.item?.name==='cobblestone');
  return {start,end:Date.now(),bodyUuid:body.uuid,step,final:body.position.clone(),excludedEntered,
    toolDamage:currentTool.durabilityUsed,inventory:bot.inventory.slots,drops,paths,resets,trace};
} catch (error) {
  console.log(JSON.stringify({phase,position:body.position,excludedEntered,paths,resets,trace}));
  throw error; // No retry, cancellation or mutation after an unknown outcome.
} finally {
  bot.removeListener('physicsTick',sample); bot.removeListener('path_update',path); bot.removeListener('path_reset',reset);
}
