// Coordinator changes only this family constant; fixture details are in
// vehicle-focused-fixture.md. All setup/equipment/restoration is outside here.
const selectedFamily = 'horse'; // horse | pig | strider | minecart
const steering = { horse: null, pig: 'carrot_on_a_stick', strider: 'warped_fungus_on_a_stick', minecart: null };
const controls = ['forward', 'back', 'left', 'right', 'jump', 'sprint', 'sneak'];
const vehicle = Object.values(bot.entities).find(e => e.getCustomName()?.toString() === 'Vehicle fixture ' + selectedFamily);
if (!Object.hasOwn(steering, selectedFamily) || !(vehicle instanceof Entity)
  || vehicle.name !== selectedFamily || !vehicle.alive || vehicle.passengers.length
  || bot.vehicle !== null || controls.some(c => bot.getControlState(c))
  || bot.currentWindow || bot.inventory.selectedItem || !bot.entity.onGround
  || steering[selectedFamily] && bot.heldItem?.name !== steering[selectedFamily])
  throw new Error('Vehicle fixture prerequisites differ');
const body = bot.entity, inventory = JSON.stringify(bot.inventory.slots), start = Date.now();
const view = () => ({ body: body.position.clone(), vehicle: vehicle.position.clone(),
  velocity: vehicle.velocity.clone(), yaw: vehicle.yaw, passengers: vehicle.passengers.map(e => e?.uuid),
  bodyVehicle: bot.vehicle?.uuid ?? null });
const initial = view();
let mountEvents = 0, dismountEvents = 0, phase = 'mount';
bot.on('mount', () => { if (bot.vehicle === vehicle) mountEvents++; });
bot.on('dismount', previous => { if (previous === vehicle) dismountEvents++; });
try {
  if (bot.mount(vehicle) !== undefined) throw new Error('mount return differs');
  for (let tick = 0; bot.vehicle !== vehicle && tick < 40; tick++) await bot.waitForTicks(1);
  if (bot.vehicle !== vehicle || bot.entity !== body || vehicle.passengers[0] !== body)
    throw new Error('Actual body did not become first passenger');
  // Minecart fixture rails point north; Mineflayer yaw 0 faces negative Z.
  await bot.look(0, 0, true);
  const mounted = view();
  phase = 'move';
  if (bot.moveVehicle(0, 1) !== undefined) throw new Error('moveVehicle return differs');
  await bot.waitForTicks(20);
  const moving = view();
  const displacement = Math.hypot(moving.vehicle.x - mounted.vehicle.x, moving.vehicle.z - mounted.vehicle.z);
  if (displacement <= 0.2 || bot.vehicle !== vehicle || vehicle.passengers[0] !== body)
    throw new Error('Meaningful native vehicle movement/passenger state missing');
  phase = 'clear';
  if (bot.setControlState('forward', true) !== undefined) throw new Error('Control return differs');
  await bot.waitForTicks(5);
  bot.clearControlStates();
  if (bot.moveVehicle(0, 0) !== undefined) throw new Error('Zero input return differs');
  await bot.waitForTicks(3);
  if (controls.some(c => bot.getControlState(c))) throw new Error('Manual controls remained held');
  const cleared = view(); // Native coast and Pig/Strider always-forward are allowed.
  phase = 'dismount';
  if (bot.dismount() !== undefined) throw new Error('dismount return differs');
  for (let tick = 0; bot.vehicle !== null && tick < 40; tick++) await bot.waitForTicks(1);
  await bot.waitForTicks(2);
  if (bot.vehicle !== null || vehicle.passengers.includes(body) || bot.entity !== body
    || controls.some(c => bot.getControlState(c)) || JSON.stringify(bot.inventory.slots) !== inventory)
    throw new Error('Dismount/control/inventory cleanup differs');
  return { selectedFamily, start, end: Date.now(), bodyUuid: body.uuid, vehicleUuid: vehicle.uuid,
    initial, mounted, moving, displacement, cleared, final: view(), mountEvents, dismountEvents,
    controlsCleared: true, inventoryUnchanged: true };
} catch (error) { error.message = phase + ': ' + error.message; throw error; }
