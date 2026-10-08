// PREPARED ONLY: normal bound-body immunity currently prevents native /kill.
// Coordinator must substitute ONLY the newly created disposable body's UUID.
// Never use ScriptProbe. Run once with minecraft_run timeoutMs: 60000.
const expectedBodyUuid = 'REPLACE_WITH_NEW_DISPOSABLE_BODY_UUID';
const entity = bot.entity;
if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(expectedBodyUuid) ||
    entity.uuid !== expectedBodyUuid)
  throw new Error('Self-death fixture requires the exact new disposable body UUID');
if (bot.isAlive !== true || entity.alive !== true || !(bot.health > 0) ||
    bot.currentWindow || bot.inventory.selectedItem || bot.inventory.slots.some(Boolean) ||
    bot.vehicle || entity.isSleeping || Object.values(bot.controlState).some(Boolean))
  throw new Error('Self-death fixture must be alive, empty, awake, unmounted and idle');

let sequence = 0, deaths = 0, terminalHealthSequence = null;
function state(kind, args) {
  return { fixture: 'self-death', kind, sequence: ++sequence, argc: args.length,
    uuid: bot.entity.uuid, sameEntity: bot.entity === entity,
    health: bot.health, isAlive: bot.isAlive, entityHealth: entity.health,
    entityAlive: entity.alive, position: entity.position,
    inventoryEmpty: !bot.inventory.slots.some(Boolean),
    cursorEmpty: bot.inventory.selectedItem == null, menuClosed: bot.currentWindow == null };
}
function hydratedDead(row) {
  return row.sameEntity && row.uuid === expectedBodyUuid && row.health <= 0 &&
    row.isAlive === false && row.entityHealth <= 0 && row.entityAlive === false;
}
bot.on('health', (...args) => {
  const row = state('health', args);
  row.terminal = hydratedDead(row);
  row.valid = args.length === 0 && row.sameEntity;
  if (row.terminal) terminalHealthSequence = row.sequence;
  console.log(JSON.stringify(row));
});
bot.on('death', (...args) => {
  const row = state('death', args);
  row.deaths = ++deaths;
  row.valid = args.length === 0 && deaths === 1 && hydratedDead(row) &&
    terminalHealthSequence === row.sequence - 1;
  // Do not throw, catch the terminal abort, mutate, or return from this callback.
  // The retained log must survive the native body's expected body_dead error.
  console.log(JSON.stringify(row));
});
console.log(JSON.stringify(state('armed', [])));
// One same-orientation forced look is a native readiness marker. Its new,
// completed action ID is observable externally after these listeners exist.
await bot.look(entity.yaw, entity.pitch, true);
console.log(JSON.stringify(state('waiting', [])));
await bot.waitForTicks(900);
// A successful script return is never evidence of terminal delivery.
throw new Error('Self-death fixture expired without terminal stream abort');
