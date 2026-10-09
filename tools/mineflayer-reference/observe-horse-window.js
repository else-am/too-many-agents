// Fixture requirements and independent native checks: remaining-native-fixtures.md.
const horse = Object.values(bot.entities).find(e => e.getCustomName()?.toString() === 'Horse window fixture');
if (!(horse instanceof Entity) || !horse.alive || bot.vehicle || bot.currentWindow ||
    bot.inventory.selectedItem || bot.controlState.sneak)
  throw new Error('Horse window prerequisites differ');
const before = JSON.stringify(bot.inventory.slots), start = Date.now();
bot.setControlState('sneak', true);
await bot.waitForTicks(1);
const window = await bot.openEntity(horse);
if (window !== bot.currentWindow || window.type !== 'HorseWindow' || window.inventoryStart !== 2 ||
    window.slots[0]?.name !== 'saddle' || window.slots[0].count !== 1 || window.selectedItem)
  throw new Error('Native HorseWindow or saddle slot differs');
const saddle = JSON.stringify(window.slots[0]);
await bot.clickWindow(0, 0, 0);
if (window.slots[0] || !(window.selectedItem instanceof Item) || window.selectedItem.name !== 'saddle' ||
    window.selectedItem.count !== 1) throw new Error('Saddle cursor pickup differs');
const cursor = { name: window.selectedItem.name, count: window.selectedItem.count,
  components: window.selectedItem.components };
await bot.clickWindow(0, 0, 0);
if (window.selectedItem || JSON.stringify(window.slots[0]) !== saddle)
  throw new Error('Saddle return differs');
await window.close();
bot.setControlState('sneak', false);
await bot.waitForTicks(1);
if (bot.currentWindow || bot.inventory.selectedItem || bot.controlState.sneak ||
    JSON.stringify(bot.inventory.slots) !== before || bot.vehicle)
  throw new Error('Horse window cleanup or original inventory differs');
return { start, end: Date.now(), horseUuid: horse.uuid, type: window.type,
  inventoryStart: window.inventoryStart, slotCount: window.slots.length, cursor,
  saddleReturned: true, inventoryUnchanged: true, closed: true };
