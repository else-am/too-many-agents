// Fixture: start (10.5,-60,0.5), missing floor x=11, existing stone x=12.
// At least one cobblestone in inventory; no parkour or alternate corridor.
const movements = new Movements(bot);
movements.canDig = false;
movements.allowParkour = false;
movements.allow1by1towers = false;
movements.maxDropDown = 0;
movements.exclusionAreasStep.push(block => block.position.z !== 0 ? 100 : 0);
bot.pathfinder.setMovements(movements);
const count = () => bot.inventory.items().filter(item => item.name === 'cobblestone').reduce((n,item) => n+item.count,0);
const before = count();
let buildingTicks = 0, minY = bot.entity.position.y;
const placements = [];
bot.on('path_update', result => { if (result.status === 'success') for (const node of result.path) placements.push(...node.toPlace); });
bot.on('physicsTick', () => { if (bot.pathfinder.isBuilding()) buildingTicks++; minY = Math.min(minY,bot.entity.position.y); });
await bot.pathfinder.goto(new goals.GoalBlock(12,-60,0));
if (bot.blockAt(new Vec3(11,-61,0)).name !== 'cobblestone' || count() !== before-1)
  throw new Error('Bridge support or inventory consumption is incorrect');
if (!bot.entity.onGround || minY < -60.1 || buildingTicks === 0) throw new Error('Bridge did not preserve supported movement');
return { position: bot.entity.position, placements, buildingTicks, consumed:before-count(), minY };
