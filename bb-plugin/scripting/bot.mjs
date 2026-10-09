import { emitWindow, createWindowFactory } from './windows.mjs';
import { createParticleClass } from './particle.mjs';
import { createChunkClass } from './chunks.mjs';
import { installColumns } from './columns.mjs';
import { installBossBars } from './boss-bars.mjs';
import { installScoreboards } from './scoreboard.mjs';
import { Vec3 } from 'vec3';
import data from 'minecraft-version-data';
import { createBlockClass } from './blocks.mjs';
import { createItemClass, fromNotch } from './items.mjs';
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
import { guardBot } from './guard.mjs';

// Only native observation fields may be copied onto a shared Entity instance.
const entityFields = ['uuid', 'width', 'height', 'onGround', 'eyePosition', 'alive',
  'isInWater', 'isInLava', 'crouching', 'elytraFlying',
  'health', 'mainHand', 'isSleeping', 'airSupply', 'maxAirSupply', 'attributes', 'agent'];
const metadataKeys = new WeakMap();

// Keep sparse initial values and actual resets without inventing native defaults.
function mergeEntityMetadata(entity, frames) {
  if (!Array.isArray(frames) || frames.length > 1024) throw new Error('Invalid metadata observations');
  let seen = metadataKeys.get(entity);
  if (!seen) { seen = new Set(); metadataKeys.set(entity, seen); }
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
      entity.metadata[key] = value;
      seen.add(key);
    }
  }
}

