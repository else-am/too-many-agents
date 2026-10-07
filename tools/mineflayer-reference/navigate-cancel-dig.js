// Fixture: body x=10.5; stone columns x=11,12 at y=-60,-59; floor y=-61.
const movements = new Movements(bot);
movements.allowParkour = false;
movements.maxDropDown = 0;
movements.exclusionAreasStep.push(block => block.position.z !== 0 ? 100 : 0);
bot.pathfinder.setMovements(movements);
const damageBefore = bot.inventory.items().find(item => item.name === 'diamond_pickaxe').durabilityUsed;
let requested = false;
bot.on('blockUpdate', (before,after) => {
  if (!requested && before?.name === 'stone' && after?.name === 'air' && after.position.x === 11) {
    requested = true;
    bot.pathfinder.setGoal(null);
  }
});
let outcome;
try { await bot.pathfinder.goto(new goals.GoalBlock(13,-60,0)); outcome='resolved'; }
catch(error) { outcome=error.name; }
if (!requested || outcome !== 'GoalChanged') throw new Error(`Dig cancellation was not exercised: ${outcome}`);
const read = () => [11,12].flatMap(x=>[-60,-59].map(y=>bot.blockAt(new Vec3(x,y,0)).name));
const stopped = read();
const cleared = stopped.filter(name=>name==='air').length;
if (cleared < 1 || cleared >= 4) throw new Error('Expected a partially completed route');
await bot.waitForTicks(20);
if (JSON.stringify(read()) !== JSON.stringify(stopped)) throw new Error('Cancelled mining continued');
const damage = bot.inventory.items().find(item=>item.name==='diamond_pickaxe').durabilityUsed-damageBefore;
if (damage !== cleared) throw new Error('Tool wear does not match completed breaks');
return { outcome, blocks:stopped, cleared, durabilityUsed:damage, position:bot.entity.position };
