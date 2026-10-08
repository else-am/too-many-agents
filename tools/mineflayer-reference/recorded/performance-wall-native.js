const start = Date.now(), age = bot.time.bigAge;
if (bot.heldItem?.name !== 'stone' || bot.heldItem.count !== 8)
  throw new Error('Wall fixture requires eight held stone blocks');
for (let y = -60; y <= -59; y++) for (let x = 32; x <= 35; x++) {
  const position = new Vec3(x, y, 8);
  if (bot.blockAt(position)?.name !== 'air') throw new Error(`Wall target occupied: ${position}`);
  const support = bot.blockAt(position.offset(0, -1, 0));
  if (support?.name !== 'stone') throw new Error(`Wall support differs: ${position}`);
  await bot.placeBlock(support, new Vec3(0, 1, 0));
  if (bot.blockAt(position)?.name !== 'stone') throw new Error(`Wall placement missing: ${position}`);
}
const remaining = bot.inventory.items().filter(item => item.name === 'stone').reduce((n, item) => n + item.count, 0);
if (remaining !== 0) throw new Error('Wall material consumption differs');
return { placed: 8, remaining, elapsedMs: Date.now() - start, ticks: Number(bot.time.bigAge - age), position: bot.entity.position };
