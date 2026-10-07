const inventory = bot.inventory;
const pickaxe = inventory.findInventoryItem('diamond_pickaxe');
if (!pickaxe || inventory.findInventoryItem(pickaxe.type) !== pickaxe) throw new Error('Inventory lookup differs');
const before = inventory.count(bot.registry.itemsByName.diamond.id);
let events = 0;
inventory.on('updateSlot', () => events++);
await bot.waitForTicks(3);
if (bot.inventory !== inventory || inventory.findInventoryItem('diamond_pickaxe') !== pickaxe)
  throw new Error('Unchanged window/items lost identity');
if (events !== 0) throw new Error('Unchanged inventory emitted slot updates');
return {
  slots: inventory.slots.length, start: inventory.inventoryStart, end: inventory.inventoryEnd,
  hotbar: inventory.hotbarStart, empty: inventory.emptySlotCount(),
  diamonds: before, selected: bot.quickBarSlot, held: bot.heldItem.name,
  firstEmpty: inventory.firstEmptyHotbarSlot(), cursor: inventory.selectedItem,
  equipment: bot.entity.equipment.map(item => item?.name ?? null),
};
