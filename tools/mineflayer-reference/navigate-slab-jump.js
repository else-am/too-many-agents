// Fixture: bottom slab (10,-60,0); stone (11,-60,0) + bottom slab (11,-59,0).
// Start (10.5,-59.5,0.5): one physical block of rise, two from floored Y.
const movements = new Movements(bot);
movements.canDig = false;
movements.allowParkour = false;
bot.pathfinder.setMovements(movements);
const before = bot.entity.position.clone();
await bot.pathfinder.goto(new goals.GoalBlock(11,-58,0));
const after = bot.entity.position.clone();
if (!bot.entity.onGround || Math.abs(after.y+58.5)>0.01 || Math.abs(after.x-11.5)>0.15)
  throw new Error(`Invalid elevated slab landing: ${JSON.stringify(after)}`);
return { before, after, rise:after.y-before.y };
