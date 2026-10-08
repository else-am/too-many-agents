// Shared transfer task from inventory-chest.js; final inventory is read after close.
const stone = bot.registry.itemsByName.stone.id;
const before = bot.inventory.count(stone);
let opened = 0, closed = 0, storageClosed = 0;
bot.on('windowOpen', window => {
  if (window !== bot.currentWindow || window.countRange(0,window.inventoryStart,stone,null) !== 80)
    throw new Error('Open event preceded complete chest state');
  opened++;
});
bot.on('windowClose', () => { if (bot.currentWindow) throw new Error('Close state differs'); closed++; });
bot.setQuickBarSlot(6);
if (bot.quickBarSlot !== 6 || bot.heldItem?.name !== 'diamond_pickaxe')
  throw new Error('Synchronous selection differs');
const window = await bot.openChest(bot.blockAt(new Vec3(12,-60,2)));
window.on('close', () => storageClosed++);
await bot.transfer({window,itemType:stone,metadata:null,count:20,
  sourceStart:1,sourceEnd:2,destStart:window.inventoryStart,destEnd:window.inventoryEnd});
await window.deposit(stone,null,10);
if (window.slots[0]?.count !== 64 || window.slots[1]?.count !== 6)
  throw new Error('Deposit merge/split differs');
await window.withdraw(stone,null,7);
const chest = window.slots.slice(0,window.inventoryStart).filter(Boolean)
  .map(item=>({slot:item.slot,name:item.name,count:item.count}));
const windowInventoryCount = window.countRange(window.inventoryStart,window.inventoryEnd,stone,null);
const inventoryBeforeClose = bot.inventory.count(stone);
if (windowInventoryCount !== before+17 || window.selectedItem)
  throw new Error('Active window transfer state differs');
await window.close();
if (bot.inventory.count(stone) !== before+17 || opened !== 1 || closed !== 1 || storageClosed !== 1)
  throw new Error('Closed inventory/window lifecycle differs');
return {chest,stone:bot.inventory.count(stone),windowInventoryCount,inventoryBeforeClose,
  selected:bot.quickBarSlot,held:bot.heldItem?.name,cursor:bot.inventory.selectedItem,opened,closed,storageClosed};
