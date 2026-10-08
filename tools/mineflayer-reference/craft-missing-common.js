if (bot.game.gameMode !== 'survival' || bot.inventory.items().length ||
    bot.inventory.selectedItem || bot.currentWindow)
  throw new Error('Missing-materials fixture differs');
const id=bot.registry.itemsByName.crafting_table.id;
const all=bot.recipesAll(id,null,null), available=bot.recipesFor(id,null,1,null);
if (!Array.isArray(all) || !all.length || !Array.isArray(available) || available.length)
  throw new Error('Recipe discovery/availability differs');
const recipe=all.find(value=>!value.requiresTable && value.result.id===id && value.result.count===1);
if (!recipe) throw new Error('Expected ordinary 2x2 crafting-table recipe');
const before=JSON.stringify(bot.inventory.slots),started=Date.now();
let rejection;
try { await bot.craft(recipe,1,null); }
catch(error) { rejection={name:error.name,message:error.message,code:error.code??null}; }
if (!rejection || JSON.stringify(bot.inventory.slots)!==before || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Missing-materials craft succeeded or changed inventory/menu');
await bot.waitForTicks(5);
if (JSON.stringify(bot.inventory.slots)!==before || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Rejected craft changed inventory afterward');
return {allRecipes:all.length,availableRecipes:available.length,requiresTable:recipe.requiresTable,
  rejection,inventoryUnchanged:true,elapsedMs:Date.now()-started};
