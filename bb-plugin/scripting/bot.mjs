import { Vec3 } from 'vec3';
import goals from 'mineflayer-pathfinder/lib/goals.js';
import data from 'minecraft-version-data';
import { createBlockClass } from './blocks.mjs';

// This first slice is intentionally not marked conformant in coverage.json.
// Coverage expands through shared scripts and reference comparisons.
export function createBot(initial) {
  const registry = {
    ...data,
    blocksArray: data.blocksArray, itemsArray: data.itemsArray,
    blocksByName: Object.fromEntries(data.blocksArray.map(block => [block.name, block])),
    itemsByName: Object.fromEntries(data.itemsArray.map(item => [item.name, item])),
    blocks: Object.fromEntries(data.blocksArray.map(block => [block.id, block])),
    items: Object.fromEntries(data.itemsArray.map(item => [item.id, item])),
  };
  const Block = createBlockClass(registry);
  let snapshot;
  const vector = ({ x, y, z }) => new Vec3(x, y, z);
  const item = entry => {
    if (!entry || entry.count === 0) return null;
    const type = registry.itemsByName[entry.id.replace(/^minecraft:/, '')];
    if (!type) throw new Error(`Unknown item registry entry ${entry.id}`);
    const slot = entry.slot === undefined ? undefined : entry.slot < 9 ? entry.slot + 36 : entry.slot === 40 ? 45 : entry.slot < 36 ? entry.slot : 44 - entry.slot;
    return { type: type.id, name: type.name, displayName: type.displayName, count: entry.count,
      stackSize: type.stackSize, metadata: 0, slot, durabilityUsed: entry.damage ?? 0 };
  };
  const bot = {
    registry, version: '1.21.1',
    inventory: { slots: new Array(46).fill(null), items() { return this.slots.slice(9, 45).filter(Boolean); } },
    blockAt(position, extraInfos = true) {
      const p = position.floored();
      const { min, size, states } = snapshot.blocks;
      const x = p.x - min[0], y = p.y - min[1], z = p.z - min[2];
      if (x < 0 || y < 0 || z < 0 || x >= size[0] || y >= size[1] || z >= size[2]) return null;
      const index = (y * size[2] + z) * size[0] + x;
      const stateId = states[index];
      if (stateId === -1) return null;
      const block = Block.fromStateId(stateId, snapshot.blocks.biomes[index]);
      block.position = p;
      block.light = snapshot.blocks.light[index] & 15;
      block.skyLight = snapshot.blocks.light[index] >> 4;
      if (extraInfos && snapshot.blocks.entities[index])
        block.entity = JSON.parse(JSON.stringify(snapshot.blocks.entities[index]));
      return block;
    },
    findBlocks({ point = bot.entity.position, matching, maxDistance = 16, count = 1, useExtraInfo = false }) {
      if (useExtraInfo) throw new Error('findBlocks useExtraInfo is pending implementation');
      const matches = typeof matching === 'function' ? matching
        : block => (Array.isArray(matching) ? matching : [matching]).includes(block.type);
      const found = [];
      const { min, size } = snapshot.blocks;
      for (let y = min[1]; y < min[1] + size[1]; y++)
        for (let z = min[2]; z < min[2] + size[2]; z++)
          for (let x = min[0]; x < min[0] + size[0]; x++) {
            const position = new Vec3(x, y, z);
            if (position.distanceTo(point) > maxDistance) continue;
            const block = bot.blockAt(position);
            if (block && matches(block)) found.push(position);
          }
      found.sort((a, b) => a.distanceSquared(point) - b.distanceSquared(point));
      return found.slice(0, count);
    },
    findBlock(options) { const [position] = bot.findBlocks({ ...options, count: 1 }); return position ? bot.blockAt(position) : null; },
    async equip(itemOrId, destination) {
      const destinations = { hand: 'mainhand', 'off-hand': 'offhand', head: 'head', torso: 'chest', legs: 'legs', feet: 'feet' };
      if (!destinations[destination]) throw new Error(`Invalid destination ${destination}`);
      const selected = typeof itemOrId === 'number' ? bot.inventory.items().find(item => item.type === itemOrId) : itemOrId;
      if (!selected || !Number.isInteger(selected.slot)) throw new Error('Item is not in the inventory');
      const slot = selected.slot >= 36 && selected.slot <= 44 ? selected.slot - 36 : selected.slot === 45 ? 40 : selected.slot < 9 ? 44 - selected.slot : selected.slot;
      await action({ type: 'equip', slot, equipment: destinations[destination] });
    },
    async dig(block, forceLook = true, digFace = 'auto') {
      if (block == null) throw new Error('dig was called with an undefined or null block');
      if (forceLook !== true || digFace !== 'auto') throw new Error('dig look/face options are pending implementation');
      await action({ type: 'mine', position: block.position });
    },
    async waitForTicks(ticks) {
      if (!Number.isInteger(ticks) || ticks < 0) throw new Error('ticks must be a nonnegative integer');
      update(await request('waitTicks', { ticks }));
    },
    pathfinder: {
      async goto(goal) {
        if (!(goal instanceof goals.GoalBlock) && !(goal instanceof goals.GoalNear))
          throw new Error('This goal is pending native Pathfinder integration');
        if (goal.isEnd(bot.entity.position.floored())) return;
        const candidates = [];
        const range = Math.ceil(Math.sqrt(goal.rangeSq ?? 0));
        for (let x = goal.x - range; x <= goal.x + range; x++)
          for (let y = goal.y - range; y <= goal.y + range; y++)
            for (let z = goal.z - range; z <= goal.z + range; z++) {
              const p = new Vec3(x, y, z);
              if (!goal.isEnd(p)) continue;
              const feet = bot.blockAt(p), head = bot.blockAt(p.offset(0, 1, 0)), floor = bot.blockAt(p.offset(0, -1, 0));
              if (feet && head && floor && feet.shapes.length === 0 && head.shapes.length === 0 && floor.shapes.length > 0)
                candidates.push(p);
            }
        candidates.sort((a, b) => a.distanceSquared(bot.entity.position) - b.distanceSquared(bot.entity.position));
        if (!candidates.length) throw new Error('No loaded standing position satisfies the goal');
        await action({ type: 'walk', position: candidates[0].offset(0.5, 0, 0.5) });
        if (!goal.isEnd(bot.entity.position.floored())) throw new Error('Native navigation stopped before reaching the goal');
      },
    },
  };
  async function request(operation, value) { return JSON.parse(await __mcRequest(operation, JSON.stringify(value))); }
  async function action(args) { const result = await request('action', args); update(result.snapshot); }
  function update(next) {
    snapshot = next;
    bot.entity = { ...next.body, position: vector(next.body.position), yaw: (180 - next.body.yaw) * Math.PI / 180, pitch: -next.body.pitch * Math.PI / 180 };
    bot.game = { minY: next.minY, height: next.height, dimension: next.dimension, gameMode: next.hands.mode === 'survival' ? 'survival' : 'creative' };
    bot.inventory.slots.fill(null);
    for (const entry of next.hands.inventory) { const stack = item(entry); bot.inventory.slots[stack.slot] = stack; }
    bot.heldItem = bot.inventory.slots[36 + next.hands.selected];
  }
  update(initial);
  return { bot, Vec3, goals };
}
