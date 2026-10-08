// Coordinator: kill the named cow after the first new completed look; spawn
// one named paper at the body after the second. Each gate allows 1200 ticks.
const cowName = 'Event target aad8063';
const paperName = 'Event paper aad8063';
const cow = Object.values(bot.entities).find(e => e.getCustomName()?.toString() === cowName);
if (!(cow instanceof Entity) || cow.health !== 20 || !cow.alive || bot.heldItem)
  throw new Error('Entity event fixture differs');
const start = Date.now(), events = [], errors = [];
const papers = () => bot.inventory.items().filter(i => i.name === 'paper').reduce((n, i) => n + i.count, 0);
const beforePaper = papers();
const check = (ok, message) => { if (!ok) errors.push(message); };
bot.on('entityHurt', (subject, cause) => {
  if (subject.uuid !== cow.uuid || cause !== bot.entity) return;
  check(subject === cow && bot.entities[subject.id] === cow && cause === bot.entity,
    'Hurt identities differ');
  check(subject.health < 20 && subject.health > 0 && subject.alive, 'Hurt state not hydrated');
  events.push({ kind: 'hurt', at: Date.now(), health: subject.health, alive: subject.alive, cause: cause?.uuid });
});
bot.on('entityDead', (subject, cause) => {
  if (subject.uuid !== cow.uuid) return;
  check(subject === cow && bot.entities[subject.id] === cow && cause === undefined,
    'Death identities differ');
  check(subject.health <= 0 && !subject.alive, 'Death state not hydrated');
  events.push({ kind: 'dead', at: Date.now(), health: subject.health, alive: subject.alive });
});
bot.on('playerCollect', (collector, collected) => {
  const item = collected?.getDroppedItem();
  if (item?.name !== 'paper' || !item.components.some(c => c.type === 'custom_name' && c.data.value === paperName)) return;
  check(collector === bot.entity && collected instanceof Entity && bot.entities[collected.id] === collected,
    'Pickup identities differ');
  check(item instanceof Item && item.count === 1 && papers() === beforePaper + 1,
    'Original pickup item or hydrated inventory differs');
  events.push({ kind: 'collect', at: Date.now(), collector: collector.uuid, id: collected.id,
    uuid: collected.uuid, item: { name: item.name, count: item.count, components: item.components }, papers: papers() });
});
const count = kind => events.filter(e => e.kind === kind).length;
const wait = async kind => {
  for (let tick = 0; !count(kind) && tick < 1200; tick++) await bot.waitForTicks(1);
  if (count(kind) !== 1 || errors.length) throw new Error(kind + ': ' + JSON.stringify({ events, errors }));
};
if (bot.attack(cow, false) !== undefined) throw new Error('Attack return differs');
await bot.waitForTicks(3);
await wait('hurt');
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
await wait('dead');
await bot.waitForTicks(5);
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
await wait('collect');
await bot.waitForTicks(5);
if (events.length !== 3 || errors.length || papers() !== beforePaper + 1)
  throw new Error('Entity event replay or final state differs: ' + JSON.stringify({ events, errors }));
return { start, end: Date.now(), bodyUuid: bot.entity.uuid, cowUuid: cow.uuid, events,
  beforePaper, afterPaper: papers(), position: bot.entity.position.clone() };
