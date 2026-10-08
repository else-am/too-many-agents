// Prepare a genuinely non-burning, dry, awake, unmounted grounded body first.
// Native shared flags index0: sneak2, then the actual all-default0 value.
const entity = bot.entity, metadata = entity.metadata;
const initial = { hasFlagKey: Object.hasOwn(metadata, 0),
  flags: Object.hasOwn(metadata, 0) ? metadata[0] : null };
if (!entity.onGround || entity.isSleeping || entity.crouching || bot.vehicle ||
    entity.isInWater !== false || entity.isInLava !== false || bot.currentWindow ||
    bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean) ||
    initial.hasFlagKey && initial.flags !== 0)
  throw new Error('Metadata default fixture prerequisites differ: ' + JSON.stringify(initial));
const before = JSON.stringify(bot.inventory.slots), start = Date.now();
const events = [], updates = [], errors = [];
const snapshot = () => ({ sameEntity: bot.entity === entity,
  sameMetadata: entity.metadata === metadata, hasFlagKey: Object.hasOwn(metadata, 0),
  flags: metadata[0], crouching: entity.crouching });
const hydrated = row => row.sameEntity && row.sameMetadata && row.hasFlagKey &&
  row.flags === (row.crouching ? 2 : 0);
bot.on('entityUpdate', target => {
  if (target !== entity) return;
  const row = snapshot();
  if (!hydrated(row)) errors.push({ kind: 'update', ...row });
  updates.push(row);
});
for (const [event, crouching] of [['entityCrouch', true], ['entityUncrouch', false]]) {
  bot.on(event, target => {
    if (target !== entity) return;
    const row = { kind: event, ...snapshot() };
    if (!hydrated(row) || row.crouching !== crouching) errors.push(row);
    events.push(row);
  });
}
bot.setControlState('sneak', true);
await bot.waitForTicks(1);
if (!hydrated(snapshot()) || metadata[0] !== 2)
  throw new Error('Native exact sneak2 metadata missing');
bot.setControlState('sneak', false);
await bot.waitForTicks(3);
const reset = snapshot();
if (!hydrated(reset) || reset.flags !== 0 || errors.length || events.length !== 2 ||
    events[0].kind !== 'entityCrouch' || events[1].kind !== 'entityUncrouch')
  throw new Error('Native default0 reset/callback differs: ' + JSON.stringify({ reset, events, errors }));
const crouchUpdate = updates.findIndex(row => row.flags === 2 && row.crouching);
if (crouchUpdate < 0 || !updates.slice(crouchUpdate + 1).some(row => row.flags === 0 && !row.crouching))
  throw new Error('Hydrated entityUpdate2->0 transition missing: ' + JSON.stringify(updates));
const updateCount = updates.length;
await bot.waitForTicks(5);
if (events.length !== 2 || updates.length !== updateCount || errors.length ||
    !hydrated(snapshot()) || metadata[0] !== 0 || bot.controlState.sneak ||
    JSON.stringify(bot.inventory.slots) !== before || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Default metadata replay/cleanup differs: ' + JSON.stringify({ events, updates, errors }));
return { start, end: Date.now(), initial, events, updates, reset,
  retainedFlagKeyAtZero: true, stableMetadata: true, inventoryUnchanged: true };
