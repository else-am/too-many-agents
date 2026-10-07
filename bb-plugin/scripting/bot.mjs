import { installScoreboards } from './scoreboard.mjs';
import { installExplosion } from './explosion.mjs';
import { Vec3 } from 'vec3';
import upstreamGoals from 'mineflayer-pathfinder/lib/goals.js';
import data from 'minecraft-version-data';
import { createBlockClass } from './blocks.mjs';
import { createItemClass } from './items.mjs';
import { createWindowFactory } from './windows.mjs';
import { createWorldView } from './world-view.mjs';
import { decodeItemTransport } from 'minecraft-item-transport';
import { EventEmitter } from 'events';
import { Movements } from './movements.mjs';
import { installPathfinder } from './pathfinder.mjs';
import { createRecipeFactory, installRecipeQueries } from './recipes.mjs';
import { createChatMessageClass } from './chat.mjs';
import { createEntityClass } from './entities.mjs';
import { installInventory } from './inventory.mjs';
import { installWorldQueries } from './world-queries.mjs';
import { installState } from './state.mjs';
import { installActions } from './actions.mjs';
import { installEntityQueries } from './entity-queries.mjs';
import { installChatPatterns } from './chat-patterns.mjs';

const goals = { ...upstreamGoals,
  GoalBreakBlock: class GoalBreakBlock extends upstreamGoals.GoalBreakBlock {
    constructor(x, y, z, bot, options) { super(x, y, z, bot.world ?? bot, options); }
    // Pinned upstream drops the required node argument here.
    isEnd(node) { return this.goal.isEnd(node); }
  },
};

