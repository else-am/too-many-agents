// One entity-obstructed attempt; setup and independent checks are external.
const support = bot.blockAt(new Vec3(48,-61,8));
const destination = new Vec3(48,-60,8);
if (support?.name !== 'stone' || bot.blockAt(destination)?.name !== 'air' ||
    bot.heldItem?.name !== 'stone' || bot.heldItem.count !== 1)
  throw new Error('Placement refusal fixture differs');
const inventory = JSON.stringify(bot.inventory.slots);
let events = 0, rejected = false, errorName, errorMessage;
const placed = () => events++;
bot.on('blockPlaced', placed);
const started = Date.now();
try {
  try { await bot.placeBlock(support,new Vec3(0,1,0)); }
  catch (error) { rejected = true; errorName = error.name; errorMessage = error.message; }
  if (!rejected || events !== 0 || bot.blockAt(destination)?.name !== 'air' ||
      JSON.stringify(bot.inventory.slots) !== inventory)
    throw new Error('Refused placement changed world/inventory or reported success');
  return { rejected,errorName,errorMessage,events,destination:bot.blockAt(destination).name,
    remaining:bot.heldItem.count,inventoryUnchanged:true,elapsedMs:Date.now()-started };
} finally { bot.removeListener('blockPlaced',placed); }
