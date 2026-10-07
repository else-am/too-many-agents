// I05/I06: chest at (12,-60,2), iron helmet slot2, shield slot3.
// Native inventory contains stone17, pickaxe hotbar6 and named sword hotbar0.
const stone = bot.registry.itemsByName.stone.id;
const source = bot.inventory.items().find(item => item.type === stone);
if (source?.count !== 17) throw new Error('Expected the chest transfer fixture stone17');
const empty = bot.inventory.firstEmptyInventorySlot();
await bot.simpleClick.rightMouse(source.slot);
if (bot.inventory.selectedItem?.count !== 9 || bot.inventory.slots[source.slot]?.count !== 8) throw new Error('Native odd split failed');
await bot.simpleClick.rightMouse(empty);
if (bot.inventory.selectedItem?.count !== 8 || bot.inventory.slots[empty]?.count !== 1) throw new Error('Native one-item placement failed');
await bot.simpleClick.leftMouse(source.slot);
await bot.moveSlotItem(empty, source.slot);
if (bot.inventory.slots[source.slot]?.count !== 17 || bot.inventory.selectedItem) throw new Error('Split recovery failed');
const window = await bot.openChest(bot.blockAt(new Vec3(12,-60,2)));
await bot.equip(window.slots[3], 'off-hand');
if (bot.currentWindow !== window || bot.inventory.slots[45]?.name !== 'shield') throw new Error('Container offhand mapping failed');
await bot.equip(window.slots[2], 'head');
if (bot.currentWindow || bot.inventory.slots[5]?.name !== 'iron_helmet') throw new Error('Armor staging/close failed');
await bot.unequip('head');
await bot.unequip('off-hand');
if (bot.inventory.slots[5] || bot.inventory.slots[45]) throw new Error('Unequip did not update equipment');
await bot.equip(bot.registry.itemsByName.diamond_sword.id, 'hand');
if (bot.heldItem?.name !== 'diamond_sword' || bot.heldItem.durabilityUsed !== 7) throw new Error('Equip lost sword identity/components');
bot.setQuickBarSlot(6); // Deliberately no await: script completion must drain this native control.
return {held:bot.heldItem?.name, selected:bot.quickBarSlot, stone:bot.inventory.count(stone),
  helmet:bot.inventory.count(bot.registry.itemsByName.iron_helmet.id), shield:bot.inventory.count(bot.registry.itemsByName.shield.id),
  cursor:bot.inventory.selectedItem, window:bot.currentWindow};