// This first slice is intentionally not marked conformant in coverage.json.
// Coverage expands through shared scripts and reference comparisons.
export function createBot(initial) {
  const registry = {
    ...data,
    chatFormattingById: initial.chatFormattingById ?? {},
    blocksArray: data.blocksArray, itemsArray: data.itemsArray,
    blocksByName: Object.fromEntries(data.blocksArray.map(block => [block.name, block])),
    itemsByName: Object.fromEntries(data.itemsArray.map(item => [item.name, item])),
    blocks: Object.fromEntries(data.blocksArray.map(block => [block.id, block])),
    items: Object.fromEntries(data.itemsArray.map(item => [item.id, item])),
    entitiesByName: Object.fromEntries(data.entitiesArray.map(entity => [entity.name, entity])),
  };
  const Block = createBlockClass(registry);
  const Item = createItemClass(registry);
  const ChatMessage = createChatMessageClass(registry);
  const Entity = createEntityClass(registry, { Item, ChatMessage });
  const recipeFactory = createRecipeFactory(registry);
  const { createWindow } = createWindowFactory(Item);
  const windowKeys = new WeakMap();
  const equipmentKeys = new WeakMap();
  const effectTicks = new WeakMap();
  const playerKeys = new Map();
  let snapshot;
  let lastPhysicsTick;
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
    players: Object.create(null), uuidToUsername: Object.create(null),
    inventory: createWindow(0, 'minecraft:inventory', 'Inventory'),
    currentWindow: null,
    QUICK_BAR_START: 36,
    quickBarSlot: null,
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
  });
  installEntityQueries(bot);
  bot._playerFromUUID = uuid => Object.values(bot.players).find(player => player.uuid === uuid);
  const updateState = installState(bot, data.featureTable);
  const scoreboards = installScoreboards(bot, ChatMessage);
  Object.defineProperty(bot, 'heldItem', { get: () => bot.inventory.slots[36 + bot.quickBarSlot] });
  bot.world = createWorldView(position => bot.blockAt(position));
  installWorldQueries(bot, { getLoadedBounds() {
    const { min, size } = snapshot.blocks;
    return { min: new Vec3(...min), max: new Vec3(...min.map((value, axis) => value + size[axis])) };
  } });
  class NativeActionError extends Error {}
  async function request(operation, value) {
    try { return JSON.parse(await __mcRequest(operation, JSON.stringify(value))); }
    catch (failure) {
      if (failure.code === 'minecraft_action_rejected_before_start')
        throw Object.assign(new NativeActionError(failure.message), { phase: 'before_start', detail: failure.message });
      throw failure;
    }
  }
  async function action(args) {
    const result = await request('action', args);
    await waitForActionState(result);
    if (result.status !== 'completed') throw Object.assign(
      new NativeActionError(`Action ${result.id}: ${result.status}: ${result.detail ?? ''}`),
      { phase: 'terminal', status: result.status, detail: result.detail });
    return result;
  }
  async function waitForActionState(result) {
    if (snapshot.completedActionSequence < result.sequence)
      await new Promise(resolve => { stateWaits.add({ sequence: result.sequence, resolve }); });
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
    bot.nativeBody = next.nativeBody;
    const present = new Set();
    const entityEvents = [];
    // Event-only entities can spawn and be collected between observations.
    // Current observations win over the earlier event-time record for live entities.
    const observed = new Map();
    for (const event of next.entityEvents ?? []) for (const source of event.entities) observed.set(source.id, source);
    for (const source of [next.body, ...next.entities]) observed.set(source.id, source);
    const sources = [...observed.values()];
    for (const source of sources) {
      present.add(source.id);
      let entity = bot.entities[source.id];
      const fresh = !entity || entity.uuid !== source.uuid;
      if (fresh) entity = bot.entities[source.id] = new Entity(source.id);
      const moved = !entity.position.equals(source.position)
        || entity.yaw !== (180 - source.yaw) * Math.PI / 180 || entity.pitch !== -source.pitch * Math.PI / 180;
      const oldPosition = source.id === next.body.id && !fresh && moved ? entity.position.clone() : null;
      const slept = !!entity.isSleeping;
      const crouched = !!entity.crouching;
      const effectEvents = [];
      const elapsed = source.effectTick - effectTicks.get(entity);
      effectTicks.set(entity, source.effectTick);
      for (const [id, effect] of Object.entries(source.effects ?? {})) {
        const previous = entity.effects[id];
        const expectedDuration = previous?.duration === -1 ? -1 : previous?.duration - elapsed;
        const changed = !previous || previous.amplifier !== effect.amplifier || expectedDuration !== effect.duration;
        if (changed) {
          entity.effects[id] = { ...effect };
          effectEvents.push(['entityEffect', entity, entity.effects[id]]);
        } else previous.duration = effect.duration;
      }
      for (const id of Object.keys(entity.effects)) {
        if (!Object.hasOwn(source.effects ?? {}, id)) {
          const previous = entity.effects[id];
          delete entity.effects[id];
          effectEvents.push(['entityEffectEnd', entity, previous]);
        }
      }
      const attributesChanged = !fresh && JSON.stringify(entity.attributes) !== JSON.stringify(source.attributes);
      let equipmentChanged = false;
      const { position, velocity, yaw, pitch, type, name, customName, droppedItem, equipment, passengers, vehicle, effects, effectTick, ...fields } = source;
      const kind = registry.entitiesByName[type.replace(/^minecraft:/, '')];
      Object.assign(entity, fields, { yaw: (180 - yaw) * Math.PI / 180, pitch: -pitch * Math.PI / 180,
        name: kind?.name ?? 'unknown', displayName: kind?.displayName ?? name, type: kind?.type ?? 'other',
        entityType: kind?.id, kind: kind?.category, isValid: true });
      if (kind?.name === 'player') entity.username = name;
      entity.position.update(position);
      entity.velocity.update(velocity);
      entity.metadata[2] = customName ?? undefined;
      if (droppedItem) entity.metadata[8] = decodeItemTransport(droppedItem.item);
      if (equipment) {
        let keys = equipmentKeys.get(entity);
        if (!keys) { keys = []; equipmentKeys.set(entity, keys); }
        for (let slot = 0; slot < equipment.length; slot++) {
          if (keys[slot] !== equipment[slot].itemKey) {
            equipmentChanged = true;
            keys[slot] = equipment[slot].itemKey;
            entity.setEquipment(slot, item(equipment[slot]));
          }
        }
      }
      if (source.id === next.body.id) {
        bot.entity = entity;
        if (oldPosition) entityEvents.push(['move', oldPosition]);
      }
      else if (fresh) entityEvents.push(['entitySpawn', entity]);
      else if (moved) entityEvents.push(['entityMoved', entity]);
      entityEvents.push(...effectEvents);
      if ((!fresh && crouched !== !!entity.crouching) || fresh && entity.crouching)
        entityEvents.push([entity.crouching ? 'entityCrouch' : 'entityUncrouch', entity]);
      if (!fresh && slept !== !!entity.isSleeping) {
        entityEvents.push([entity.isSleeping ? 'entitySleep' : 'entityWake', entity]);
        if (source.id === next.body.id) entityEvents.push([entity.isSleeping ? 'sleep' : 'wake']);
      }
      if (attributesChanged) entityEvents.push(['entityAttributes', entity]);
      if (!fresh && equipmentChanged) entityEvents.push(['entityEquip', entity]);
    }
    for (const [id, entity] of Object.entries(bot.entities)) {
      if (!present.has(Number(id))) {
        entity.isValid = false;
        delete bot.entities[id];
        entityEvents.push(['entityGone', entity]);
      }
    }
    for (const source of sources) {
      const entity = bot.entities[source.id];
      // Missing observations stay undefined, distinct from no vehicle. Do not
      // invent entities or erase passenger positions at the cache boundary.
      entity.passengers = (source.passengers ?? []).map(id => bot.entities[id]);
      entity.vehicle = source.vehicle == null ? null : bot.entities[source.vehicle];
    }
    bot.isSleeping = !!bot.entity.isSleeping;
    const previousVehicle = bot.vehicle;
    bot.vehicle = bot.entity.vehicle;
    if (streamed && previousVehicle !== bot.vehicle) {
      if (bot.vehicle) entityEvents.push(['mount']);
      else if (bot.vehicle === null && previousVehicle) entityEvents.push(['dismount', previousVehicle]);
    }
    const presentPlayers = new Set();
    for (const source of next.players ?? []) {
      presentPlayers.add(source.username);
      const key = JSON.stringify(source);
      let player = bot.players[source.username];
      const fresh = !player || player.uuid !== source.uuid;
      if (fresh) {
        if (player) { delete bot.uuidToUsername[player.uuid]; playerKeys.delete(player.uuid); }
        player = bot.players[source.username] = {};
      }
      const changed = playerKeys.get(source.uuid) !== key;
      if (changed || fresh) {
        Object.assign(player, source, { displayName: new ChatMessage(source.displayName), skinData: source.skinData });
        playerKeys.set(source.uuid, key);
      }
      player.entity = Object.values(bot.entities).find(entity => entity.uuid === source.uuid) ?? null;
      bot.uuidToUsername[source.uuid] = source.username;
      if (streamed && (fresh || changed)) entityEvents.push([fresh ? 'playerJoined' : 'playerUpdated', player]);
    }
    for (const [username, player] of Object.entries(bot.players)) if (!presentPlayers.has(username)) {
      delete bot.players[username]; delete bot.uuidToUsername[player.uuid]; playerKeys.delete(player.uuid);
      if (streamed) entityEvents.push(['playerLeft', player]);
    }
    const game = { minY: next.minY, height: next.height, dimension: next.dimension.replace(/^minecraft:/, ''),
      gameMode: next.hands.mode === 'survival' ? 'survival' : 'creative' };
    for (const key of ['difficulty', 'hardcore', 'levelType', 'maxPlayers', 'serverViewDistance'])
      if (next.worldState?.[key] !== undefined) game[key] = next.worldState[key];
    const gameChanged = bot.game && Object.keys(game).some(key => bot.game[key] !== game[key]);
    Object.assign(bot.game ??= {}, game);
    if (streamed && gameChanged) entityEvents.push(['game']);
    let experienceChanged = false;
    if (next.hands.experience) {
      experienceChanged = !!bot.experience && (bot.experience.level !== next.hands.experience.level
        || bot.experience.progress !== next.hands.experience.progress || bot.experience.points !== next.hands.experience.total);
      bot.experience ??= {};
      Object.assign(bot.experience, { level: next.hands.experience.level,
        progress: next.hands.experience.progress, points: next.hands.experience.total });
    }
    const heldBefore = bot.heldItem;
    bot.quickBarSlot = inventory.selection() ?? next.hands.selected;
    const menu = next.hands.menu;
    const inventorySlots = Array.from({ length: 46 }, () => null);
    for (const entry of next.hands.inventory) {
      const slot = entry.slot < 9 ? entry.slot + 36 : entry.slot === 40 ? 45 : entry.slot < 36 ? entry.slot : 44 - entry.slot;
      inventorySlots[slot] = entry;
    }
    if (menu.type === 'minecraft:inventory') {
      for (const entry of menu.slots) inventorySlots[entry.slot] = entry;
    }
    const windowEvents = hydrateWindow(bot.inventory, inventorySlots, menu.carried, menu.generation);
    let opened;
    const previousWindow = bot.currentWindow;
    if (menu.type === 'minecraft:inventory') bot.currentWindow = null;
    else {
      if (!previousWindow || previousWindow.id !== menu.id || previousWindow.type !== menu.type
          || windowKeys.get(previousWindow)?.generation !== menu.generation) {
        opened = createWindow(menu.id, menu.type, menu.titleNbt ?? menu.title ?? '', menu.slots.length - 36);
        if (!opened) throw new Error(`Unknown native window type ${menu.type}`);
        bot.currentWindow = opened;
      }
      windowEvents.push(...hydrateWindow(bot.currentWindow, menu.slots, menu.carried, menu.generation));
    }
    bot.entity.equipment = [bot.heldItem, bot.inventory.slots[45], bot.inventory.slots[8],
      bot.inventory.slots[7], bot.inventory.slots[6], bot.inventory.slots[5]];
    bot.usingHeldItem = next.hands.usingItem;
    const stateEvents = updateState(next);
    const scoreEvents = scoreboards.update(next.scoreboard, streamed);
    inventory.syncWindow(bot.currentWindow ?? bot.inventory, menu);
    // A native frame changes all slots/cursor together. Listeners must see the
    // complete inventory and container state, including held equipment.
    for (const [window, slot, before, after] of windowEvents) {
      window.emit('updateSlot', slot, before, after);
      window.emit(`updateSlot:${slot}`, before, after);
    }
    if (previousWindow && previousWindow !== bot.currentWindow) bot.emit('windowClose', previousWindow);
    if (opened) bot.emit('windowOpen', opened);
    if (heldBefore !== bot.heldItem) bot.emit('heldItemChanged', bot.heldItem);
    if (experienceChanged) bot.emit('experience');
    for (const event of stateEvents) bot.emit(...event);
    for (const event of scoreEvents) bot.emit(...event);
    for (const [before, position] of changed) {
      const after = bot.blockAt(position);
      bot.emit('blockUpdate', before, after);
      bot.emit(`blockUpdate:${position}`, before, after);
    }
    if (streamed) {
      for (const event of entityEvents) bot.emit(...event);
      if (next.tick !== lastPhysicsTick) { lastPhysicsTick = next.tick; bot.emit('physicsTick'); }
    }
    for (const event of next.entityEvents ?? []) {
      const subject = bot.entities[event.subject], cause = event.cause == null ? undefined : bot.entities[event.cause];
      bot.emit(event.name, subject, cause);
    }
    for (const sound of next.sounds ?? []) {
      bot.emit('soundEffectHeard', sound.name, vector(sound.position), sound.volume, sound.pitch);
    }
    for (const entry of next.messages ?? []) {
      const message = new ChatMessage(entry.message);
      const sender = entry.sender ?? null;
      bot.emit('message', message, entry.position, sender, entry.verified);
      bot.emit('messagestr', message.toString(), entry.position, message, sender, entry.verified);
      if (entry.position === 'game_info') bot.emit('actionBar', message, sender);
    }
    for (const wait of stateWaits) {
      if (next.completedActionSequence >= wait.sequence) { stateWaits.delete(wait); wait.resolve(); }
    }
  }
  function hydrateWindow(window, entries, carried, generation) {
    const events = [];
    let keys = windowKeys.get(window);
    if (!keys) { keys = []; windowKeys.set(window, keys); }
    keys.generation = generation;
    for (let slot = 0; slot < window.slots.length; slot++) {
      const entry = entries[slot];
      const key = entry && entry.count > 0 ? entry.itemKey : null;
      if (keys[slot] !== key) {
        keys[slot] = key;
        const before = window.slots[slot], after = entry ? item(entry) : null;
        if (after) after.slot = slot;
        window.slots[slot] = after;
        events.push([window, slot, before, after]);
      }
    }
    if (keys.carried !== carried.itemKey) {
      keys.carried = carried.itemKey;
      window.selectedItem = item(carried);
    }
    return events;
  }
  const inventory = installInventory(bot, { action, Item, snapshot: () => snapshot, decodeItem: item,
    isKnownActionError: error => error instanceof NativeActionError,
    assertActive() {
      if (snapshot.session !== initial.session || snapshot.body.uuid !== initial.body.uuid || !bot.entity.isValid)
        throw new Error('Script body or world session changed');
    },
  });
  installChatPatterns(bot);
  update(initial);
  const actions = installActions(bot, { request, action, waitForActionState, snapshot: () => snapshot,
    enqueueControl: inventory.enqueueControl,
    drainControls: inventory.drainControls, isKnownActionError: error => error instanceof NativeActionError });
  async function drainControls() { await inventory.drainControls(); await actions.drainControls(); }
  lastPhysicsTick = initial.tick;
  installPathfinder(bot, {
    async request(operation, value) {
      if (operation === 'action' || operation === 'startAction') await drainControls();
      return request(operation, value);
    },
    waitForActionState, snapshot: () => snapshot,
  });
  installRecipeQueries(bot, recipeFactory);
  installExplosion(bot);
  return { bot, Vec3, goals, Movements, Block, Item, Entity, ChatMessage,
    MessageBuilder: ChatMessage.MessageBuilder, ...recipeFactory, update, drainControls };
}
