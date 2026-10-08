// Run only after the raw metadata port is packaged. No fixture mutations needed.
// Native Entity.setShiftKeyDown changes bit 1 of shared-flags metadata index 0.
// Require an awake, unmounted, grounded body with no active controls/menu/cursor.
const entity = bot.entity, metadata = entity.metadata;
if (!entity.onGround || entity.isSleeping || entity.crouching || bot.vehicle ||
    bot.currentWindow || bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean))
  throw new Error('Metadata fixture prerequisites differ');
const before = JSON.stringify(bot.inventory.slots), events = [], errors = [];
const start = Date.now();
const observe = (kind, target) => {
  if (target !== entity) return;
  const flags = metadata[0];
  const crouching = kind === 'crouch';
  if (bot.entity !== entity || entity.metadata !== metadata ||
      !Object.hasOwn(metadata, 0) || !Number.isInteger(flags) ||
      !!(flags & 2) !== crouching || entity.crouching !== crouching)
    errors.push('Callback metadata or entity state not hydrated');
  events.push({ kind, flags, crouching: entity.crouching });
};
bot.on('entityCrouch', target => observe('crouch', target));
bot.on('entityUncrouch', target => observe('uncrouch', target));
bot.setControlState('sneak', true);
await bot.waitForTicks(1);
if (!entity.crouching || !Number.isInteger(metadata[0]) || !(metadata[0] & 2))
  throw new Error('Native crouch metadata missing');
bot.setControlState('sneak', false);
await bot.waitForTicks(3);
if (bot.entity !== entity || entity.metadata !== metadata || !Object.hasOwn(metadata, 0) ||
    !Number.isInteger(metadata[0]) || (metadata[0] & 2) || entity.crouching ||
    errors.length || events.length !== 2 || events[0].kind !== 'crouch' || events[1].kind !== 'uncrouch')
  throw new Error('Metadata default reset, identity or callbacks differ: ' + JSON.stringify({ events, errors }));
if (JSON.stringify(bot.inventory.slots) !== before || bot.controlState.sneak ||
    bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Metadata check cleanup or inventory differs');
return { start, end: Date.now(), events, retainedFlagKey: true, stableMetadata: true,
  keys: Object.keys(metadata), inventoryUnchanged: true };
