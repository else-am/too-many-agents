// PREPARED ONLY: fresh empty Cod; see fish-route-fixture.md.
const expectedUuid = '4c908008-5346-4445-b844-9695af29343a';
const body = bot.entity, caps = bot.nativeBody;
if (body.uuid !== expectedUuid || body.name !== 'cod' || !body.alive ||
    !body.isInWater || body.isInLava || body.isSleeping || bot.vehicle ||
    bot.inventory.slots.some(Boolean) || bot.currentWindow || bot.inventory.selectedItem ||
    Object.values(bot.controlState).some(Boolean)) throw new Error('Empty submerged Cod fixture differs');
if (caps?.physics !== 'native-fish-submerged-post-tick' || caps.locomotion !== 'submerged' ||
    caps.canSwim !== true || caps.canJump !== false || caps.jumpHeight !== 0 ||
    caps.maxJumpDistance !== 0 || caps.maxSprintJumpDistance !== 0 ||
    !Number.isFinite(body.width) || body.width <= 0 ||
    !Number.isFinite(body.height) || body.height <= 0 ||
    !Number.isFinite(body.eyeHeight) || body.eyeHeight < 0 ||
    !Number.isFinite(caps.swimTargetYOffset) ||
    Math.abs(caps.swimTargetYOffset - Math.max(.05, (1 - body.height) / 2)) > 1e-6)
  throw new Error('Native fish capability/dimension/offset contract differs');
const offset = caps.swimTargetYOffset, origin = new Vec3(204.5, -60, 108.5);
if (!body.onGround || body.position.distanceTo(origin) > .2) throw new Error('Settled Cod start differs');
await bot.waitForChunksToLoad();
function sourceWater(p) {
  const block = bot.blockAt(p);
  return block?.name === 'water' && Number(block.getProperties().level) === 0;
}
function submerged() {
  const p = body.position, half = body.width / 2, top = p.y + Math.max(body.height, body.eyeHeight);
  for (let y = Math.floor(p.y + 1e-7); y < Math.ceil(top - 1e-7); y++)
    for (let x = Math.floor(p.x - half + 1e-7); x < Math.ceil(p.x + half - 1e-7); x++)
      for (let z = Math.floor(p.z - half + 1e-7); z < Math.ceil(p.z + half - 1e-7); z++)
        if (!sourceWater(new Vec3(x,y,z)) || !sourceWater(new Vec3(x,y+1,z))) return false;
  return true;
}
for (let y = -60; y <= -54; y++)
  for (let z = 106; z <= 110; z++)
    if (bot.blockAt(new Vec3(207,y,z))?.name !== 'stone') throw new Error('Full-depth detour column differs');
for (const p of [new Vec3(204,-60,108), new Vec3(204,-56,108),
    new Vec3(210,-56,108), new Vec3(210,-58,108), new Vec3(207,-56,105), new Vec3(207,-56,111)])
  if (!sourceWater(p) || !sourceWater(p.offset(0,1,0))) throw new Error('Submerged endpoints/detour differ');
if (!submerged()) throw new Error('Initial body/eyes not wholly submerged');
const inventory = JSON.stringify(bot.inventory.slots), health = bot.health, start = Date.now();
const trace = [], paths = [], resets = [], arrivals = [];
let phase = 'up', escaped = false, obstacleTouched = false;
const sample = () => {
  const p = body.position;
  if (!body.isInWater || !submerged()) escaped = true;
  if (p.x + body.width/2 > 207 && p.x - body.width/2 < 208 &&
      p.z + body.width/2 > 106 && p.z - body.width/2 < 111) obstacleTouched = true;
  if (trace.length < 2400) trace.push({phase, position:p.clone(), velocity:body.velocity.clone(),
    onGround:body.onGround, isInWater:body.isInWater});
};
const path = result => paths.push({phase, status:result.status, nodes:result.path.map(node =>
  ({x:node.x,y:node.y,z:node.z,parkour:node.parkour===true,toBreak:node.toBreak,toPlace:node.toPlace}))});
const reset = reason => resets.push({phase,reason});
const movements = new Movements(bot);
movements.canDig = false; movements.allowParkour = false; movements.allowSprinting = false;
movements.allow1by1towers = false; movements.scafoldingBlocks = []; movements.allowFreeMotion = false;
movements.exclusionAreasStep.push(block => !block.position ||
  block.position.x < 202 || block.position.x > 211 || block.position.z < 105 || block.position.z > 112 ||
  block.position.y < -60 || block.position.y > -55 ? 200 : 0);
bot.pathfinder.enablePathShortcut = false; bot.pathfinder.setMovements(movements);
bot.on('physicsTick',sample); bot.on('path_update',path); bot.on('path_reset',reset);
async function arrive(x,y,z) {
  await bot.pathfinder.goto(new goals.GoalBlock(x,y,z));
  const target = new Vec3(x+.5,y+offset,z+.5);
  if (bot.entity !== body || !body.position.floored().equals(new Vec3(x,y,z)) ||
      body.position.distanceTo(target) > .25 || !submerged() || escaped || obstacleTouched)
    throw new Error('Native submerged arrival differs: '+phase);
  arrivals.push({phase,position:body.position.clone(),target});
}
try {
  await arrive(204,-56,108);
  phase = 'detour'; await arrive(210,-56,108);
  phase = 'down'; await arrive(210,-58,108);
  await bot.waitForTicks(5);
  const detourNodes = paths.filter(row=>row.phase==='detour'&&row.status==='success').flatMap(row=>row.nodes);
  if (!detourNodes.some(node=>Math.floor(node.x)===207 &&
      (Math.floor(node.z)<=105 || Math.floor(node.z)>=111)) ||
      !trace.some(row=>row.phase==='up'&&row.position.y>origin.y+.5) ||
      !trace.some(row=>row.phase==='down'&&row.velocity.y<-.01) ||
      paths.some(row=>row.nodes.some(node=>node.parkour||node.toBreak.length||node.toPlace.length)) ||
      escaped || obstacleTouched || !submerged() || bot.health!==health ||
      JSON.stringify(bot.inventory.slots)!==inventory || bot.vehicle || bot.currentWindow ||
      bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean) || bot.pathfinder.isMoving())
    throw new Error('Fish selected up/detour/down/release differs');
  return {start,end:Date.now(),bodyUuid:body.uuid,nativeCapabilities:caps,
    dimensions:{width:body.width,height:body.height,eyeHeight:body.eyeHeight},
    arrivals,final:body.position.clone(),health,inventoryUnchanged:true,escaped,obstacleTouched,paths,resets,trace};
} catch (error) {
  console.log(JSON.stringify({phase,position:body.position,nativeCapabilities:caps,escaped,obstacleTouched,paths,resets,trace}));
  throw error; // No retry or cleanup mutation after an unknown outcome.
} finally {
  bot.removeListener('physicsTick',sample); bot.removeListener('path_update',path); bot.removeListener('path_reset',reset);
}
