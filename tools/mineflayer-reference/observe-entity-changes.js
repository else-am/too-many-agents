// Fixture phase: existing subject has no offhand, no passenger, drop damage 7.
// Controller changes name, restores shield/passenger, and sets drop damage 11.
const find = name => Object.values(bot.entities).find(e=>e.getCustomName()?.toString()===name);
const stand=find('Fixture subject'), passenger=find('Fixture passenger'), drop=find('Fixture drop');
if (!stand || !passenger || !drop || stand.equipment[1]!==null || drop.getDroppedItem().durabilityUsed!==7) throw new Error('Changed fixture before observation');
const position=stand.position, velocity=stand.velocity, unchanged=stand.equipment[0];
for(let ticks=0;ticks<400;ticks++) {
  await bot.waitForTicks(1);
  if(stand.getCustomName()?.toString()==='Updated fixture' && stand.equipment[1]?.name==='shield' && passenger.vehicle===stand && stand.passengers.includes(passenger) && drop.getDroppedItem().durabilityUsed===11) {
    if(bot.entities[stand.id]!==stand || stand.position!==position || stand.velocity!==velocity || stand.equipment[0]!==unchanged) throw new Error('Unchanged identity lost');
    return {name:stand.getCustomName().toString(),offhand:stand.equipment[1].name,vehicle:passenger.vehicle.id,passengers:stand.passengers.map(e=>e.id),damage:drop.getDroppedItem().durabilityUsed,ticks};
  }
}
throw new Error('Native fixture changes did not arrive');
