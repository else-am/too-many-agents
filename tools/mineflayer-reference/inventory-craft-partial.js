// C04: two oak planks in chest slot0, no planks/buttons initially in inventory.
// Request three one-plank button crafts: retain two real outputs, then fail honestly.
const planks=bot.registry.itemsByName.oak_planks.id,button=bot.registry.itemsByName.oak_button.id;
const before=bot.inventory.count(button);
const w=await bot.openContainer(bot.blockAt(new Vec3(12,-60,2)));
await w.withdraw(planks,null,2);await w.close();
const recipe=bot.recipesFor(button,null,1)[0];
let failed;
try {await bot.craft(recipe,3);} catch(error) {failed={code:error.code,completedCrafts:error.completedCrafts};}
if(failed?.code!=='InsufficientItems' || failed.completedCrafts!==2 || bot.inventory.count(button)!==before+2 || bot.inventory.count(planks)!==0 || bot.inventory.selectedItem)
  throw new Error('Partial craft outcome did not match native work');
if(bot.inventory.slots.slice(1,5).some(Boolean)) throw new Error('Known craft failure left input grid uncleared');
return {failed,buttons:bot.inventory.count(button),planks:bot.inventory.count(planks),cursor:bot.inventory.selectedItem};
