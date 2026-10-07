// Fixture: water x=10..14,y=-62..-61,z=0, stone floor y=-63 and walls;
// dry banks x=9 and15 have feet y=-60. Start (9.5,-60,0.5).
const movements = new Movements(bot);
movements.canDig = false;
movements.allowParkour = false;
movements.allow1by1towers = false;
movements.scafoldingBlocks = [];
movements.exclusionAreasStep.push(block => block.position.z !== 0 ? 100 : 0);
bot.pathfinder.setMovements(movements);
const stops = [];
for (const [x,y] of [[10,-61],[12,-61],[15,-60]]) {
  await bot.pathfinder.goto(new goals.GoalBlock(x,y,0));
  stops.push(bot.entity.position.clone());
}
await bot.waitForTicks(5);
if (!bot.entity.onGround || Math.abs(bot.entity.position.y+60)>0.01) throw new Error('Water exit did not land on the bank');
return { stops, position:bot.entity.position, onGround:bot.entity.onGround };
