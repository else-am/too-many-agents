// C01-C03: inventory oak_log1; chest (12,-60,2) contains milk buckets3,
// sugar2, egg1, wheat3. Crafting table at (11,-60,1). Start (10.5,-60,2.5).
const id = name => bot.registry.itemsByName[name].id;
const count = name => bot.inventory.count(id(name));
const first = (name, table=null) => {
  const recipe=bot.recipesFor(id(name),null,1,table)[0];
  if(!recipe) throw new Error(`Missing fixture recipe ${name}`);
  return recipe;
};
const planksBefore=count('oak_planks'), sticksBefore=count('stick');
await bot.craft(first('oak_planks'),1);
if(count('oak_planks')!==planksBefore+4) throw new Error('Native log craft did not produce four planks');
await bot.craft(first('stick'),2);
if(count('stick')!==sticksBefore+8 || count('oak_planks')!==planksBefore) throw new Error('Craft operation count differs from output count');
const chest=await bot.openChest(bot.blockAt(new Vec3(12,-60,2)));
for(const [name,amount] of [['milk_bucket',3],['sugar',2],['egg',1],['wheat',3]]) await chest.withdraw(id(name),null,amount);
await chest.close();
const before={cake:count('cake'),bucket:count('bucket')};
const table=bot.blockAt(new Vec3(11,-60,1));
await bot.craft(first('cake',table),1,table);
if(count('cake')!==before.cake+1 || count('bucket')!==before.bucket+3 || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Native cake output/remainders/closure incomplete');
return {sticks:count('stick'),cake:count('cake'),buckets:count('bucket'),milk:count('milk_bucket'),sugar:count('sugar'),egg:count('egg'),wheat:count('wheat'),window:bot.currentWindow,cursor:bot.inventory.selectedItem};