export function createBot(initial) {
  if (!initial.registryCodecs) throw new Error('Native registry codecs are missing');
  const registry = createRegistry(data, initial.registryCodecs);
  const biomeNames = initial.itemRegistries.references['minecraft:worldgen/biome'];
  if (!Array.isArray(biomeNames) || biomeNames.length !== registry.biomesArray.length
    || biomeNames.some((name, id) => registry.biomesArray[id].name !== name.replace(/^minecraft:/, '')))
    throw new Error('Native biome codec and item registry IDs differ');
  const { Block, digTime } = createBlockClass(registry);
  const ChunkColumn = createChunkClass(registry, Block);
  const Particle = createParticleClass(registry);
  const Item = createItemClass(registry);
  const ChatMessage = createChatMessageClass(registry);
  const Entity = createEntityClass(registry, { Item, ChatMessage });
  const recipeFactory = createRecipeFactory(registry);
  const { createWindow } = createWindowFactory();
  const windowKeys = new WeakMap();
  const equipmentKeys = new WeakMap();
  const playerKeys = new WeakMap();
  let snapshot;
  let lastPhysicsTick;
  const stateWaits = new Set();
  const vector = ({ x, y, z }) => new Vec3(x, y, z);
  const item = entry => {
    if (!entry) return null;
    const stack = fromNotch(Item, decodeItemTransport(entry.item));
    if (stack && entry.slot !== undefined)
      stack.slot = entry.slot < 9 ? entry.slot + 36 : entry.slot === 40 ? 45 : entry.slot < 36 ? entry.slot : 44 - entry.slot;
    return stack;
  };
  // Listeners run with this === bot, Emission stays private.
  const events = new EventEmitter();
  const emit = (event, ...args) => events.emit(event, ...args);
  const bound = new WeakMap();
  const listener = callback => {
    if (typeof callback !== 'function') throw new TypeError('Listener must be a function');
    let wrapped = bound.get(callback);
    if (!wrapped) { wrapped = (...args) => callback.apply(bot, args); bound.set(callback, wrapped); }
    return wrapped;
  };
  const { attributesByName, biomesByName, blockLoot, blocksByName, effects, effectsByName, enchantmentsByName,
    entitiesByName, entityLoot, foodsByName, items, itemsByName, particles } = registry;
  const bot = {
    registry: { attributesByName, biomesByName, blockLoot, blocksByName, effects, effectsByName, enchantmentsByName,
      entitiesByName, entityLoot, foodsByName, items, itemsByName, particles },
    version: '1.21.1',
    // Component holder IDs belong to this world's registries
    nativeRegistries: initial.itemRegistries.references,
    entities: {},
    players: Object.create(null),
    inventory: createWindow('minecraft:inventory', 'Inventory'),
    currentWindow: null,
    quickBarSlot: null,
    // Filled once observed; declared so the guard treats them as part of the API.
    spawnPoint: null,
    experience: {},
    on(event, callback) { events.on(event, listener(callback)); return bot; },
    once(event, callback) { events.once(event, listener(callback)); return bot; },
    off(event, callback) { events.off(event, listener(callback)); return bot; },
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
    async waitForTicks(ticks) {
      if (ticks <= 0) return;
      // Count ticks after earlier controls reached native state, not queued frames
      // from before a synchronous setControlState/activateItem call.
      await drainControls();
      await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
          events.off('physicsTick', tickListener);
          reject(new Error(`Timeout waiting for ${ticks} ticks after ${(ticks * 50 + 5000)}ms`));
        }, ticks * 50 + 5000);
        const tickListener = () => {
          ticks--;
          if (ticks === 0) {
            clearTimeout(timeout);
            events.off('physicsTick', tickListener);
            resolve();
          }
        };
        events.on('physicsTick', tickListener);
      });
    },
  };
  // A stable view of observed Mob state; assignments cannot fake native settings.
  Object.defineProperty(bot, 'settings', { enumerable: true, value: Object.freeze({
    get mainHand() { return snapshot?.body.mainHand; },
  }) });
  Object.defineProperty(bot, 'heldItem', { enumerable: true, get: () => bot.inventory.slots[36 + bot.quickBarSlot] });
  installEntityQueries(bot);
  const updateState = installState(bot);
  const scoreboards = installScoreboards(bot, ChatMessage);
  const bossBars = installBossBars(bot, ChatMessage);
  const columns = installColumns(bot, ChunkColumn);
  // Internal block reads and raycasts use the observed column cache.
  const world = createWorldView(position => columns.getBlock(position));
  const blockEvents = installBlockEvents(bot, registry.instruments);
  installWorldQueries(bot, { getBlock: world.getBlock, getLoadedBounds() {
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
  const blockPosition = ({ min, size }, i) =>
    new Vec3(min[0] + i % size[0], min[1] + Math.floor(i / (size[0] * size[2])), min[2] + Math.floor(i / size[0]) % size[2]);
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
    const trackBlockEntities = streamed && snapshot && events.listenerCount('blockEntityData') > 0;
    if (trackBlockEntities) {
      for (const [key, tag] of Object.entries(blocks.entities)) {
        const position = blockPosition(blocks.min ? blocks : snapshot.blocks, Number(key));
        const before = bot.blockAt(position);
        if (before && JSON.stringify(before.entity ?? null) !== JSON.stringify(tag))
          blockEntityPositions.set(position.toString(), position);
      }
    }
    // Chest-lid state resets on every observed change, so changes are always tracked.
    const trackBlocks = !!snapshot;
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
      for (let n = 0; n < blocks.changes.length; n += 4) {
        const [i, state, biome, light] = blocks.changes.slice(n, n + 4);
        if (trackBlocks) oldBlock(blockPosition(cached, i), state);
        cached.states[i] = state;
        cached.biomes[i] = biome;
        cached.light[i] = light;
      }
      cached.entities = blocks.entities;
      next.blocks = cached;
    } else if (trackBlocks) {
      for (let i = 0; i < blocks.states.length; i++) oldBlock(blockPosition(blocks, i), blocks.states[i]);
    }
    const columnUpdate = columns.update(next.columnView, trackBlocks, next.blocks, trackBlockEntities);
    for (const position of columnUpdate.blockEntities) blockEntityPositions.set(position.toString(), position);
    changesFromColumns(columnUpdate.changes, changed);
    columns.patchLocal(next.blocks);
    snapshot = next;
    const present = new Set();
    const spawned = [], gone = [];
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
      const effects = source.effects ?? {};
      for (const [id, effect] of Object.entries(effects)) Object.assign(entity.effects[id] ??= {}, effect);
      for (const id of Object.keys(entity.effects)) if (!Object.hasOwn(effects, id)) delete entity.effects[id];
      const { position, velocity, yaw, pitch, type, name, equipment } = source;
      const kind = registry.entitiesByName[type.replace(/^minecraft:/, '')];
      for (const key of entityFields) if (Object.hasOwn(source, key)) entity[key] = source[key];
      Object.assign(entity, { yaw: (180 - yaw) * Math.PI / 180, pitch: -pitch * Math.PI / 180,
        name: kind?.name ?? 'unknown', displayName: kind?.displayName ?? name, type: Object.hasOwn(source, 'experienceValue') ? 'orb' : kind?.type ?? 'other',
        isValid: true });
      if (Object.hasOwn(source, 'experienceValue')) entity.count = source.experienceValue;
      if (kind?.name === 'player') entity.username = name;
      entity.position.update(position);
      entity.velocity.update(velocity);
      mergeEntityMetadata(entity, metadataObservations.get(source.uuid));
      if (equipment) {
        let keys = equipmentKeys.get(entity);
        if (!keys) { keys = []; equipmentKeys.set(entity, keys); }
        for (let slot = 0; slot < equipment.length; slot++) {
          if (keys[slot] !== equipment[slot].itemKey) {
            keys[slot] = equipment[slot].itemKey;
            entity.equipment[slot] = item(equipment[slot]);
          }
        }
      }
      if (source.id === next.body.id) {
        bot.entity = entity;
        bot.username = name;
      } else if (fresh) spawned.push(entity);
    }
    for (const [id, entity] of Object.entries(bot.entities)) {
      if (!present.has(Number(id))) {
        entity.isValid = false;
        delete bot.entities[id];
        gone.push(entity);
      }
    }
    for (const source of sources) {
      const entity = bot.entities[source.id];
      // Missing observations stay undefined, distinct from no vehicle. Do not
      // invent entities or erase passenger positions at the cache boundary.
      entity.passengers = (source.passengers ?? []).map(id => bot.entities[id]);
      entity.vehicle = source.vehicle == null ? null : bot.entities[source.vehicle];
    }
    bot.vehicle = bot.entity.vehicle;
    bot.fireworkRocketDuration = 0;
    if (bot.entity.elytraFlying) for (const source of sources) {
      if (source.fireworkAttachedTo === next.body.id && source.fireworkTicksRemaining > 0)
        bot.fireworkRocketDuration = Math.max(bot.fireworkRocketDuration, source.fireworkTicksRemaining);
    }
    const presentPlayers = new Set();
    for (const source of next.players ?? []) {
      presentPlayers.add(source.username);
      let player = bot.players[source.username];
      if (!player || player.uuid !== source.uuid) player = bot.players[source.username] = {};
      const key = JSON.stringify(source);
      if (playerKeys.get(player) !== key) {
        Object.assign(player, source, { displayName: new ChatMessage(source.displayName) });
        playerKeys.set(player, key);
      }
      player.entity = Object.values(bot.entities).find(entity => entity.uuid === source.uuid) ?? null;
    }
    for (const username of Object.keys(bot.players)) if (!presentPlayers.has(username)) delete bot.players[username];
    const game = { minY: next.minY, height: next.height, dimension: next.dimension.replace(/^minecraft:/, ''),
      gameMode: next.hands.mode === 'survival' ? 'survival' : 'creative' };
    for (const key of ['difficulty', 'hardcore'])
      if (next.worldState?.[key] !== undefined) game[key] = next.worldState[key];
    Object.assign(bot.game ??= {}, game);
    const spawn = next.worldState?.spawnPoint;
    if (spawn) {
      if (bot.spawnPoint) bot.spawnPoint.set(spawn.x, spawn.y, spawn.z);
      else bot.spawnPoint = vector(spawn);
    }
    if (next.hands.experience) Object.assign(bot.experience ??= {}, { level: next.hands.experience.level,
      progress: next.hands.experience.progress, points: next.hands.experience.total });
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
    const previousWindow = bot.currentWindow;
    if (menu.type === 'minecraft:inventory') bot.currentWindow = null;
    else {
      if (!previousWindow || previousWindow.type !== menu.type
          || windowKeys.get(previousWindow)?.generation !== menu.generation) {
        const opened = createWindow(menu.type, menu.titleNbt ?? menu.title ?? '', menu.slots.length - 36);
        if (!opened) throw new Error(`Unknown native window type ${menu.type}`);
        bot.currentWindow = opened;
      }
      windowEvents.push(...hydrateWindow(bot.currentWindow, menu.slots, menu.carried, menu.generation));
    }
    bot.entity.equipment = [bot.heldItem, bot.inventory.slots[45], bot.inventory.slots[8],
      bot.inventory.slots[7], bot.inventory.slots[6], bot.inventory.slots[5]];
    bot.usingHeldItem = next.hands.usingItem;
    const stateEvents = updateState(next);
    scoreboards.update(next.scoreboard);
    bossBars.update(next.bossBars);
    inventory.syncWindow(bot.currentWindow ?? bot.inventory, menu);
    // A native frame changes all slots/cursor together. Listeners must see the
    // complete inventory and container state, including held equipment.
    for (const [window, slot, before, after] of windowEvents) emitWindow(window, 'updateSlot', slot, before, after);
    if (previousWindow && previousWindow !== bot.currentWindow) inventory.windowClosed(previousWindow);
    for (const event of stateEvents) emit(...event);
    for (const [before, position] of changed) {
      const after = bot.blockAt(position);
      blockEvents.blockUpdated(before, after);
      emit('blockUpdate', before, after);
    }
    for (const position of blockEntityPositions.values()) emit('blockEntityData', bot.blockAt(position));
    for (const event of blockEvents.update(next.blockEvents)) emit(...event);
    if (streamed) {
      for (const entity of spawned) emit('entitySpawn', entity);
      for (const entity of gone) emit('entityGone', entity);
      if (next.tick !== lastPhysicsTick) {
        lastPhysicsTick = next.tick;
        emit('physicsTick');
      }
    }
    for (const event of next.entityEvents ?? []) {
      if (event.name === 'forcedMove') { emit('forcedMove'); continue; }
      const subject = bot.entities[event.subject], cause = event.cause == null ? undefined : bot.entities[event.cause];
      emit(event.name, subject, cause);
    }
    for (const sound of next.sounds ?? []) {
      emit('soundEffectHeard', sound.name, vector(sound.position), sound.volume, sound.pitch);
    }
    for (const particle of next.particles ?? []) {
      const type = registry.particlesByName[particle.name];
      if (!type) throw new Error(`Unknown native particle type: ${particle.name}`);
      emit('particle', Particle.fromNetwork({ ...particle, particle: { type: type.id } }));
    }
    for (const entry of next.messages ?? []) {
      if (entry.kind === 'title') {
        emit('title', new ChatMessage(entry.message).toString(), entry.type);
        continue;
      }
      if (entry.kind === 'title_times') {
        emit('title_times', entry.fadeIn, entry.stay, entry.fadeOut);
        continue;
      }
      if (entry.kind === 'title_clear') { emit('title_clear'); continue; }
      const message = new ChatMessage(entry.message);
      emit('message', message, entry.position, entry.sender ?? null, entry.verified);
      dispatchMessage(message.toString());
    }
    for (const wait of stateWaits) {
      if (next.completedActionSequence >= wait.sequence) { stateWaits.delete(wait); wait.resolve(); }
    }
  }
  function hydrateWindow(window, entries, carried, generation) {
    const changes = [];
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
        changes.push([window, slot, before, after]);
      }
    }
    if (keys.carried !== carried.itemKey) {
      keys.carried = carried.itemKey;
      window.selectedItem = item(carried);
    }
    return changes;
  }
  const inventory = installInventory(bot, { action, Item, snapshot: () => snapshot, decodeItem: item,
    isKnownActionError: error => error instanceof NativeActionError,
    assertActive() {
      if (snapshot.session !== initial.session || snapshot.body.uuid !== initial.body.uuid || !bot.entity.isValid)
        throw new Error('Script body or world session changed');
    },
  });
  const dispatchMessage = installChatPatterns(bot, emit);
  update(initial);
  const actions = installActions(bot, { request, action, digTime, waitForActionState, snapshot: () => snapshot,
    enqueueControl: inventory.enqueueControl, drainControls: inventory.drainControls,
    isKnownActionError: error => error instanceof NativeActionError,
    emit, raycast: (...args) => world.raycast(...args) });
  async function drainControls() { await inventory.drainControls(); await actions.drainControls(); }
  lastPhysicsTick = initial.tick;
  installRecipeQueries(bot, recipeFactory);
  return { bot: guardBot(bot), Vec3, Item, ChatMessage, MessageBuilder: ChatMessage.MessageBuilder, update, drainControls };
}
