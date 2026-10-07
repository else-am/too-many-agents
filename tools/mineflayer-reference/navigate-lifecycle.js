const start = bot.entity.position.floored();
await bot.pathfinder.goto(new goals.GoalBlock(start.x, start.y, start.z));
const blocked = new Movements(bot);
blocked.canDig = false;
blocked.exclusionAreasStep.push(() => 100);
bot.pathfinder.setMovements(blocked);
let noPath;
try { await bot.pathfinder.goto(new goals.GoalXZ(start.x + 2, start.z)); }
catch (error) { noPath = error.name; }
if (noPath !== 'NoPath') throw new Error(`Empty unreachable path incorrectly resolved: ${noPath}`);
bot.pathfinder.setMovements(new Movements(bot));
const first = new goals.GoalXZ(start.x + 8, start.z);
const pending = bot.pathfinder.goto(first).then(() => 'resolved', error => error.name);
await bot.waitForTicks(2);
bot.pathfinder.setGoal(null);
const changed = await pending;
if (changed !== 'GoalChanged') throw new Error(`Goal replacement produced ${changed}`);
await bot.waitForTicks(3);
const before = bot.entity.position.clone();
await bot.waitForTicks(3);
if (before.distanceTo(bot.entity.position) > 0.05) throw new Error('Cancelled path kept moving');
return { noPath, changed, position: bot.entity.position };
