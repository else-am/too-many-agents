// Coordinator teleports this exact body +2 X, then back, at two NEW look gates.
// Both floor positions must be loaded, clear, safe and within the body's bounds.
const body = bot.entity, position = body.position, origin = position.clone();
if (!body.onGround || body.isSleeping || bot.vehicle || bot.currentWindow
  || bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean))
  throw new Error('Teleport fixture prerequisites differ');
const before = JSON.stringify(bot.inventory.slots), events = [], errors = [], start = Date.now();
bot.on('forcedMove', (...args) => {
  if (args.length || bot.entity !== body || body.position !== position)
    errors.push('forcedMove arguments or shared identity differ');
  events.push({at:Date.now(), position:position.clone(), yaw:body.yaw, pitch:body.pitch});
});
const wait = async (count, expected) => {
  for (let tick = 0; events.length < count && tick < 1200; tick++) await bot.waitForTicks(1);
  if (events.length !== count || errors.length || position.distanceTo(expected) > 0.2
    || events[count - 1].position.distanceTo(expected) > 0.2)
    throw new Error('Accepted teleport callback differs: ' + JSON.stringify({events,errors}));
};
await bot.look(body.yaw + 0.01, body.pitch, true);
await wait(1, origin.offset(2,0,0));
await bot.waitForTicks(5);
if (events.length !== 1) throw new Error('Teleport event replayed');
await bot.look(body.yaw + 0.01, body.pitch, true);
await wait(2, origin);
await bot.waitForTicks(5);
if (events.length !== 2 || errors.length || JSON.stringify(bot.inventory.slots) !== before)
  throw new Error('Ordinary look, replay or inventory changed teleport outcome');
return {start,end:Date.now(),origin,events,position:position.clone(),inventoryUnchanged:true};
