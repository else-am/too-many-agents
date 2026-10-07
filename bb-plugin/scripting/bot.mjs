import { Vec3 } from 'vec3';
import goals from 'mineflayer-pathfinder/lib/goals.js';
import data from 'minecraft-version-data';
import { createBlockClass } from './blocks.mjs';
import { createItemClass } from './items.mjs';
import { decodeItemTransport } from 'minecraft-item-transport';
import { EventEmitter } from 'events';

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
    entitiesByName: Object.fromEntries(data.entitiesArray.map(entity => [entity.name, entity])),
  };
  const Block = createBlockClass(registry);
  const Item = createItemClass(registry);
  let snapshot;
  let lastPhysicsTick;
  let nextQuickBarSlot = 0;
  const stateWaits = new Set();
  const vector = ({ x, y, z }) => new Vec3(x, y, z);
  const item = entry => {
    if (!entry) return null;
    const stack = Item.fromNotch(decodeItemTransport(entry.item));
    if (stack && entry.slot !== undefined)
      stack.slot = entry.slot < 9 ? entry.slot + 36 : entry.slot === 40 ? 45 : entry.slot < 36 ? entry.slot : 44 - entry.slot;
    return stack;
  };
  const bot = Object.assign(new EventEmitter(), {
    registry, version: '1.21.1',
    // Component holder IDs belong to this world's registries. Item IDs alone
    // are translated to the pinned Mineflayer registry by the trusted decoder.
    nativeRegistries: initial.itemRegistries.references,
    entities: {},
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
    async equip(itemOrId, destination = 'hand') {
      if (!destination) destination = 'hand';
      const destinations = { hand: 'mainhand', 'off-hand': 'offhand', head: 'head', torso: 'chest', legs: 'legs', feet: 'feet' };
      if (!destinations[destination]) throw new Error(`Invalid destination ${destination}`);
      const selected = typeof itemOrId === 'number' ? bot.inventory.items().find(item => item.type === itemOrId) : itemOrId;
      if (!selected || !Number.isInteger(selected.slot)) throw new Error('Item is not in the inventory');
      const slot = selected.slot >= 36 && selected.slot <= 44 ? selected.slot - 36 : selected.slot === 45 ? 40 : selected.slot < 9 ? 44 - selected.slot : selected.slot;
      let hotbar;
      if (destination === 'hand') {
        if (selected.slot >= 36 && selected.slot <= 44) hotbar = selected.slot - 36;
        else {
          hotbar = bot.inventory.slots.slice(36, 45).findIndex(stack => stack === null);
          if (hotbar === -1) { hotbar = nextQuickBarSlot; nextQuickBarSlot = (nextQuickBarSlot + 1) % 9; }
        }
      }
      await action({ type: 'equip', slot, equipment: destinations[destination], hotbar });
    },
    async dig(block, forceLook = true, digFace = 'auto') {
      if (block == null) throw new Error('dig was called with an undefined or null block');
      if (forceLook !== true || digFace !== 'auto') throw new Error('dig look/face options are pending implementation');
      await action({ type: 'mine', position: block.position });
    },
    // Adapted from Mineflayer 4.39.0 physics.js; see mineflayer.LICENSE.
    async waitForTicks(ticks) {
      if (ticks <= 0) return;
      await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
          bot.removeListener('physicsTick', tickListener);
          reject(new Error(`Timeout waiting for ${ticks} ticks after ${(ticks * 50 + 5000)}ms`));
        }, ticks * 50 + 5000);
        const tickListener = () => {
          ticks--;
          if (ticks === 0) {
            clearTimeout(timeout);
            bot.removeListener('physicsTick', tickListener);
            resolve();
          }
        };
        bot.on('physicsTick', tickListener);
      });
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
  });
  async function request(operation, value) { return JSON.parse(await __mcRequest(operation, JSON.stringify(value))); }
  async function action(args) {
    const result = await request('action', args);
    if (snapshot.completedActionSequence < result.sequence)
      await new Promise(resolve => { stateWaits.add({ sequence: result.sequence, resolve }); });
    if (result.status !== 'completed') throw new Error(`Action ${result.id}: ${result.status}: ${result.detail ?? ''}`);
    return result;
  }
  function update(next, streamed = false) {
    if (snapshot && next.revision <= snapshot.revision) throw new Error('Minecraft state arrived out of order');
    const changed = [];
    const blocks = next.blocks;
    const trackBlocks = snapshot && bot.eventNames().some(name => typeof name === 'string' && name.startsWith('blockUpdate'));
    const oldBlock = (position, state) => {
      const { min, size, states } = snapshot.blocks;
      const x = position.x - min[0], y = position.y - min[1], z = position.z - min[2];
      if (x < 0 || y < 0 || z < 0 || x >= size[0] || y >= size[1] || z >= size[2]) return;
      const oldState = states[(y * size[2] + z) * size[0] + x];
      if (oldState >= 0 && state >= 0 && oldState !== state) changed.push([bot.blockAt(position), position]);
    };
    if (streamed && blocks.changes) {
      if (!snapshot) throw new Error('State stream began with a block delta');
      const cached = snapshot.blocks;
      const { min, size } = cached;
      for (let n = 0; n < blocks.changes.length; n += 4) {
        const [i, state, biome, light] = blocks.changes.slice(n, n + 4);
        if (trackBlocks) oldBlock(new Vec3(min[0] + i % size[0], min[1] + Math.floor(i / (size[0] * size[2])), min[2] + Math.floor(i / size[0]) % size[2]), state);
        cached.states[i] = state;
        cached.biomes[i] = biome;
        cached.light[i] = light;
      }
      cached.entities = blocks.entities;
      next.blocks = cached;
    } else {
      if (trackBlocks) {
        const { min, size, states } = blocks;
        for (let i = 0; i < states.length; i++) {
          const position = new Vec3(min[0] + i % size[0], min[1] + Math.floor(i / (size[0] * size[2])), min[2] + Math.floor(i / size[0]) % size[2]);
          oldBlock(position, states[i]);
        }
      }
    }
    snapshot = next;
    const present = new Set();
    const entityEvents = [];
    for (const source of [next.body, ...next.entities]) {
      present.add(source.id);
      let entity = bot.entities[source.id];
      const fresh = !entity || entity.uuid !== source.uuid;
      if (fresh) entity = bot.entities[source.id] = Object.assign(new EventEmitter(), { position: new Vec3(0, 0, 0), velocity: new Vec3(0, 0, 0) });
      const moved = !entity.position.equals(source.position);
      const { position, velocity, yaw, pitch, type, name, ...fields } = source;
      const kind = registry.entitiesByName[type.replace(/^minecraft:/, '')];
      Object.assign(entity, fields, { yaw: (180 - yaw) * Math.PI / 180, pitch: -pitch * Math.PI / 180,
        name: kind?.name ?? 'unknown', displayName: kind?.displayName ?? name, type: kind?.type ?? 'other', isValid: true });
      if (kind?.name === 'player') entity.username = name;
      entity.position.update(position);
      entity.velocity.update(velocity);
      if (source.id === next.body.id) bot.entity = entity;
      else if (fresh) entityEvents.push(['entitySpawn', entity]);
      else if (moved) entityEvents.push(['entityMoved', entity]);
    }
    for (const [id, entity] of Object.entries(bot.entities)) {
      if (!present.has(Number(id))) {
        entity.isValid = false;
        delete bot.entities[id];
        entityEvents.push(['entityGone', entity]);
      }
    }
    bot.game = { minY: next.minY, height: next.height, dimension: next.dimension, gameMode: next.hands.mode === 'survival' ? 'survival' : 'creative' };
    bot.inventory.slots.fill(null);
    for (const entry of next.hands.inventory) { const stack = item(entry); bot.inventory.slots[stack.slot] = stack; }
    bot.heldItem = bot.inventory.slots[36 + next.hands.selected];
    bot.quickBarSlot = next.hands.selected;
    for (const [before, position] of changed) {
      const after = bot.blockAt(position);
      bot.emit('blockUpdate', before, after);
      bot.emit(`blockUpdate:${position}`, before, after);
    }
    if (streamed) {
      for (const event of entityEvents) bot.emit(...event);
      if (next.tick !== lastPhysicsTick) { lastPhysicsTick = next.tick; bot.emit('physicsTick'); }
    }
    for (const wait of stateWaits) {
      if (next.completedActionSequence >= wait.sequence) { stateWaits.delete(wait); wait.resolve(); }
    }
  }
  update(initial);
  lastPhysicsTick = initial.tick;
  return { bot, Vec3, goals, update };
}
