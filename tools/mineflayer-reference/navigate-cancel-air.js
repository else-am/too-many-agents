// Fixture: one-cell gap at x=11 down to bedrock y=-64; start x=10.5.
const movements = new Movements(bot);
movements.canDig = false;
movements.maxDropDown = 0;
movements.exclusionAreasStep.push(block => block.position.z !== 0 ? 100 : 0);
bot.pathfinder.setMovements(movements);
let cancelledAt;
bot.on('physicsTick', () => {
  const p = bot.entity.position;
  if (!cancelledAt && !bot.entity.onGround && p.x > 11.3 && p.x < 11.7) {
    cancelledAt = p.clone();
    bot.pathfinder.setGoal(null);
  }
});
let outcome;
try { await bot.pathfinder.goto(new goals.GoalBlock(12,-60,0)); outcome='resolved'; }
catch(error) { outcome=error.name; }
if (!cancelledAt || outcome !== 'GoalChanged') throw new Error(`Midair cancellation was not exercised: ${outcome}`);
await bot.waitForTicks(40);
const landed = bot.entity.position.clone();
if (!bot.entity.onGround || Math.abs(landed.y+63)>0.01 || Math.abs(landed.x-cancelledAt.x)>0.3)
  throw new Error(`Cancelled body did not fall naturally: ${JSON.stringify({cancelledAt,landed})}`);
await bot.waitForTicks(5);
if (bot.entity.position.distanceTo(landed)>0.01) throw new Error('Cancelled route resumed');
return { outcome, cancelledAt, landed, onGround:bot.entity.onGround };
