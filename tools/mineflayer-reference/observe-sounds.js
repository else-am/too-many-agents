// Coordinator: after this new look completes, invoke guarded dev sounds once.
const before = JSON.stringify(bot.inventory.slots);
const events = [];
bot.on('soundEffectHeard', (name, position, volume, pitch) => {
  if (name !== 'block.note_block.harp' && name !== 'block.note_block.bell') return;
  if (!(position instanceof Vec3)) throw new Error('Sound position is not a shared Vec3');
  events.push({ name, position: position.clone(), volume, pitch });
});
await bot.look(bot.entity.yaw + 0.01, bot.entity.pitch, true);
const origin = bot.entity.position.clone();
for (let tick = 0; events.length < 3 && tick < 1200; tick++) await bot.waitForTicks(1);
const expected = [
  ['block.note_block.harp', origin.offset(32, 0, 0), 0.5, 1.25],
  ['block.note_block.harp', origin, 0.75, 0.75],
  ['block.note_block.bell', origin, 0.5, 1],
];
if (events.length !== 3) throw new Error('Native sound events missing or duplicated: ' + JSON.stringify(events));
for (let i = 0; i < expected.length; i++) {
  const [name, position, volume, pitch] = expected[i], value = events[i];
  if (value.name !== name || value.position.distanceTo(position) > 0.2
    || Math.abs(value.volume - volume) > 0.001 || Math.abs(value.pitch - pitch) > 0.001)
    throw new Error('Native sound payload or order differs: ' + JSON.stringify(events));
}
await bot.waitForTicks(5);
if (events.length !== 3 || JSON.stringify(bot.inventory.slots) !== before)
  throw new Error('Sound replay, wrong recipient or inventory mutation');
return { events, inventoryUnchanged: true };
