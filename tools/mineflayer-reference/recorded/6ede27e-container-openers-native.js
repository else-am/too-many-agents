// Inspect/correct this disposable single-chest coordinate before invocation.
const chestAt = new Vec3(111, -60, 64), chest = bot.blockAt(chestAt);
if (chest?.name !== 'chest' || chest.getProperties().type !== 'single' ||
    bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Held-open chest prerequisites differ');
const before = JSON.stringify(bot.inventory.slots), selection = bot.quickBarSlot;
const start = Date.now(), events = [], errors = [];
let heldWindow;
bot.on('chestLidMove', (block, count, partner) => {
  if (!block?.position?.equals(chestAt)) return;
  if (!(block instanceof Block) || partner !== null || ![0, 1].includes(count))
    errors.push('Invalid single-chest lid payload');
  if (count === 0 && bot.currentWindow && (!heldWindow || bot.currentWindow === heldWindow))
    errors.push('Premature zero while native window is open');
  events.push({ at: Date.now(), count, windowOpen: !!bot.currentWindow });
});
const wait = async count => {
  for (let tick = 0; events.length < count && tick < 80; tick++) await bot.waitForTicks(1);
  if (events.length !== count || errors.length)
    throw new Error('Chest lid count differs: ' + JSON.stringify({ events, errors }));
};
heldWindow = await bot.openContainer(chest);
await wait(1);
await bot.waitForTicks(12);
if (bot.currentWindow !== heldWindow || events.length !== 1 || events[0].count !== 1 || errors.length)
  throw new Error('Held-open counter changed: ' + JSON.stringify({ events, errors }));
await heldWindow.close();
await wait(2);
await bot.waitForTicks(10);
if (bot.currentWindow || events.length !== 2 || errors.length)
  throw new Error('First close counter changed: ' + JSON.stringify({ events, errors }));
heldWindow = await bot.openContainer(chest);
await wait(3);
await bot.waitForTicks(1);
if (bot.currentWindow !== heldWindow || errors.length)
  throw new Error('Second open did not retain its native window');
await heldWindow.close();
await wait(4);
await bot.waitForTicks(10);
if (JSON.stringify(events.map(e => e.count)) !== '[1,0,1,0]' || errors.length ||
    bot.currentWindow || bot.inventory.selectedItem || bot.quickBarSlot !== selection ||
    JSON.stringify(bot.inventory.slots) !== before)
  throw new Error('Opener reuse/cleanup differs: ' + JSON.stringify({ events, errors }));
return { start, end: Date.now(), chestAt, events, heldTicks: 12,
  inventoryUnchanged: true, selectionUnchanged: true, cursorEmpty: true };
