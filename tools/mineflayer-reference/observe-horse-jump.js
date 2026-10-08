// PREPARED ONLY. See horse-jump-fixture.md; coordinator substitutes this one UUID.
const horseUuid = 'REPLACE_WITH_FRESH_HORSE_UUID';
const horse = Object.values(bot.entities).find(entity => entity.uuid === horseUuid);
const body = bot.entity;
const controls = ['forward', 'back', 'left', 'right', 'jump', 'sprint', 'sneak'];
if (!(horse instanceof Entity) || horse.name !== 'horse' || horse.alive !== true ||
    !(horse.health > 0) || !horse.onGround || horse.passengers.length !== 0 ||
    horse.velocity.distanceTo(new Vec3(0, 0, 0)) > 0.1 ||
    body.position.distanceTo(horse.position) > 2.5 || bot.vehicle !== null ||
    !body.onGround || body.alive !== true || body.isSleeping || bot.heldItem ||
    bot.currentWindow || bot.inventory.selectedItem || controls.some(c => bot.getControlState(c)))
  throw new Error('Horse-jump fixture prerequisites differ; do not retry mounting');
const inventory = JSON.stringify(bot.inventory.slots), selected = bot.quickBarSlot;
const horseHealth = horse.health, bodyHealth = bot.health, started = Date.now();
let phase = 'mount', floorY = null, peakY = null, airborne = false, descending = false;
let landedTicks = 0, heldAirborne = false, ownershipChanged = false;
const trace = [];
const view = () => ({ phase, y: horse.position.y, vy: horse.velocity.y,
  onGround: horse.onGround, health: horse.health, bodyHealth: bot.health,
  passengers: horse.passengers.map(entity => entity.uuid), vehicle: bot.vehicle?.uuid ?? null });
function soleRider() {
  return bot.entity === body && bot.vehicle === horse && horse.passengers.length === 1 &&
    horse.passengers[0] === body && horse.alive === true;
}
function observe() {
  if (phase !== 'charge' && phase !== 'release') return;
  if (!soleRider()) ownershipChanged = true;
  if (trace.length < 120) trace.push(view());
  if (phase === 'charge' && !horse.onGround) heldAirborne = true;
  if (phase === 'release') {
    peakY = Math.max(peakY, horse.position.y);
    if (!horse.onGround && horse.position.y > floorY + 0.35) airborne = true;
    if (airborne && !horse.onGround && horse.velocity.y < -0.01) descending = true;
    landedTicks = airborne && descending && horse.onGround ? landedTicks + 1 : 0;
  }
}
bot.on('physicsTick', observe);
try {
  if (bot.mount(horse) !== undefined) throw new Error('mount must return void');
  for (let tick = 0; bot.vehicle !== horse && tick < 40; tick++) await bot.waitForTicks(1);
  if (!soleRider()) throw new Error('Actual body is not the sole horse rider');
  await bot.waitForTicks(3);
  if (!horse.onGround || !soleRider()) throw new Error('Mounted horse is not settled on support');
  floorY = peakY = horse.position.y;
  console.log(JSON.stringify({ kind: 'horse-jump-mounted', horseUuid, bodyUuid: body.uuid, state: view() }));
  phase = 'charge';
  if (bot.setControlState('jump', true) !== undefined) throw new Error('setControlState must return void');
  await bot.waitForTicks(12);
  if (!bot.getControlState('jump') || heldAirborne || ownershipChanged)
    throw new Error('Charge state, grounded hold or rider ownership differs');
  phase = 'release';
  bot.setControlState('jump', false);
  // A positive tick wait drains the native release. The listener also sees the
  // first airborne frame if it arrives before that control promise resolves.
  for (let tick = 0; landedTicks < 2 && tick < 70; tick++) await bot.waitForTicks(1);
  if (!airborne || !descending || landedTicks < 2 || peakY - floorY <= 0.35 ||
      Math.abs(horse.position.y - floorY) > 0.35 || ownershipChanged || !soleRider())
    throw new Error('Charged release did not produce a native ascent and supported landing');
  const landing = view();
  phase = 'clear';
  bot.clearControlStates();
  bot.moveVehicle(0, 0);
  await bot.waitForTicks(3);
  if (controls.some(c => bot.getControlState(c)) || !soleRider())
    throw new Error('Mounted controls did not clear');
  phase = 'dismount';
  bot.dismount();
  for (let tick = 0; bot.vehicle !== null && tick < 40; tick++) await bot.waitForTicks(1);
  await bot.waitForTicks(2);
  if (bot.vehicle !== null || horse.passengers.length !== 0 || bot.entity !== body ||
      controls.some(c => bot.getControlState(c)) || horse.health !== horseHealth || bot.health !== bodyHealth ||
      JSON.stringify(bot.inventory.slots) !== inventory || bot.quickBarSlot !== selected ||
      bot.inventory.selectedItem || bot.currentWindow)
    throw new Error('Dismount, health, controls or inventory/selection changed');
  return { horseUuid, bodyUuid: body.uuid, started, ended: Date.now(),
    rise: peakY - floorY, airborne, descending, landing, final: view(), trace,
    soleBodyRiderDuringJump: true, controlsCleared: true, inventoryUnchanged: true };
} catch (error) {
  console.log(JSON.stringify({ kind: 'horse-jump-failed', phase, state: view(), trace }));
  // Preserve the first failure; scoped host/native cleanup owns control release.
  // Do not issue a speculative dismount or retry after an unknown action outcome.
  throw error;
} finally {
  bot.removeListener('physicsTick', observe);
}
