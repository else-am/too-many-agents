import { Vec3 } from 'vec3';

// ChestBlock.getConnectedDirection: RIGHT joins counter-clockwise from facing.
const partnerOffsets = { north: [-1, 0, 0], south: [1, 0, 0], west: [0, 0, 1], east: [0, 0, -1] };
const chestNames = new Set(['chest', 'trapped_chest']);
const shulkerColors = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
  'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black'];
const lidNames = new Set(['chest', 'trapped_chest', 'ender_chest', 'shulker_box', ...shulkerColors.map(color => `${color}_shulker_box`)]);

// The caller emits returned tuples only after complete frame hydration.
export function installBlockEvents(bot, instrumentsRegistry) {
  const instruments = new Map(Object.values(instrumentsRegistry).map(instrument => [instrument.name, instrument]));
  const openCounts = new Map();
  const signature = block => {
    const properties = block.getProperties();
    return `${block.name}:${properties.type ?? ''}:${properties.facing ?? ''}`;
  };
  // The caller reports each observed block change.
  function blockUpdated(oldBlock, block) {
    if (!block || !oldBlock || signature(oldBlock) !== signature(block))
      openCounts.delete((block ?? oldBlock)?.position.toString());
  }

  function update(records = []) {
    if (!Array.isArray(records) || records.length > 256) throw new Error('Invalid native block-event batch');
    const events = [];
    for (const record of records) {
      const p = record?.position;
      if (!p || ![p.x, p.y, p.z].every(Number.isSafeInteger)) throw new Error('Invalid native block-event position');
      const block = bot.blockAt(new Vec3(p.x, p.y, p.z));
      // The native loaded/range fence may extend beyond this guest's column view.
      if (!block) continue;
      if (record.kind === 'break') {
        if (!Number.isInteger(record.stage) || !Number.isInteger(record.breakerId)) throw new Error('Invalid native breaking event');
        const breaker = bot.entities[record.breakerId];
        if (record.stage < 0 || record.stage > 9) events.push(['blockBreakProgressEnd', block, breaker]);
        else events.push(['blockBreakProgressObserved', block, record.stage, breaker]);
        continue;
      }
      if (record.kind !== 'action' || !Number.isInteger(record.blockId) || record.blockId < 0
        || typeof record.blockName !== 'string' || record.blockName.length > 256
        || ![record.action, record.parameter].every(value => Number.isInteger(value) && value >= 0 && value <= 255))
        throw new Error('Invalid native block action');
      const name = record.blockName.startsWith('minecraft:') ? record.blockName.slice(10) : record.blockName;
      if (name === 'note_block') {
        const instrument = instruments.get(record.instrument);
        // A directly addressed action may arrive after its note block vanished.
        // There is then no authoritative note state to interpret.
        if (record.instrument === undefined && record.note === undefined) continue;
        if (!instrument || !Number.isInteger(record.note) || record.note < 0 || record.note > 24)
          throw new Error('Unsupported native note-block state');
        events.push(['noteHeard', block, instrument, record.note]);
      } else if (name === 'piston' || name === 'sticky_piston') {
        // Retain packet identity: successful retraction can already remove the base.
        events.push(['pistonMove', block, record.action, record.parameter]);
      } else if (lidNames.has(name) && record.action === 1 && block.name === name) {
        let partner = null;
        if (chestNames.has(name)) {
          const properties = block.getProperties();
          if (properties.type === 'left') continue;
          if (properties.type === 'right') {
            const offset = partnerOffsets[properties.facing];
            if (!offset) throw new Error('Invalid native chest facing');
            partner = bot.blockAt(block.position.offset(...offset));
            const other = partner?.getProperties();
            if (!partner || partner.name !== name || other.type !== 'left' || other.facing !== properties.facing) continue;
          } else if (properties.type !== 'single') throw new Error('Invalid native chest type');
        }
        const key = block.position.toString(), state = signature(block);
        const previous = openCounts.get(key);
        if (previous?.state === state && previous.count === record.parameter) continue;
        if (!previous && openCounts.size >= 1024) throw new Error('Native chest observation limit exceeded');
        openCounts.set(key, { state, count: record.parameter });
        events.push(['chestLidMove', block, record.parameter, partner]);
      }
    }
    return events;
  }
  return { update, blockUpdated };
}
