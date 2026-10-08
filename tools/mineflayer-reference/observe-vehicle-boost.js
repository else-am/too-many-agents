// Prepare-only: one fresh normal-AI saddled Pig and undamaged carrot stick.
// Two NEW look gates: remove its saddle while mounted, then restore the saddle.
// See vehicle-boost-fixture.md. All setup/restoration is outside this script.
const pig = Object.values(bot.entities).find(e => e.getCustomName()?.toString() === 'Pig boost fixture');
const body = bot.entity, heldSlot = 36 + bot.quickBarSlot;
// Native1.21.1 keys: Entity8 + LivingEntity7 + Mob1 + AgeableMob1; Pig adds17/18.
const saddleKey = 17, boostKey = 18;
const controls = ['forward', 'back', 'left', 'right', 'jump', 'sprint', 'sneak'];
const boostDuration = () => pig.metadata[boostKey] ?? 0;
const soleRider = () => bot.entity === body && bot.vehicle === pig &&
  pig.passengers.length === 1 && pig.passengers[0] === body;
if (!(pig instanceof Entity) || pig.name !== 'pig' || !pig.alive || pig.passengers.length ||
    pig.metadata[saddleKey] !== true || ((pig.metadata[15] ?? 0) & 1) || boostDuration() !== 0 || !body.onGround ||
    body.isSleeping || bot.vehicle || bot.currentWindow || bot.inventory.selectedItem ||
    controls.some(c => bot.getControlState(c)) || !(bot.heldItem instanceof Item) ||
    bot.heldItem.name !== 'carrot_on_a_stick' || bot.heldItem.count !== 1 || bot.heldItem.durabilityUsed !== 0)
  throw new Error('Fresh native Pig boost prerequisites differ');
const before = bot.inventory.slots.map(item => JSON.stringify(item));
const held = bot.heldItem, heldComponents = JSON.stringify(held.components.filter(c => c.type !== 'damage'));
const start = Date.now();
const view = () => ({ pigUuid: pig.uuid, bodyUuid: body.uuid,
  pigPosition: pig.position.clone(), bodyPosition: body.position.clone(), velocity: pig.velocity.clone(),
  passengers: pig.passengers.map(e => e.uuid), saddle: pig.metadata[saddleKey],
  boostDuration: boostDuration(), heldName: bot.heldItem?.name, damage: bot.heldItem?.durabilityUsed });
const wait = async (predicate, phase, ticks) => {
  for (let tick = 0; !predicate() && tick < ticks; tick++) await bot.waitForTicks(1);
  if (!predicate()) throw new Error(phase + ': ' + JSON.stringify(view()));
};
if (bot.mount(pig) !== undefined) throw new Error('mount return differs');
await wait(soleRider, 'actual sole body rider', 40);
await bot.look(0, 0, true); // First readiness gate: coordinator removes saddle once.
await wait(() => pig.metadata[saddleKey] === false, 'native saddle removal', 1200);
if (!soleRider() || boostDuration() !== 0) throw new Error('Unsaddled fixture is not fresh/mounted');
const unsaddledBefore = view();
if (bot.activateItem() !== undefined) throw new Error('activateItem return differs');
await bot.waitForTicks(2);
const unsaddledAfter = view();
if (!soleRider() || pig.metadata[saddleKey] !== false || boostDuration() !== 0 ||
    bot.inventory.slots.some((item, slot) => JSON.stringify(item) !== before[slot]))
  throw new Error('Unsaddled activation spent an item or boosted: ' + JSON.stringify(unsaddledAfter));
await bot.look(body.yaw + 0.01, body.pitch, true); // Second gate: restore saddle once.
await wait(() => pig.metadata[saddleKey] === true, 'native saddle restoration', 1200);
if (!soleRider() || boostDuration() !== 0 || bot.heldItem.durabilityUsed !== 0)
  throw new Error('Saddled boost baseline differs');
if (bot.moveVehicle(0, 1) !== undefined) throw new Error('moveVehicle return differs');
await bot.waitForTicks(2);
const mounted = view();
if (bot.activateItem() !== undefined) throw new Error('boost activation return differs');
await bot.waitForTicks(2);
const activated = view();
if (!soleRider() || !Number.isInteger(boostDuration()) || boostDuration() <= 0 ||
    !(bot.heldItem instanceof Item) || bot.heldItem.name !== 'carrot_on_a_stick' ||
    bot.heldItem.count !== 1 || bot.heldItem.durabilityUsed !== 7 ||
    JSON.stringify(bot.heldItem.components.filter(c => c.type !== 'damage')) !== heldComponents)
  throw new Error('Native boost/durability/components differ: ' + JSON.stringify(activated));
await bot.waitForTicks(20);
const moving = view(), displacement = Math.hypot(
  moving.pigPosition.x - mounted.pigPosition.x, moving.pigPosition.z - mounted.pigPosition.z);
if (!soleRider() || displacement <= 0.2) throw new Error('Native boosted movement missing');
bot.clearControlStates();
bot.moveVehicle(0, 0); // Normal Pig always-forward/coasting is allowed.
await bot.waitForTicks(2);
if (bot.dismount() !== undefined) throw new Error('dismount return differs');
await wait(() => bot.vehicle === null, 'native dismount', 40);
await bot.waitForTicks(2);
if (pig.passengers.includes(body) || controls.some(c => bot.getControlState(c)) ||
    bot.currentWindow || bot.inventory.selectedItem || bot.heldItem?.durabilityUsed !== 7 ||
    bot.inventory.slots.some((item, slot) => slot !== heldSlot && JSON.stringify(item) !== before[slot]))
  throw new Error('Boost cleanup or unrelated inventory differs');
return { start, end: Date.now(), bodyUuid: body.uuid, pigUuid: pig.uuid, heldSlot,
  unsaddledBefore, unsaddledAfter, mounted, activated, moving, displacement,
  nativeBoostDuration: activated.boostDuration, durabilityBefore: 0, durabilityAfter: 7,
  unrelatedInventoryUnchanged: true, controlsCleared: true, dismounted: true };
