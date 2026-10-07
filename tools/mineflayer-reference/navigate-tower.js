// Fixture: body (10.5,-60,0.5), empty headroom and one cobblestone.
const movements = new Movements(bot);
movements.canDig = false;
movements.allowParkour = false;
movements.exclusionAreasStep.push(block => block.position.x !== 10 || block.position.z !== 0 ? 100 : 0);
bot.pathfinder.setMovements(movements);
const count = () => bot.inventory.items().filter(item => item.name === 'cobblestone').reduce((n,item) => n+item.count,0);
const before = count();
let airborne = 0, buildingTicks = 0;
bot.on('physicsTick', () => { if (!bot.entity.onGround) airborne++; if (bot.pathfinder.isBuilding()) buildingTicks++; });
await bot.pathfinder.goto(new goals.GoalBlock(10,-59,0));
if (bot.blockAt(new Vec3(10,-60,0)).name !== 'cobblestone' || count() !== before-1)
  throw new Error('Tower placement or consumption is incorrect');
if (!bot.entity.onGround || Math.abs(bot.entity.position.y+59)>0.01 || !airborne || !buildingTicks)
  throw new Error('Tower did not jump and land on its support');
return { position: bot.entity.position, consumed:before-count(), airborne, buildingTicks };
