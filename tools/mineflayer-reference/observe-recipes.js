// Fixture: one oak log, fewer than nine diamonds, no sticks; crafting table (9,-60,2).
const ids = bot.registry.itemsByName;
const before = bot.inventory.items().map(item => [item.slot,item.type,item.count]);
if (bot.inventory.count(ids.oak_log.id) !== 1) throw new Error('Expected one native oak log');
const planks = bot.recipesFor(ids.oak_planks.id, null, 4);
if (!planks.length || planks.some(recipe=>!(recipe instanceof Recipe) || recipe.requiresTable || recipe.result.count !== 4))
  throw new Error('Incorrect recipe objects for four planks');
if (bot.recipesFor(ids.oak_planks.id,null,5).length || bot.recipesFor(ids.diamond_block.id,null,1,true).length)
  throw new Error('Insufficient inventory was treated as craftable');
const table = bot.blockAt(new Vec3(9,-60,2));
if (bot.recipesAll(ids.diamond_pickaxe.id,null).length || !bot.recipesAll(ids.diamond_pickaxe.id,null,table).length)
  throw new Error('Crafting-table filtering is incorrect');
if (bot.recipesFor(ids.diamond_pickaxe.id,null,1,table).length) throw new Error('Missing sticks were ignored');
if (JSON.stringify(before)!==JSON.stringify(bot.inventory.items().map(item=>[item.slot,item.type,item.count])))
  throw new Error('Recipe queries mutated inventory');
return { logs:bot.inventory.count(ids.oak_log.id), planks:planks.map(recipe=>({result:recipe.result,delta:recipe.delta,requiresTable:recipe.requiresTable})), tableRecipes:bot.recipesAll(ids.diamond_pickaxe.id,null,table).length };
