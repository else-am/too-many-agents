// One NEW completed look gate authorizes one native power change.
// Fixture: note_block[instrument=basedrum,note=12,powered=false] over stone.
const noteAt = new Vec3(112, -60, 64), note = bot.blockAt(noteAt);
const expectedInstrument = Object.values(bot.registry.instruments).find(i => i.name === 'basedrum');
if (note?.name !== 'note_block' || Number(note.getProperties().note) !== 12
  || note.getProperties().powered || !expectedInstrument || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Note fixture prerequisites differ');
const before = JSON.stringify(bot.inventory.slots), start = Date.now(), events = [], errors = [];
bot.on('noteHeard', (block, instrument, pitch) => {
  if (!block?.position?.equals(noteAt)) return;
  const checks = {
    typedBlock: block instanceof Block,
    typedPosition: block.position instanceof Vec3,
    sharedInstrument: instrument === expectedInstrument,
    nativeName: instrument?.name === 'basedrum',
    nativePitch: pitch === 12,
    hydratedNote: Number(bot.blockAt(noteAt)?.getProperties().note) === 12
  };
  if (Object.values(checks).some(value => !value)) errors.push(checks);
  events.push({ at: Date.now(), position: block.position.clone(), instrument: instrument?.name, pitch, checks });
});
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
for (let tick = 0; events.length === 0 && tick < 1200; tick++) await bot.waitForTicks(1);
if (events.length !== 1 || errors.length)
  throw new Error('Native note differs: ' + JSON.stringify({ events, errors }));
await bot.waitForTicks(10);
if (events.length !== 1 || JSON.stringify(bot.inventory.slots) !== before)
  throw new Error('Note replay or inventory differs: ' + JSON.stringify(events));
return { start, end: Date.now(), events, inventoryUnchanged: true };
