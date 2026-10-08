import { createParticleClass } from './particle.mjs';
import { createChunkClass } from './chunks.mjs';
import { createColumnWorld, installColumns } from './columns.mjs';
import { installBossBars } from './boss-bars.mjs';
import { installScoreboards } from './scoreboard.mjs';
import { installExplosion } from './explosion.mjs';
import { Vec3 } from 'vec3';
import data from 'minecraft-version-data';
import { createBlockClass } from './blocks.mjs';
import { createItemClass } from './items.mjs';
import { createWindowFactory } from './windows.mjs';
import { createWorldView } from './world-view.mjs';
import { decodeItemTransport } from 'minecraft-item-transport';
import { EventEmitter } from 'events';
import { createRecipeFactory, installRecipeQueries } from './recipes.mjs';
import { createChatMessageClass } from './chat.mjs';
import { createEntityClass } from './entities.mjs';
import { installInventory } from './inventory.mjs';
import { installWorldQueries } from './world-queries.mjs';
import { installState } from './state.mjs';
import { installActions } from './actions.mjs';
import { installEntityQueries } from './entity-queries.mjs';
import { installChatPatterns } from './chat-patterns.mjs';
import { installBlockEvents } from './block-events.mjs';
import { createRegistry } from './registry.mjs';

// Only native observation fields may be copied onto a shared Entity instance.
const entityFields = ['uuid', 'width', 'height', 'onGround', 'eyeHeight', 'eyePosition', 'direction', 'alive',
  'isInWater', 'isInLava', 'crouching', 'fireworkAttachedTo', 'fireworkTicksRemaining', 'elytraFlying',
  'health', 'mainHand', 'isSleeping', 'airSupply', 'maxAirSupply', 'attributes', 'agent'];
const metadataKeys = new WeakMap();

// Keep sparse initial values and actual resets without inventing native defaults.
// Exported only for the focused transport/QuickJS contract probe.
export function mergeEntityMetadata(entity, frames) {
  if (!Array.isArray(frames) || frames.length > 1024) throw new Error('Invalid metadata observations');
  let seen = metadataKeys.get(entity);
  if (!seen) { seen = new Set(); metadataKeys.set(entity, seen); }
  let changed = false;
  for (const frame of frames) {
    if (!frame || !Array.isArray(frame.nonDefaultKeys)) throw new Error('Missing entity metadata');
    const entries = decodeItemTransport(frame.values);
    if (!Array.isArray(entries) || entries.length > 255) throw new Error('Invalid entity metadata entries');
    const keys = new Set();
    for (const entry of entries) {
      if (!entry || !Number.isInteger(entry.key) || entry.key < 0 || entry.key >= 255 || keys.has(entry.key))
        throw new Error('Invalid entity metadata key');
      keys.add(entry.key);
    }
    const nonDefaults = new Set(frame.nonDefaultKeys);
    if (nonDefaults.size !== frame.nonDefaultKeys.length || nonDefaults.size > keys.size
      || frame.nonDefaultKeys.some(key => !Number.isInteger(key) || !keys.has(key))) throw new Error('Invalid non-default metadata keys');
    for (const { key, value } of entries) {
      if (!nonDefaults.has(key) && !seen.has(key)) continue;
      // Protocol values are trees; tagged transport preserves long/NaN/-0 values
      // but JSON comparison alone would not. Compare those leaves explicitly.
      if (!seen.has(key) || !sameMetadata(entity.metadata[key], value)) changed = true;
      entity.metadata[key] = value;
      seen.add(key);
    }
  }
  return changed;
}
function sameMetadata(a, b) {
  if (Object.is(a, b)) return true;
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object' || Array.isArray(a) !== Array.isArray(b)) return false;
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every(key => Object.hasOwn(b, key) && sameMetadata(a[key], b[key]));
}

