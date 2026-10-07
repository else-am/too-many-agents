// The same procedure is intended to run on the reference bot and native body.
// Fixture: survival, one diamond pickaxe, diamond ore at (5, -60, 0).
const ore = bot.findBlock({ matching: bot.registry.blocksByName.diamond_ore.id, maxDistance: 16 });
if (!ore || typeof ore.then === 'function') throw new Error('findBlock must synchronously return the ore');
const tool = bot.inventory.items().find(item => item.name === 'diamond_pickaxe');
if (!tool) throw new Error('Fixture pickaxe is missing');
const moved = await bot.pathfinder.goto(new goals.GoalNear(ore.position.x, ore.position.y, ore.position.z, 2));
const equipped = await bot.equip(tool, 'hand');
const dug = await bot.dig(ore);
if (moved !== undefined || equipped !== undefined || dug !== undefined)
  throw new Error('goto, equip and dig must resolve without a value');
await bot.pathfinder.goto(new goals.GoalNear(ore.position.x, ore.position.y, ore.position.z, 0));
for (let i = 0; i < 40 && !bot.inventory.items().some(item => item.name === 'diamond'); i++)
  await bot.waitForTicks(1);
return {
  ore: bot.blockAt(ore.position).name,
  diamonds: bot.inventory.items().filter(item => item.name === 'diamond').reduce((sum, item) => sum + item.count, 0),
  held: bot.heldItem?.name,
};
