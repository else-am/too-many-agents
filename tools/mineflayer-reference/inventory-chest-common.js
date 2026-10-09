// Shared transfer task from inventory-chest.js; final inventory is read after close.
const stone = bot.registry.itemsByName.stone.id;
const before = bot.inventory.count(stone);
let opened = 0, closed = 0, storageClosed = 0;
bot.setQuickBarSlot(6);
if (bot.quickBarSlot !== 6 || bot.heldItem?.name !== 'diamond_pickaxe')
  throw new Error('Synchronous selection differs');
const window = await bot.openContainer(bot.blockAt(new Vec3(12,-60,2)));
opened++;
if (window.containerCount(stone) !== 80) throw new Error('Incomplete chest state');
window.on('close', () => storageClosed++);
await bot.moveSlotItem(1, window.firstEmptyInventorySlot());
await window.deposit(stone,null,10);
if (window.slots[0]?.count !== 64 || window.slots[1]?.count !== 6)
  throw new Error('Deposit merge/split differs');
await window.withdraw(stone,null,7);
const chest = window.slots.slice(0,window.inventoryStart).filter(Boolean)
  .map(item=>({slot:item.slot,name:item.name,count:item.count}));
const windowInventoryCount = window.count(stone,null);
const inventoryBeforeClose = bot.inventory.count(stone);
if (windowInventoryCount !== before+17 || window.selectedItem)
  throw new Error('Active window transfer state differs');
await window.close();
closed++;
if (bot.currentWindow) throw new Error('Window did not close');
if (bot.inventory.count(stone) !== before+17 || opened !== 1 || closed !== 1 || storageClosed !== 1)
  throw new Error('Closed inventory/window lifecycle differs');
return {chest,stone:bot.inventory.count(stone),windowInventoryCount,inventoryBeforeClose,
  selected:bot.quickBarSlot,held:bot.heldItem?.name,cursor:bot.inventory.selectedItem,opened,closed,storageClosed};
