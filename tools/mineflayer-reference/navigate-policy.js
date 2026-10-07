// Fixture: stone floor, body at (-7.5,-60,0.5), wall at x=0,
// y=-60..-58, z=-2..2. No commands inside the agent's procedure.
const movements = new Movements(bot);
movements.canDig = false;
movements.allowParkour = false;
movements.exclusionAreasStep.push(block => block.position.z < 0 ? 100 : 0);
bot.pathfinder.setMovements(movements);
const goal = new goals.GoalBlock(5, -60, 0);
const search = bot.pathfinder.getPathFromTo(movements, bot.entity.position, goal);
let planned;
for (const step of search) {
  planned = step.result;
  if (planned.status === 'partial') await bot.waitForTicks(1);
}
if (planned.status !== 'success') throw new Error(`Planning failed: ${planned.status}`);
if (planned.path.some(node => node.toBreak.length || node.toPlace.length || node.z < 0))
  throw new Error('Planned route violated movement policy');
const updates = [];
bot.on('path_update', result => updates.push(result.status));
const value = await bot.pathfinder.goto(goal);
if (value !== undefined || !goal.isEnd(bot.entity.position.floored())) throw new Error('Incorrect goto completion');
if (bot.pathfinder.isMoving()) throw new Error('Pathfinder still moving after arrival');
return { position: bot.entity.position, updates, pathNodes: planned.path.length };
