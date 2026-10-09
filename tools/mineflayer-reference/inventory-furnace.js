// Specialized fixture: empty furnace (12,-60,1), chest (12,-60,2) ore1/coal1.
const id=name=>bot.registry.itemsByName[name].id;
const chest=await bot.openContainer(bot.blockAt(new Vec3(12,-60,2)));
await chest.withdraw(id('iron_ore'),null,1);await chest.withdraw(id('coal'),null,1);await chest.close();
const furnace=await bot.openFurnace(bot.blockAt(new Vec3(12,-60,1)));
if(furnace.fuel!==0 || furnace.progress!==0 || furnace.inputItem() || furnace.fuelItem() || furnace.outputItem())
  throw new Error('Initial native furnace state incomplete');
let updates=0;furnace.on('update',()=>updates++);
await furnace.putInput(id('iron_ore'),null,1);await furnace.putFuel(id('coal'),null,1);
for(let ticks=0;!furnace.outputItem() && ticks<240;ticks++) await bot.waitForTicks(1);
if(furnace.outputItem()?.name!=='iron_ingot' || furnace.fuel<=0 || updates===0) throw new Error('Native furnace did not smelt/update');
const before=bot.inventory.count(id('iron_ingot'));
const output=await furnace.takeOutput();
if(output.name!=='iron_ingot' || bot.inventory.count(id('iron_ingot'))!==before+1 || furnace.outputItem())
  throw new Error('Furnace output barrier failed');
const progress={fuel:furnace.fuel,totalFuel:furnace.totalFuel,fuelSeconds:furnace.fuelSeconds,progress:furnace.progress};
await furnace.close();
return {output:output.name,ingots:bot.inventory.count(id('iron_ingot')),updates,progress,experience:bot.experience,cursor:bot.inventory.selectedItem};
