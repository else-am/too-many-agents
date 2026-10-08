// Guarded fresh fixture: stone floor at y=-61 over x62..81,z30..45;
// clear above it, body (64.5,-60,32.5), no nearby other entities except
// an immobile named cow "Direct target" at (76.5,-60,36.5).
const origin = new Vec3(64.5, -60, 32.5);
if (bot.entity.position.distanceTo(origin) > 0.2 || !bot.entity.onGround)
  throw new Error('Direct navigation fixture differs');
const inventory = JSON.stringify(bot.inventory.slots);
const movements = new Movements(bot);
movements.canDig = false;
movements.allowParkour = false;
movements.allow1by1towers = false;
movements.scafoldingBlocks = [];
movements.allowFreeMotion = false;
bot.pathfinder.setMovements(movements);
bot.pathfinder.enablePathShortcut = true;
const lengths = [], resets = [];
bot.on('path_update', result => { if (result.status === 'success') lengths.push(result.path.length); });
bot.on('path_reset', reason => resets.push(reason));
await bot.pathfinder.goto(new goals.GoalBlock(70, -60, 32));
if (bot.entity.position.distanceTo(new Vec3(70.5, -60, 32.5)) > 0.3 || !lengths.some(length => length === 1))
  throw new Error('Straight selected shortcut did not arrive or shorten');
const straight = bot.entity.position.clone();
await bot.pathfinder.goto(new goals.GoalBlock(73, -60, 35));
const diagonal = bot.entity.position.clone();
if (diagonal.distanceTo(new Vec3(73.5, -60, 35.5)) > 0.3)
  throw new Error('Diagonal shortcut did not arrive');
const target = Object.values(bot.entities).find(entity => entity.customName?.toString() === 'Direct target');
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
return { straight, diagonal, followed, target: target.position.clone(), pathLengths: lengths, resets, pursuitFallback, inventoryUnchanged: true };
