const start = Date.now(), age = bot.time.bigAge;
const count = name => bot.inventory.items().filter(item => item.name === name).reduce((n, item) => n + item.count, 0);
if (bot.heldItem?.name !== 'iron_axe' || ['oak_log', 'oak_planks', 'stick'].some(name => count(name)))
  throw new Error('Gather fixture inventory differs');
const damage = bot.heldItem.durabilityUsed;
for (const y of [-59, -60]) {
  const block = bot.blockAt(new Vec3(33, y, 8));
  if (block?.name !== 'oak_log') throw new Error('Gather log fixture differs');
  await bot.dig(block);
}
const movements = new Movements(bot);
movements.canDig = false;
movements.allowParkour = false;
movements.allow1by1towers = false;
movements.scafoldingBlocks = [];
bot.pathfinder.setMovements(movements);
await bot.pathfinder.goto(new goals.GoalBlock(33, -60, 8));
for (let ticks = 0; count('oak_log') < 2 && ticks < 100; ticks++) await bot.waitForTicks(1);
if (count('oak_log') !== 2) throw new Error('Actual log drops not collected');
const planks = bot.recipesFor(bot.registry.itemsByName.oak_planks.id, null, 8, false)[0];
if (!planks) throw new Error('Log recipe unavailable');
await bot.craft(planks, 2);
const sticks = bot.recipesFor(bot.registry.itemsByName.stick.id, null, 8, false)[0];
if (!sticks) throw new Error('Stick recipe unavailable');
await bot.craft(sticks, 2);
const result = { logs: count('oak_log'), planks: count('oak_planks'), sticks: count('stick') };
if (result.logs !== 0 || result.planks !== 4 || result.sticks !== 8 || bot.inventory.selectedItem || bot.currentWindow)
  throw new Error('Gather/craft final state differs');
return { ...result, axeDamage: bot.inventory.items().find(item => item.name === 'iron_axe').durabilityUsed - damage,
  elapsedMs: Date.now() - start, ticks: Number(bot.time.bigAge - age), position: bot.entity.position };
