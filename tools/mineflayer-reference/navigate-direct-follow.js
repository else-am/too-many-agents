// Continue only the previously unreached pursuit phase after native shortcuts.
const inventory = JSON.stringify(bot.inventory.slots);
const movements = new Movements(bot);
movements.canDig = false;
movements.allowParkour = false;
movements.allow1by1towers = false;
movements.scafoldingBlocks = [];
movements.allowFreeMotion = true;
bot.pathfinder.setMovements(movements);
bot.pathfinder.enablePathShortcut = true;
const lengths = [], resets = [];
bot.on('path_update', result => { if (result.status === 'success') lengths.push(result.path.length); });
bot.on('path_reset', reason => resets.push(reason));
const target = Object.values(bot.entities).find(entity => entity.getCustomName()?.toString() === 'Direct target');
if (!target) throw new Error('Direct follow target missing');
movements.allowFreeMotion = true;
bot.pathfinder.setMovements(movements);
const followGoal = new goals.GoalFollow(target, 1), beforeFollow = resets.length;
await bot.pathfinder.goto(followGoal);
const pursuitFallback = resets.slice(beforeFollow).includes('direct_rejected');
if (pursuitFallback ? !followGoal.isEnd(bot.entity.position.floored())
  : bot.entity.position.distanceTo(target.position) > 1.05)
  throw new Error('Pursuit did not satisfy its selected completion mode');
const followed = bot.entity.position.clone();
bot.pathfinder.setGoal(new goals.GoalFollow(target, 1), true);
await bot.waitForTicks(10);
if (bot.entity.position.distanceTo(followed) > 0.2 || bot.pathfinder.isMoving())
  throw new Error('Stationary dynamic goal did not hold');
bot.pathfinder.setGoal(null);
await bot.waitForTicks(1);
if (JSON.stringify(bot.inventory.slots) !== inventory) throw new Error('Direct navigation changed inventory');
return { followed, target: target.position.clone(), pathLengths: lengths, resets, pursuitFallback, inventoryUnchanged: true };
