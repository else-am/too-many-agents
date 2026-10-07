// Fixture: start (10.5,-60,0.5); bottom slab at (11,-60,0),
// full block at (12,-60,0), two-block column at x=13; stone floor y=-61.
const movements = new Movements(bot);
movements.canDig = false;
movements.allowParkour = false;
bot.pathfinder.setMovements(movements);
const trace = [];
bot.on('physicsTick', () => trace.push({ position: bot.entity.position.clone(), onGround: bot.entity.onGround }));
const stops = [];
for (const [x, goalY, feetY] of [[11,-59,-59.5], [12,-59,-59], [13,-58,-58], [15,-60,-60]]) {
  await bot.pathfinder.goto(new goals.GoalBlock(x, goalY, 0));
  const p = bot.entity.position.clone();
  if (Math.abs(p.x-(x+0.5)) > 0.15 || Math.abs(p.y-feetY) > 0.02 || !bot.entity.onGround)
    throw new Error(`Incorrect supported arrival: ${JSON.stringify(p)}`);
  stops.push(p);
}
let maxStep = 0;
for (let i=1;i<trace.length;i++) maxStep = Math.max(maxStep, trace[i].position.distanceTo(trace[i-1].position));
if (maxStep > 0.75) throw new Error(`Unexpected position discontinuity: ${maxStep}`);
return { stops, maxStep, airborneTicks: trace.filter(frame => !frame.onGround).length, ticks: trace.length, nativeBody: bot.nativeBody };
