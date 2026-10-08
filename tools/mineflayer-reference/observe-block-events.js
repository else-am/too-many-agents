// Three NEW look gates: note power, piston power, piston unpower. Then own chest.
// See recent-port-native-fixtures.md; targets must be inspected before execution.
const noteAt = new Vec3(115,-60,64), pistonAt = new Vec3(115,-60,67), chestAt = new Vec3(111,-60,64);
const note = bot.blockAt(noteAt), piston = bot.blockAt(pistonAt), chest = bot.blockAt(chestAt);
if (note?.name !== 'note_block' || piston?.name !== 'piston' || chest?.name !== 'chest'
  || piston.getProperties().facing !== 'up' || piston.getProperties().extended
  || chest.getProperties().type !== 'single' || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Block-event fixture prerequisites differ');
const pitch = note.getProperties().note, instrumentName = note.getProperties().instrument;
const before = JSON.stringify(bot.inventory.slots), start = Date.now(), events = [], errors = [];
const at = (block, target) => block?.position?.equals(target);
bot.on('noteHeard', (block, instrument, value) => {
  if (!at(block,noteAt)) return;
  if (!(block instanceof Block) || registryInstrument(instrument)?.name !== instrumentName || value !== pitch)
    errors.push('Note block/instrument/pitch differs');
  events.push({kind:'note',instrument:instrument.name,pitch:value});
});
function registryInstrument(value) { return Object.values(bot.registry.instruments).find(entry => entry === value); }
bot.on('pistonMove', (block, pulling, direction) => {
  if (!at(block,pistonAt)) return;
  if (!(block instanceof Block) || ![0,1].includes(pulling) || direction !== 1)
    errors.push('Piston payload differs');
  events.push({kind:'piston',pulling,direction});
});
bot.on('chestLidMove', (block,count,partner) => {
  if (!at(block,chestAt)) return;
  if (!(block instanceof Block) || partner !== null || !Number.isInteger(count)) errors.push('Chest payload differs');
  events.push({kind:'lid',count});
});
const wait = async (predicate, phase) => {
  for (let tick = 0; !predicate() && tick < 1200; tick++) await bot.waitForTicks(1);
  if (!predicate() || errors.length) throw new Error(phase + ': ' + JSON.stringify({events,errors}));
};
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
await wait(() => events.some(e => e.kind === 'note'),'note');
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
await wait(() => events.some(e => e.kind === 'piston' && e.pulling === 0),'extend');
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
await wait(() => events.some(e => e.kind === 'piston' && e.pulling === 1),'retract');
const window = await bot.openContainer(chest);
await wait(() => events.some(e => e.kind === 'lid' && e.count === 1),'open');
await window.close();
await wait(() => events.some(e => e.kind === 'lid' && e.count === 0),'close');
await bot.waitForTicks(10);
if (events.length !== 5 || errors.length || bot.currentWindow || bot.inventory.selectedItem
  || JSON.stringify(bot.inventory.slots) !== before)
  throw new Error('Block event replay/cleanup differs: ' + JSON.stringify({events,errors}));
return {start,end:Date.now(),events,inventoryUnchanged:true};
