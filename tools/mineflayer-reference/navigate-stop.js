// Fixture: body (10.5,-60,0.5), one-cell gap at x=11, floor x=12 onward.
const movements = new Movements(bot);
movements.canDig = false;
movements.maxDropDown = 0;
movements.exclusionAreasStep.push(block => block.position.z !== 0 ? 100 : 0);
bot.pathfinder.setMovements(movements);
let requested = false;
bot.on('physicsTick', () => {
  if (!requested && !bot.entity.onGround) { requested = true; bot.pathfinder.stop(); }
});
let outcome;
try { await bot.pathfinder.goto(new goals.GoalBlock(15,-60,0)); outcome='resolved'; }
catch(error) { outcome=error.name; }
if (!requested || outcome !== 'PathStopped') throw new Error(`Incorrect stop outcome: ${outcome}`);
const stopped = bot.entity.position.clone();
if (!bot.entity.onGround || stopped.x < 12 || stopped.x > 13 || Math.abs(stopped.y+60)>0.01)
  throw new Error(`Stop did not finish the current jump: ${JSON.stringify(stopped)}`);
await bot.waitForTicks(10);
if (bot.entity.position.distanceTo(stopped)>0.01 || bot.pathfinder.isMoving()) throw new Error('Stopped route kept moving');
return { outcome, position: stopped, onGround: bot.entity.onGround };
