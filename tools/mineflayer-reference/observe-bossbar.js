// Three NEW completed look gates authorize create, rename, then removal.
// The coordinator owns only the exact named Wither fixture; see the note.
const firstTitle = 'Boss fixture first', nextTitle = 'Boss fixture renamed';
if (bot.bossBars.some(bar => [firstTitle, nextTitle].includes(bar.title.toString())))
  throw new Error('Boss fixture already visible');
const before = JSON.stringify(bot.inventory.slots), start = Date.now(), events = [], errors = [];
let selected;
const check = (ok, message) => { if (!ok) errors.push(message); };
bot.on('bossBarCreated', bar => {
  if (bar.title.toString() !== firstTitle) return;
  selected = bar;
  check(bar instanceof BossBar && bar.title instanceof ChatMessage && bot.bossBars.includes(bar),
    'Created bar class/list not hydrated');
  check(bar.title.json.color === 'gold', 'Created title style differs');
  check(Number.isFinite(bar.health) && bar.health >= 0 && bar.health <= 1, 'Native progress invalid');
  events.push({ kind: 'created', at: Date.now(), uuid: bar.entityUUID, title: bar.title.toString(), health: bar.health });
});
bot.on('bossBarUpdated', bar => {
  if (!selected || bar.entityUUID !== selected.entityUUID) return;
  check(bar === selected && bar.title instanceof ChatMessage && bot.bossBars.includes(bar),
    'Updated bar identity/list differs');
  check(bar.title.json.color === 'aqua', 'Renamed title style differs');
  events.push({ kind: 'updated', at: Date.now(), title: bar.title.toString() });
});
bot.on('bossBarDeleted', bar => {
  if (!selected || bar.entityUUID !== selected.entityUUID) return;
  check(bar === selected && !bot.bossBars.some(row => row.entityUUID === bar.entityUUID),
    'Deleted bar identity/list differs');
  events.push({ kind: 'deleted', at: Date.now(), title: bar.title.toString() });
});
const wait = async (predicate, phase) => {
  for (let tick = 0; !predicate() && tick < 1200; tick++) await bot.waitForTicks(1);
  if (!predicate() || errors.length) throw new Error(phase + ': ' + JSON.stringify({ events, errors }));
};
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
await wait(() => !!selected, 'create');
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
await wait(() => events.some(e => e.kind === 'updated' && e.title === nextTitle), 'rename');
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
await wait(() => events.some(e => e.kind === 'deleted'), 'remove');
await bot.waitForTicks(5);
if (events.length !== 3 || errors.length || JSON.stringify(bot.inventory.slots) !== before)
  throw new Error('Boss event replay or inventory differs: ' + JSON.stringify({ events, errors }));
return { start, end: Date.now(), events, stableBar: true, inventoryUnchanged: true };
