// Fixture: ladder at (10,-60..-57,0), attached to the west face of x=11 wall.
const movements = new Movements(bot);
movements.canDig = false;
movements.allowParkour = false;
bot.pathfinder.setMovements(movements);
const stops=[];
for (const [x,y] of [[10,-57],[11,-56],[10,-57],[10,-60]]) {
  await bot.pathfinder.goto(new goals.GoalBlock(x,y,0));
  stops.push(bot.entity.position.clone());
}
await bot.waitForTicks(5);
if (!bot.entity.onGround || Math.abs(bot.entity.position.y+60)>0.01) throw new Error('Ladder descent did not reach its floor');
return { stops, position:bot.entity.position, onGround:bot.entity.onGround };