// This first slice is intentionally not marked conformant in coverage.json.
// Coverage expands through shared scripts and reference comparisons.
export function createBot(initial) {
  if (!initial.registryCodecs) throw new Error('Native registry codecs are missing');
  const registry = createRegistry(data, initial.registryCodecs);
  const biomeNames = initial.itemRegistries.references['minecraft:worldgen/biome'];
  if (!Array.isArray(biomeNames) || biomeNames.length !== registry.biomesArray.length
    || biomeNames.some((name, id) => registry.biomesArray[id].name !== name.replace(/^minecraft:/, '')))
    throw new Error('Native biome codec and item registry IDs differ');
  const Block = createBlockClass(registry);
  const ChunkColumn = createChunkClass(registry, Block);
  const Particle = createParticleClass(registry);
  const Item = createItemClass(registry);
  const ChatMessage = createChatMessageClass(registry);
  const Entity = createEntityClass(registry, { Item, ChatMessage });
  const recipeFactory = createRecipeFactory(registry);
  const { createWindow } = createWindowFactory(Item);
  const windowKeys = new WeakMap();
  const equipmentKeys = new WeakMap();
  const effectTicks = new WeakMap();
  const vehicleLinks = new WeakMap();
  const playerKeys = new Map();
  const knownFireworks = new Set();
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
    registry, version: '1.21.1', protocolVersion: data.protocolVersion, majorVersion: data.majorVersion,
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
      if (x < 0 || y < 0 || z < 0 || x >= size[0] || y >= size[1] || z >= size[2]) return columns.getBlock(p, extraInfos);
      const index = (y * size[2] + z) * size[0] + x;
      const stateId = states[index];
      if (stateId === -1) return null;
      const complete = columns.getBlock(p, extraInfos);
      if (complete) return complete;
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
      // Count ticks after earlier controls reached native state, not queued frames
      // from before a synchronous setControlState/activateItem call.
      await drainControls();
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
  // A stable view of observed Mob state; assignments cannot fake native settings.
  Object.defineProperty(bot, 'settings', { enumerable: true, value: Object.freeze({
    get mainHand() { return snapshot?.body.mainHand; },
  }) });
  installEntityQueries(bot);
  bot._playerFromUUID = uuid => Object.values(bot.players).find(player => player.uuid === uuid);
  const updateState = installState(bot, data.featureTable);
  registry.supportFeature = bot.supportFeature;
  // This bot is already initialized; script-local plugins run immediately in
  // the same bounded guest realm. No package or host module loading is implied.
  const loadedPlugins = new Set(), pluginOptions = { version: bot.version };
  bot.hasPlugin = plugin => loadedPlugins.has(plugin);
  bot.loadPlugin = plugin => {
    if (typeof plugin !== 'function') throw new TypeError('plugin needs to be a function');
    if (loadedPlugins.has(plugin)) return;
    loadedPlugins.add(plugin);
    plugin(bot, pluginOptions);
  };
  bot.loadPlugins = plugins => {
    if (!Array.isArray(plugins) || plugins.filter(plugin => typeof plugin === 'function').length !== plugins.length)
      throw new TypeError('plugins need to be an array of functions');
    plugins.forEach(bot.loadPlugin);
  };
  const scoreboards = installScoreboards(bot, ChatMessage);
  const bossBars = installBossBars(bot, ChatMessage);
  Object.defineProperty(bot, 'heldItem', { get: () => bot.inventory.slots[36 + bot.quickBarSlot] });
  // World reads share its column cache, including explicit guest unloads.
  bot.world = createWorldView(position => columns.getBlock(position), createColumnWorld());
  const columns = installColumns(bot, ChunkColumn);
  for (const event of ['blockUpdate', 'chunkColumnLoad', 'chunkColumnUnload'])
    bot.world.on(event, (...args) => bot.emit(event, ...args));
  const blockEvents = installBlockEvents(bot);
  const coordinateEvent = event => typeof event === 'string' && /^blockUpdate:\(-?\d+, -?\d+, -?\d+\)$/.test(event);
  bot.on('newListener', (event, listener) => { if (coordinateEvent(event)) bot.world.on(event, listener); });
  bot.on('removeListener', (event, listener) => { if (coordinateEvent(event)) bot.world.off(event, listener); });
  installWorldQueries(bot, { getLoadedBounds() {
    const full = columns.bounds();
    if (full) return full;
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
  function changesFromColumns(incoming, changed) {
    const seen = new Set(changed.map(([, p]) => p.toString()));
    for (const entry of incoming) if (!seen.has(entry[1].toString())) changed.push(entry);
  }
  const tablistKeys = {};
  bot.tablist = { header: new ChatMessage(''), footer: new ChatMessage('') };
  function update(next, streamed = false) {
    if (!['left', 'right'].includes(next.body.mainHand)) throw new Error('Missing native body mainHand');
    if (snapshot && next.revision <= snapshot.revision) throw new Error('Minecraft state arrived out of order');
    for (const key of ['header', 'footer']) {
      const value = next.hands.tablist?.[key] ?? '';
      const encoded = JSON.stringify(value);
      if (tablistKeys[key] !== encoded) {
        tablistKeys[key] = encoded;
        bot.tablist[key] = new ChatMessage(value);
      }
    }
    const changed = [];
    const blocks = next.blocks;
    const blockEntityPositions = new Map();
    const trackBlockEntities = streamed && snapshot && bot.listenerCount('blockEntityData') > 0;
    if (trackBlockEntities) {
      const { min, size } = blocks.min ? blocks : snapshot.blocks;
      for (const [key, tag] of Object.entries(blocks.entities)) {
        const index = Number(key);
        const position = new Vec3(min[0] + index % size[0], min[1] + Math.floor(index / (size[0] * size[2])), min[2] + Math.floor(index / size[0]) % size[2]);
        const before = bot.blockAt(position);
        if (before && JSON.stringify(before.entity ?? null) !== JSON.stringify(tag))
          blockEntityPositions.set(position.toString(), position);
      }
    }
    const editor = next.hands.signEditor;
    const openedSign = streamed && editor && editor.sequence !== snapshot?.hands.signEditor?.sequence;
    const trackBlocks = snapshot && [bot, bot.world, bot.world.async].some(emitter => emitter.eventNames()
      .some(name => typeof name === 'string' && name.startsWith('blockUpdate')));
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
    const columnUpdate = columns.update(next.columnView, streamed, trackBlocks, next.blocks, trackBlockEntities);
    for (const position of columnUpdate.blockEntities) blockEntityPositions.set(position.toString(), position);
    changesFromColumns(columnUpdate.changes, changed);
    columns.patchLocal(next.blocks);
    snapshot = next;
    const present = new Set();
    const entityEvents = [];
    // Event-only entities can spawn and be collected between observations.
    // Current observations win over the earlier event-time record for live entities.
    const observed = new Map(), metadataObservations = new Map();
    const observe = source => {
      observed.set(source.id, source);
      const frames = metadataObservations.get(source.uuid) ?? [];
      frames.push(source.rawMetadata); metadataObservations.set(source.uuid, frames);
    };
    for (const event of next.entityEvents ?? []) for (const source of event.entities) observe(source);
    for (const source of [next.body, ...next.entities]) observe(source);
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
      const flew = !!entity.elytraFlying;
      const crouched = !!entity.crouching;
      const droppedBefore = entity.metadata[8];
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
      const { position, velocity, yaw, pitch, type, name, equipment } = source;
      const kind = registry.entitiesByName[type.replace(/^minecraft:/, '')];
      for (const key of entityFields) if (Object.hasOwn(source, key)) entity[key] = source[key];
      Object.assign(entity, { yaw: (180 - yaw) * Math.PI / 180, pitch: -pitch * Math.PI / 180,
        name: kind?.name ?? 'unknown', displayName: kind?.displayName ?? name, type: Object.hasOwn(source, 'experienceValue') ? 'orb' : kind?.type ?? 'other',
        entityType: kind?.id, kind: kind?.category, isValid: true });
      if (Object.hasOwn(source, 'experienceValue')) entity.count = source.experienceValue;
      if (kind?.name === 'player') entity.username = name;
      entity.position.update(position);
      entity.velocity.update(velocity);
      const metadataChanged = mergeEntityMetadata(entity, metadataObservations.get(source.uuid));
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
        bot.username = name;
        if (oldPosition) entityEvents.push(['move', oldPosition]);
      }
      else if (fresh) entityEvents.push(['entitySpawn', entity]);
      else if (moved) entityEvents.push(['entityMoved', entity]);
      if (metadataChanged) entityEvents.push(['entityUpdate', entity]);
      if (entity.name === 'item' && entity.metadata[8]?.itemCount > 0
        && !sameMetadata(droppedBefore, entity.metadata[8])) entityEvents.push(['itemDrop', entity]);
      entityEvents.push(...effectEvents);
      if (!flew && entity.elytraFlying) entityEvents.push(['entityElytraFlew', entity]);
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
      const vehicleId = source.vehicle ?? null;
      const previous = vehicleLinks.get(entity);
      const sameVehicle = previous?.id === vehicleId;
      // Keep a known relationship across cache boundaries, without inventing
      // a dismount when the native vehicle still exists outside observation.
      const vehicle = entity.vehicle ?? (sameVehicle ? previous.entity : null);
      if (previous?.entity && (!sameVehicle || vehicle !== previous.entity))
        entityEvents.push(['entityDetach', entity, previous.entity]);
      if (entity.vehicle && (!sameVehicle || entity.vehicle !== previous?.entity))
        entityEvents.push(['entityAttach', entity, entity.vehicle]);
      vehicleLinks.set(entity, { id: vehicleId, entity: vehicle });
    }
    bot.isSleeping = !!bot.entity.isSleeping;
    bot.fireworkRocketDuration = 0;
    if (!bot.entity.elytraFlying) knownFireworks.clear();
    else for (const source of sources) {
      if (source.fireworkAttachedTo !== next.body.id || !(source.fireworkTicksRemaining > 0)) continue;
      bot.fireworkRocketDuration = Math.max(bot.fireworkRocketDuration, source.fireworkTicksRemaining);
      if (!knownFireworks.has(source.uuid)) entityEvents.push(['usedFirework', source.id]);
      knownFireworks.add(source.uuid);
    }
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
    const spawn = next.worldState?.spawnPoint;
    const spawnChanged = spawn && (!bot.spawnPoint || !bot.spawnPoint.equals(vector(spawn)));
    if (spawn) {
      if (bot.spawnPoint) bot.spawnPoint.set(spawn.x, spawn.y, spawn.z);
      else bot.spawnPoint = vector(spawn);
    }
    const gameChanged = bot.game && (Object.keys(game).some(key => bot.game[key] !== game[key]) || spawnChanged);
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
    const bossEvents = bossBars.update(next.bossBars, streamed);
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
    for (const event of bossEvents) bot.emit(...event);
    for (const event of columnUpdate.events) { bot.world.async.emit(...event); bot.world.emit(...event); }
    for (const [before, position] of changed) {
      const after = bot.blockAt(position);
      bot.world.async.emit('blockUpdate', before, after);
      bot.world.async.emit(`blockUpdate:${position}`, before, after);
      bot.world.emit('blockUpdate', before, after);
      bot.world.emit(`blockUpdate:${position}`, before, after);
    }
    for (const position of blockEntityPositions.values()) bot.emit('blockEntityData', bot.blockAt(position));
    for (const event of blockEvents.update(next.blockEvents)) bot.emit(...event);
    if (openedSign) bot.emit('signOpen', bot.blockAt(new Vec3(editor.position.x, editor.position.y, editor.position.z)));
    if (streamed) {
      for (const event of entityEvents) bot.emit(...event);
      if (next.tick !== lastPhysicsTick) {
        lastPhysicsTick = next.tick;
        bot.emit('physicsTick');
        bot.emit('physicTick');
      }
    }
    for (const event of next.entityEvents ?? []) {
      if (event.name === 'forcedMove') { bot.emit('forcedMove'); continue; }
      const subject = bot.entities[event.subject], cause = event.cause == null ? undefined : bot.entities[event.cause];
      bot.emit(event.name, subject, cause);
    }
    for (const sound of next.sounds ?? []) {
      bot.emit('soundEffectHeard', sound.name, vector(sound.position), sound.volume, sound.pitch);
    }
    for (const particle of next.particles ?? []) {
      const type = registry.particlesByName[particle.name];
      if (!type) throw new Error(`Unknown native particle type: ${particle.name}`);
      bot.emit('particle', Particle.fromNetwork({ ...particle, particle: { type: type.id } }));
    }
    for (const entry of next.messages ?? []) {
      if (entry.kind === 'title') {
        bot.emit('title', new ChatMessage(entry.message).toString(), entry.type);
        continue;
      }
      if (entry.kind === 'title_times') {
        bot.emit('title_times', entry.fadeIn, entry.stay, entry.fadeOut);
        continue;
      }
      if (entry.kind === 'title_clear') { bot.emit('title_clear'); continue; }
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
  installRecipeQueries(bot, recipeFactory);
  installExplosion(bot);
  return { bot, Vec3, Block, Item, Entity, ChatMessage,
    Particle, ChunkColumn, BossBar: bossBars.BossBar, MessageBuilder: ChatMessage.MessageBuilder, ...recipeFactory, update, drainControls };
}
