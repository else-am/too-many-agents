// Fixture: body (10.5,-60,0.5), two stone blocks in x=11 at y=-60,-59.
const movements = new Movements(bot);
movements.allowParkour = false;
movements.allow1by1towers = false;
movements.maxDropDown = 0;
movements.exclusionAreasStep.push(block => block.position.z !== 0 ? 100 : 0);
bot.pathfinder.setMovements(movements);
const tool = bot.inventory.items().find(item => item.name === 'diamond_pickaxe');
const damageBefore = tool.durabilityUsed;
const stoneBefore = bot.inventory.items().filter(item => item.name === 'cobblestone').reduce((n,item) => n+item.count,0);
const plannedBreaks = [];
let miningTicks = 0;
bot.on('path_update', result => { if (result.status === 'success') for (const node of result.path) plannedBreaks.push(...node.toBreak); });
bot.on('physicsTick', () => { if (bot.pathfinder.isMining()) miningTicks++; });
await bot.pathfinder.goto(new goals.GoalBlock(12,-60,0));
await bot.waitForTicks(5);
for (const y of [-60,-59]) if (bot.blockAt(new Vec3(11,y,0)).name !== 'air') throw new Error('Route did not clear its tunnel');
const after = bot.inventory.items().find(item => item.name === 'diamond_pickaxe');
const stoneAfter = bot.inventory.items().filter(item => item.name === 'cobblestone').reduce((n,item) => n+item.count,0);
if (after.durabilityUsed !== damageBefore+2 || stoneAfter !== stoneBefore+2 || miningTicks === 0)
  throw new Error(`Incorrect native mining outcome: ${JSON.stringify({damageBefore,damageAfter:after.durabilityUsed,stoneBefore,stoneAfter,miningTicks})}`);
return { position: bot.entity.position, plannedBreaks, miningTicks, durabilityUsed: after.durabilityUsed, gained: stoneAfter-stoneBefore };
