// Coordinator must prepare the inactive impulse fixture described in the note.
const position = new Vec3(112, -60, 64), before = JSON.stringify(bot.inventory.slots);
const initial = bot.blockAt(position);
if (initial?.name !== 'command_block' || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Command block prerequisites differ');
const facing = initial.getProperties().facing, start = Date.now(), stages = [];
const inspect = (name, conditional) => {
  const block = bot.blockAt(position), properties = block?.getProperties();
  if (block?.name !== name || properties.facing !== facing || properties.conditional !== conditional)
    throw new Error('Command block type/facing/conditional differs');
  stages.push({ name: block.name, stateId: block.stateId, properties, blockEntity: block.blockEntity });
};
if (bot.setCommandBlock(position, 'say Unexecuted fixture') !== undefined)
  throw new Error('Default command edit return differs');
await bot.waitForTicks(1);
inspect('command_block', false);
if (bot.setCommandBlock(position, 'say Unexecuted fixture',
    { mode: 0, conditional: true, trackOutput: true, alwaysActive: false }) !== undefined)
  throw new Error('Mode change return differs');
await bot.waitForTicks(1);
inspect('chain_command_block', true);
if (JSON.stringify(bot.inventory.slots) !== before) throw new Error('Command edit changed inventory');
// Deliberately no await after restoration: the runner must drain this control.
if (bot.setCommandBlock(position, '') !== undefined) throw new Error('Restore return differs');
return { start, end: Date.now(), position, facing, stages, queuedRestore: true,
  inventoryUnchanged: true };
