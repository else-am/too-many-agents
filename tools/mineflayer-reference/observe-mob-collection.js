// Prepare an adult AI-enabled Fox named "Collection fox" with an empty hand,
// enclosed on dry ground several blocks from the body. After each NEW completed
// look, spawn exactly one paper at the collector: first Fox, then body.
const foxName = 'Collection fox';
const foxPaper = 'Fox collection paper';
const bodyPaper = 'Body collection paper';
const fox = Object.values(bot.entities).find(e => e.getCustomName()?.toString() === foxName);
if (!(fox instanceof Entity) || fox.name !== 'fox' || !fox.alive || fox.equipment[0]
  || fox.position.distanceTo(bot.entity.position) < 4 || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Collection fixture prerequisites differ');
const named = (item, name) => item?.name === 'paper'
  && item.components.some(c => c.type === 'custom_name' && c.data.value === name);
const count = name => bot.inventory.items().filter(i => named(i, name)).reduce((n, i) => n + i.count, 0);
const before = JSON.stringify(bot.inventory.slots);
const original = bot.inventory.slots.map(i => i && !named(i, bodyPaper) ? JSON.stringify(i) : null);
const beforeBodyPaper = count(bodyPaper), username = bot.username;
if (typeof username !== 'string' || !username) throw new Error('Body username missing');
const start = Date.now(), events = [], errors = [];
bot.on('playerCollect', (collector, collected) => {
  const item = collected?.getDroppedItem();
  const target = named(item, foxPaper) ? 'fox' : named(item, bodyPaper) ? 'body' : null;
  if (!target) return;
  const expected = target === 'fox' ? fox : bot.entity;
  if (collector !== expected || !(collector instanceof Entity) || !(collected instanceof Entity)
    || bot.entities[collected.id] !== collected || !(item instanceof Item) || item.count !== 1)
    errors.push(target + ' identity/original Item differs');
  const held = collector.equipment[0];
  if (target === 'fox' && (JSON.stringify(bot.inventory.slots) !== before
    || held && !named(held, foxPaper))) errors.push('Fox equipment or body inventory differs');
  if (target === 'body' && count(bodyPaper) !== beforeBodyPaper + 1)
    errors.push('Body inventory not hydrated before pickup callback');
  events.push({ target, at: Date.now(), collector: collector.uuid, collected: collected.uuid,
    id: collected.id, item: { name: item.name, count: item.count, components: item.components },
    held: held ? { name: held.name, count: held.count, components: held.components } : null,
    bodyPaperCount: count(bodyPaper) });
});
const wait = async target => {
  for (let tick = 0; !events.some(e => e.target === target) && tick < 1200; tick++) await bot.waitForTicks(1);
  if (events.filter(e => e.target === target).length !== 1 || errors.length)
    throw new Error(target + ' pickup: ' + JSON.stringify({ events, errors }));
  await bot.waitForTicks(5);
  if (events.filter(e => e.target === target).length !== 1 || errors.length)
    throw new Error(target + ' duplicate/replay: ' + JSON.stringify({ events, errors }));
};
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
await wait('fox');
if (!named(fox.equipment[0], foxPaper) || !(fox.equipment[0] instanceof Item))
  throw new Error('Fox did not retain the named paper; inspect native HandItems before classifying');
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
await wait('body');
if (events.length !== 2 || bot.username !== username || count(bodyPaper) !== beforeBodyPaper + 1)
  throw new Error('Final collection/username state differs');
if (original.some((item, slot) => item && JSON.stringify(bot.inventory.slots[slot]) !== item))
  throw new Error('Unrelated belongings changed');
return { start, end: Date.now(), username, bodyName: bot.entity.getCustomName()?.toString(),
  bodyUuid: bot.entity.uuid, foxUuid: fox.uuid, events, beforeBodyPaper, afterBodyPaper: count(bodyPaper),
  position: bot.entity.position.clone() };
