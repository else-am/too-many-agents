// I01/I02: chest at (12,-60,2), stone60 in slot0 and stone20 in slot1.
// Start (10.5,-60,2.5); no stone in player inventory. Real native clicks only.
const stone = bot.registry.itemsByName.stone.id;
const before = bot.inventory.count(stone);
let opened = 0, closed = 0, storageClosed = 0;
bot.on('windowOpen', window => {
  if (window !== bot.currentWindow || window.countRange(0, window.inventoryStart, stone, null) !== 80)
    throw new Error('Open event preceded complete native chest state');
  opened++;
});
bot.on('windowClose', () => { if (bot.currentWindow) throw new Error('Close event preceded native closure'); closed++; });
bot.setQuickBarSlot(6);
if (bot.quickBarSlot !== 6 || bot.heldItem?.name !== 'diamond_pickaxe') throw new Error('Synchronous selection failed');
const window = await bot.openChest(bot.blockAt(new Vec3(12,-60,2)));
window.on('close', () => storageClosed++);
await bot.transfer({window, itemType:stone, metadata:null, count:20,
  sourceStart:1, sourceEnd:2, destStart:window.inventoryStart, destEnd:window.inventoryEnd});
await window.deposit(stone, null, 10);
if (window.slots[0]?.count !== 64 || window.slots[1]?.count !== 6) throw new Error('Deposit did not merge and split exactly');
await window.withdraw(stone, null, 7);
const chest = window.slots.slice(0, window.inventoryStart).filter(Boolean).map(item => ({slot:item.slot,name:item.name,count:item.count}));
if (bot.inventory.count(stone) !== before+17 || window.selectedItem) throw new Error('Transfer state is incomplete');
await window.close();
let stale;
try { await window.withdraw(stone,null,1); } catch (error) { stale=error.code; }
if (stale !== 'WindowChanged' || opened !== 1 || closed !== 1 || storageClosed !== 1) throw new Error('Window lifecycle mismatch');
return {chest, stone:bot.inventory.count(stone), selected:bot.quickBarSlot, held:bot.heldItem?.name,
  cursor:bot.inventory.selectedItem, opened,closed,storageClosed,stale};