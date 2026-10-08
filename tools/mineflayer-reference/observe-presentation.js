// Coordinator: after this script's new native look completes, deliver the
// guarded dev presentation fixture once with that exact action ID.
const inventoryBefore = JSON.stringify(bot.inventory.slots);
const tablist = bot.tablist;
const events = [];
function observe(event) {
  if (bot.tablist !== tablist || !(tablist.header instanceof ChatMessage) || !(tablist.footer instanceof ChatMessage))
    throw new Error('Tab-list identity or classes differ');
  if (tablist.header.toString() !== 'Native header' || tablist.footer.toString() !== 'Native footer')
    throw new Error('Presentation callback preceded tab-list hydration');
  events.push(event);
}
bot.on('title', (text, type) => observe(['title', text, type]));
bot.on('title_times', (...times) => observe(['title_times', ...times]));
bot.on('title_clear', () => observe(['title_clear']));
bot.on('actionBar', message => {
  if (!(message instanceof ChatMessage)) throw new Error('Action bar is not a shared ChatMessage');
  observe(['actionBar', message.toString()]);
});
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
for (let ticks = 0; events.length < 5 && ticks < 1200; ticks++) await bot.waitForTicks(1);
const expected = [
  ['title', '<Fixture> Title', 'title'],
  ['title', 'Native subtitle', 'subtitle'],
  ['title_times', -1, 40, 5],
  ['actionBar', 'Native action bar'],
  ['title_clear'],
];
if (JSON.stringify(events) !== JSON.stringify(expected)) throw new Error('Native presentation delivery differs: ' + JSON.stringify(events));
const header = tablist.header, footer = tablist.footer;
await bot.waitForTicks(5);
if (events.length !== 5 || bot.tablist !== tablist || tablist.header !== header || tablist.footer !== footer)
  throw new Error('Presentation replay or unstable unchanged state');
if (JSON.stringify(bot.inventory.slots) !== inventoryBefore) throw new Error('Presentation changed inventory');
return { events, header: header.toMotd(), footer: footer.toString(), inventoryUnchanged: true };
