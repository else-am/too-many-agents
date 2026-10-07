// Fixture: start (10.5,-60,0.5), one missing floor column at x=11,
// stone landing x=12. The allowed route stays in z=0 and cannot drop.
const movements = new Movements(bot);
movements.canDig = false;
movements.maxDropDown = 0;
movements.allowSprinting = true;
movements.exclusionAreasStep.push(block => block.position.z !== 0 ? 100 : 0);
bot.pathfinder.setMovements(movements);
let parkourEdges = 0, airborne = 0, minY = bot.entity.position.y;
bot.on('path_update', result => { parkourEdges += result.path.filter(node => node.parkour).length; });
bot.on('physicsTick', () => { if (!bot.entity.onGround) airborne++; minY = Math.min(minY, bot.entity.position.y); });
await bot.pathfinder.goto(new goals.GoalBlock(12,-60,0));
if (!bot.entity.onGround || Math.abs(bot.entity.position.y + 60) > 0.01 || minY < -60.1)
  throw new Error('The body did not cross the gap with a supported landing');
if (!parkourEdges || !airborne) throw new Error('The fixture did not exercise parkour');
return { position: bot.entity.position, parkourEdges, airborne, minY };
