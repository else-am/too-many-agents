// Prepared only. Use the two native readiness gates in item-drop-native-scenarios.md.
const fixtureName = 'Observed drop fixture';
const named = item => item?.name === 'paper' && item.components.some(component =>
  component.type === 'custom_name' && component.data?.value === fixtureName);
if (bot.currentWindow || bot.inventory.selectedItem || bot.vehicle ||
    Object.values(bot.controlState).some(Boolean) ||
    Object.values(bot.entities).some(entity => named(entity.getDroppedItem())))
  throw new Error('Dropped-item fixture prerequisites differ');
const inventory = JSON.stringify(bot.inventory.slots), body = bot.entity, health = bot.health;
const events = [], errors = [], start = Date.now();
let dropped = null, metadata = null, physics = 0, legacy = 0, awaitingLegacy = false;
const listeners = [];
const listen = (name, fn) => { bot.on(name, fn); listeners.push([name, fn]); };
const fail = message => { if (errors.length < 16) errors.push(message); };
for (const kind of ['entitySpawn', 'entityUpdate', 'itemDrop']) listen(kind, entity => {
  const item = entity.getDroppedItem();
  if (!named(item)) return;
  if (!dropped) { dropped = entity; metadata = entity.metadata; }
  if (entity !== dropped || entity.metadata !== metadata || bot.entities[entity.id] !== entity ||
      !(entity instanceof Entity) || !(item instanceof Item) || entity.name !== 'item')
    fail(kind + ': typed/hydrated identity differs');
  if (events.length < 16) events.push({ kind, uuid: entity.uuid, count: item.count,
    name: item.name, components: item.components, position: entity.position.clone() });
  else fail('Selected event overflow');
});
listen('physicsTick', () => {
  if (awaitingLegacy) fail('physicsTick repeated before physicTick');
  physics++; awaitingLegacy = true;
});
listen('physicTick', () => {
  if (!awaitingLegacy) fail('physicTick missing preceding physicsTick');
  legacy++; awaitingLegacy = false;
});
const drops = () => events.filter(event => event.kind === 'itemDrop');
const waitDrop = async (number, count) => {
  for (let tick = 0; drops().length < number && tick < 1200; tick++) await bot.waitForTicks(1);
  if (drops().length !== number || drops()[number - 1]?.count !== count || errors.length)
    throw new Error('Dropped-item count/event differs: ' + JSON.stringify({ events, errors }));
  await bot.waitForTicks(10);
  if (drops().length !== number || errors.length)
    throw new Error('Dropped-item replay: ' + JSON.stringify({ events, errors }));
};
try {
  // Coordinator creates the one native named paper only after this NEW completed look.
  await bot.look(bot.entity.yaw, bot.entity.pitch, true);
  await waitDrop(1, 1);
  const first = events.slice(0, 3).map(event => event.kind).join(',');
  if (first !== 'entitySpawn,entityUpdate,itemDrop' || !dropped ||
      dropped.position.distanceTo(body.position) < 4 || !named(dropped.getDroppedItem()))
    throw new Error('Spawn/update/drop order or remote fixture differs');
  const beforeChange = events.length;
  // Coordinator changes only the exact observed UUID's Item.count to2 after this gate.
  await bot.look(bot.entity.yaw, bot.entity.pitch, true);
  await waitDrop(2, 2);
  const changed = events.slice(beforeChange).map(event => event.kind).join(',');
  if (changed !== 'entityUpdate,itemDrop' || dropped.getDroppedItem()?.count !== 2 ||
      bot.entities[dropped.id] !== dropped || dropped.metadata !== metadata || bot.entity !== body ||
      physics === 0 || physics !== legacy || awaitingLegacy || errors.length ||
      bot.health !== health || JSON.stringify(bot.inventory.slots) !== inventory ||
      bot.currentWindow || bot.inventory.selectedItem)
    throw new Error('Drop update/tick identity or unchanged inventory differs');
  return { start, end: Date.now(), bodyUuid: body.uuid, itemUuid: dropped.uuid,
    events, physics, legacy, inventoryUnchanged: true, metadataIdentity: true };
} catch (error) {
  console.log(JSON.stringify({ events, errors, physics, legacy, itemUuid: dropped?.uuid }));
  throw error;
} finally {
  for (const [name, fn] of listeners) bot.removeListener(name, fn);
}
