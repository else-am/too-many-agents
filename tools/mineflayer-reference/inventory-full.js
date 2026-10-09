// I03: player inventory has 10 stacks including stone17; chest slot0 stone48,
// slots1..26 each glass64. Withdraw into a full inventory's one compatible stack.
const stone=bot.registry.itemsByName.stone.id, glass=bot.registry.itemsByName.glass.id;
if(bot.inventory.emptySlotCount()!==26 || bot.inventory.count(stone)!==17) throw new Error('Unexpected full-inventory fixture');
const w=await bot.openContainer(bot.blockAt(new Vec3(12,-60,2)));
await w.withdraw(stone,null,43);
for(let slot=1;slot<=26;slot++) await bot.clickWindow(slot,0,1);
if(bot.inventory.emptySlotCount()!==0 || bot.inventory.count(stone)!==60) throw new Error('Failed to fill native inventory');
await w.withdraw(stone,null,4);
let failure;
try {await w.withdraw(stone,null,1);} catch(error) {failure=error.code;}
if(failure!=='DestinationFull' || bot.inventory.count(stone)!==64 || w.selectedItem || w.containerCount(stone)!==1)
  throw new Error('Full-inventory rejection lost items or cursor state');
await w.close();
return {failure,stone:bot.inventory.count(stone),glass:bot.inventory.count(glass),empty:bot.inventory.emptySlotCount(),cursor:bot.inventory.selectedItem};
