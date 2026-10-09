import { Vec3 } from 'vec3';

const faces = ['down', 'up', 'north', 'south', 'west', 'east'];
const normals = [new Vec3(0,-1,0), new Vec3(0,1,0), new Vec3(0,0,-1), new Vec3(0,0,1), new Vec3(-1,0,0), new Vec3(1,0,0)];
const failure = (code, message) => Object.assign(new Error(message), { name: 'ActionError', code });
const need = (valid, code, message) => { if (!valid) throw failure(code, message); };
function vector(value, label) {
  need(value && ['x','y','z'].every(axis => Number.isFinite(value[axis])), 'InvalidVector', `Invalid ${label}`);
  return new Vec3(value.x, value.y, value.z);
}
function faceIndex(value) {
  const v = vector(value, 'face vector');
  const index = normals.findIndex(normal => normal.equals(v));
  need(index >= 0, 'InvalidFace', 'Expected one of the six cardinal face vectors');
  return index;
}
function forceValue(force) {
  need(force == null || typeof force === 'boolean' || force === 'ignore', 'InvalidForceLook', 'forceLook must be boolean or ignore');
  return force ?? false;
}

export function installActions(bot, { request, waitForActionState, action, digTime, snapshot, enqueueControl, drainControls = async () => {}, isKnownActionError = () => false, emit, raycast }) {
  const session = snapshot().session;
  let activeDig, activeConsume, activeFishing, targetOwner, poisoned, controlFailure;
  const stops = new Set();
  bot.targetDigBlock = null;
  function ready() {
    if (poisoned) throw poisoned;
    if (controlFailure) throw controlFailure;
    need(snapshot().session === session, 'WorldChanged', 'The native world session changed');
  }
  async function native(operation, args) {
    ready();
    try { return await request(operation, args); }
    catch (error) { if (!isKnownActionError(error)) poisoned = error; throw error; }
  }
  async function perform(args) {
    ready(); await drainControls(); ready();
    let result;
    try { result = await action(args); }
    catch (error) { if (!isKnownActionError(error)) poisoned = error; throw error; }
    need(result.result?.status !== 'failed', 'NativeInteractionFailed', 'Native interaction explicitly failed');
    return result;
  }
  function observed(block) {
    const position = vector(block?.position, 'block position');
    need(['x','y','z'].every(axis => Number.isSafeInteger(position[axis])), 'InvalidBlock', 'Block position must be integral');
    const current = bot.blockAt(position);
    need(current, 'BlockUnavailable', 'Block is outside the loaded snapshot');
    need(Number.isInteger(block.stateId) && current.stateId === block.stateId, 'BlockChanged', 'The observed block has changed');
    return { position, expectedStateId: current.stateId };
  }
  function eyes() {
    return vector(bot.entity.eyePosition, 'body eye position');
  }
  function miningEnchants(item) {
    const value = item?.enchants;
    if (Array.isArray(value)) return value;
    const names = bot.nativeRegistries?.['minecraft:enchantment'];
    return (value?.enchantments ?? []).map(entry => ({ name: names?.[entry.id], lvl: entry.level }));
  }
  bot.digTime = block => {
    need(block && Number.isInteger(block.stateId), 'InvalidBlock', 'digTime requires a Block');
    const held = bot.heldItem, helmet = bot.inventory.slots[5];
    return digTime(block, held?.type ?? null, bot.game.gameMode === 'creative',
      ['water','flowing_water'].includes(bot.blockAt(eyes())?.name), !bot.entity.onGround,
      [...miningEnchants(held), ...miningEnchants(helmet)], bot.entity.effects);
  };
  bot.canDigBlock = block => {
    if (!block?.diggable || !block.position) return false;
    const current = bot.blockAt(block.position);
    if (!current || current.stateId !== block.stateId) return false;
    const range = snapshot().hands.blockInteractionRange;
    need(Number.isFinite(range) && range >= 0, 'MissingNativeReach', 'Native block interaction range is unavailable');
    const eye = eyes(), pos = block.position;
    // Native Player.canInteractWithBlock measures eye distance to the block AABB.
    const distance = ['x','y','z'].reduce((sum, axis) => sum + Math.max(pos[axis] - eye[axis], 0, eye[axis] - pos[axis] - 1) ** 2, 0);
    return distance < range * range;
  };
  function rayFace(block) {
    const eye = eyes(), center = block.position.offset(.5,.5,.5), candidates = [];
    for (const index of [0,1,4,5,2,3]) {
      const normal = normals[index];
      if (eye.minus(center).dot(normal) <= .5) continue;
      const point = center.plus(normal.scaled(.5)), delta = point.minus(eye);
      const hit = raycast(eye, delta.normalize(), delta.norm() + .01);
      if (hit?.position.equals(block.position) && Number.isInteger(hit.face) && hit.face >= 0 && hit.face < 6)
        candidates.push({ face: hit.face, distance: eye.distanceSquared(hit.intersect) });
    }
    candidates.sort((a,b) => a.distance - b.distance);
    need(candidates.length, 'BlockNotInView', 'No visible native candidate face in the loaded block snapshot');
    return candidates[0].face;
  }
  function cancel(active) {
    if (!active || active.terminal || active.cancelRequested) return;
    active.cancelRequested = true;
    if (active.id) sendCancel(active);
  }
  function sendCancel(active) {
    if (active.cancelSent || active.terminal) return;
    active.cancelSent = true;
    // Capture rejection immediately; do not leave a control promise unhandled
    // while the independent terminal wait is pending.
    active.control = native('cancelAction', { id: active.id }).catch(error => { active.controlError = error; });
  }
  bot.stopDigging = () => {
    const active = activeDig;
    if (!active || active.terminal || active.stopTracked) return;
    active.stopTracked = true; cancel(active);
    const stopped = active.done.catch(error => {
      if (error.code !== 'DiggingAborted') throw error;
    });
    stops.add(stopped);
    stopped.then(() => stops.delete(stopped), error => { stops.delete(stopped); controlFailure = error; });
  };
  bot.dig = (block, forceLook, digFace = 'auto') => {
    let target, force, face;
    try {
      ready(); target = observed(block); force = forceValue(forceLook);
      need(bot.digTime(block) !== Infinity, 'UnbreakableBlock', `dig time for ${block.name} is Infinity`);
      if (digFace == null || typeof digFace === 'function') digFace = 'auto';
      if (force !== 'ignore' && digFace === 'raycast') face = rayFace(block);
      else if (force !== 'ignore' && typeof digFace === 'object') face = faceIndex(digFace);
      else need(digFace === 'auto' || digFace === 'raycast' || force === 'ignore', 'InvalidFace', 'digFace must be auto, raycast or a face vector');
    } catch (error) { return Promise.reject(error); }
    const previous = activeDig;
    cancel(previous);
    const active = { block, id: null, terminal: false, cancelRequested: false, control: null };
    activeDig = active;
    active.done = (async () => {
      try {
        if (previous) await previous.done.catch(() => {});
        ready(); await drainControls(); ready();
        if (active.cancelRequested) throw failure('DiggingAborted', 'Digging aborted');
        observed(block);
        targetOwner = active; bot.targetDigBlock = block;
        const started = await native('startAction', { type: 'mine', ...target, forceLook: force, ...(face != null ? { face: faces[face] } : {}) });
        if (typeof started?.id !== 'string' || !started.id) {
          poisoned = failure('InvalidNativeReply', 'Native mine start did not return an action ID'); throw poisoned;
        }
        active.id = started.id;
        if (active.cancelRequested) sendCancel(active);
        const result = await native('awaitAction', { id: active.id });
        if (typeof result?.status !== 'string' || !Number.isSafeInteger(result.sequence) || result.sequence < 0) {
          poisoned = failure('InvalidNativeReply', 'Native mine wait did not return a sequenced terminal result'); throw poisoned;
        }
        active.terminal = true;
        await active.control;
        ready();
        try { await waitForActionState(result); }
        catch (error) { poisoned = error; throw error; }
        ready();
        if (active.controlError && result.status !== 'completed') throw active.controlError;
        if (result.status === 'cancelled' || (active.cancelRequested && result.status !== 'completed'))
          throw failure('DiggingAborted', 'Digging aborted');
        need(result.status === 'completed', 'NativeDigFailed', `Native digging failed: ${result.detail ?? result.status}`);
        const after = bot.blockAt(target.position);
        need(after && after.stateId !== target.expectedStateId, 'DigNotVerified', 'Native dig did not expose a changed target block');
      } finally {
        active.terminal = true;
        if (targetOwner === active) { targetOwner = undefined; bot.targetDigBlock = null; }
        if (activeDig === active) activeDig = undefined;
      }
    })();
    return active.done;
  };
  bot.look = async (yaw, pitch, force = false) => {
    need(Number.isFinite(yaw) && Number.isFinite(pitch) && pitch >= -Math.PI / 2 && pitch <= Math.PI / 2,
      'InvalidLook', 'Expected finite yaw and pitch in [-pi/2, pi/2]');
    need(typeof force === 'boolean', 'InvalidLook', 'Look force must be boolean');
    await perform({ type: 'look', yaw, pitch, force });
  };
  bot.lookAt = async (point, force = false) => {
    const delta = vector(point, 'look point').minus(eyes());
    await bot.look(Math.atan2(-delta.x, -delta.z), Math.atan2(delta.y, Math.hypot(delta.x, delta.z)), force);
  };
  async function place(referenceBlock, faceVector, options, verify, entityPlacement = false) {
    const reference = observed(referenceBlock), index = faceIndex(faceVector), normal = normals[index];
    const force = forceValue(options.forceLook);
    need(options.offhand ? bot.inventory.slots[45] : bot.heldItem, 'EmptyHand', 'Must be holding an item to place');
    let cursor = new Vec3(.5 + normal.x * .5, .5 + normal.y * .5, .5 + normal.z * .5);
    need(options.half == null || options.half === 'top' || options.half === 'bottom', 'InvalidPlacement', 'half must be top or bottom');
    if (cursor.y === .5) cursor.y += options.half === 'top' ? .25 : options.half === 'bottom' ? -.25 : 0;
    if (options.delta) cursor = vector(options.delta, 'placement cursor');
    need(['x','y','z'].every(axis => cursor[axis] >= 0 && cursor[axis] <= 1), 'InvalidPlacement', 'Placement cursor must be block-local');
    need(options.swingArm == null || ['left','right'].includes(options.swingArm), 'InvalidPlacement', 'Invalid swing arm');
    const destination = reference.position.plus(normal), before = verify ? bot.blockAt(destination) : null;
    if (verify) need(before, 'BlockUnavailable', 'Placement destination is outside the loaded snapshot');
    const result = await perform({ type: entityPlacement ? 'place_entity' : 'place', ...reference, face: faces[index], cursorPos: cursor, forceLook: force,
      offhand: !!options.offhand, ...(options.swingArm ? { swingArm: options.swingArm, showHand: options.showHand ?? true } : {}),
      ...(verify ? { expectedDestination: destination } : {}) });
    if (entityPlacement) return result.result?.spawnedEntities ?? [];
    if (verify) {
      const after = bot.blockAt(destination);
      need(after && after.stateId !== before.stateId, 'PlacementNotVerified', 'Native placement did not change the requested destination');
    }
    return referenceBlock.position;
  }
  bot._genericPlace = (block, face, options = {}) => place(block, face, options, false);
  bot._placeBlockWithOptions = async (block, face, options = {}) => { await place(block, face, options, true); };
  bot.placeBlock = (block, face) => bot._placeBlockWithOptions(block, face, { swingArm: 'right' });
  bot._placeEntityWithOptions = async (block, face, options = {}) => {
    const held = options.offhand ? bot.inventory.slots[45] : bot.heldItem;
    need(held, 'EmptyHand', 'Must be holding an item to place an entity');
    const name = held.name;
    const boat = /_(boat|raft)$/.test(name);
    const egg = name.endsWith('_spawn_egg');
    need(boat || egg || name === 'armor_stand' || name === 'end_crystal', 'InvalidEntityItem', 'Item does not place a supported entity');
    const ids = await place(block, face, { ...options, swingArm: options.swingArm ?? (options.offhand ? 'left' : 'right') }, false, true);
    const matches = Object.values(bot.entities).filter(entity => ids.includes(entity.uuid) &&
      (boat ? ['boat', 'chest_boat'].includes(entity.name) : egg ? entity.name === held.spawnEggMobName : entity.name === name));
    need(matches.length === 1, 'EntityPlacementNotVerified', 'Native placement did not expose exactly one matching new entity');
    return matches[0];
  };
  bot.placeEntity = (block, face) => bot._placeEntityWithOptions(block, face);
  bot.activateBlock = async (block, direction = new Vec3(0,1,0), cursorPos = new Vec3(.5,.5,.5)) => {
    const reference = observed(block), face = faceIndex(direction ?? new Vec3(0,1,0));
    const cursor = vector(cursorPos ?? new Vec3(.5,.5,.5), 'block cursor');
    need(['x','y','z'].every(axis => cursor[axis] >= 0 && cursor[axis] <= 1), 'InvalidCursor', 'Block cursor must be block-local');
    await perform({ type: 'interact', ...reference, face: faces[face], cursorPos: cursor });
  };
  function entityId(entity) {
    need(typeof entity?.uuid === 'string' && entity.uuid.length, 'InvalidEntity', 'Expected an entity with a native UUID');
    return entity.uuid;
  }
  bot.activateEntity = async entity => { await perform({ type: 'interact', entity: entityId(entity) }); };
  bot.activateEntityAt = async (entity, position) => {
    const id = entityId(entity), point = vector(position, 'entity interaction point');
    await perform({ type: 'interact', entity: id, entityAt: point.minus(vector(entity.position, 'entity position')) });
  };
  function control(args) { ready(); enqueueControl(args); }
  bot.attack = (entity, swing = true) => { control({ type: 'attack', entity: entityId(entity), swing: !!swing }); };
  bot.swingArm = (arm = 'right', showHand = true) => {
    control({ type: 'swing', offhand: arm !== 'right', showHand: !!showHand });
  };
  bot.updateSign = (block, text, back = false) => {
    const lines = text.split('\n');
    if (lines.length > 4 || lines.some(line => line.length > 45)) {
      emit('error', new Error('Signs require at most four lines of 45 characters')); return;
    }
    while (lines.length < 4) lines.push('');
    control({ type: 'update_sign', ...observed(block), lines, front: !back });
  };
  bot.mount = entity => { control({ type: 'interact', entity: entityId(entity), forceLook: 'ignore' }); };
  bot.moveVehicle = (left, forward) => {
    need(Number.isFinite(left) && Number.isFinite(forward), 'InvalidVehicleInput', 'Vehicle inputs must be finite numbers');
    control({ type: 'vehicle_control', left: Math.max(-1, Math.min(1, left)), forward: Math.max(-1, Math.min(1, forward)) });
  };
  bot.dismount = () => {
    if (!bot.vehicle) { emit('error', new Error('dismount: not mounted')); return; }
    control({ type: 'dismount' });
  };
  bot.activateItem = (offhand = false) => { control({ type: 'use', offhand: !!offhand }); };
  bot.deactivateItem = () => {
    if (!activeConsume || activeConsume.terminal) { control({ type: 'release' }); return; }
    const active = activeConsume;
    cancel(active);
    const stopped = active.done.catch(error => { if (error.code !== 'ConsumptionAborted') throw error; });
    stops.add(stopped);
    stopped.then(() => stops.delete(stopped), error => { stops.delete(stopped); controlFailure = error; });
  };
  bot.consume = () => {
    const previous = activeConsume;
    cancel(previous);
    const active = { id: null, terminal: false, cancelRequested: false, control: null };
    activeConsume = active;
    active.done = (async () => {
      try {
        if (previous) await previous.done.catch(() => {});
        ready(); await drainControls(); ready();
        if (active.cancelRequested) throw failure('ConsumptionAborted', 'Consumption aborted');
        const started = await native('startAction', { type: 'consume' });
        if (typeof started?.id !== 'string' || !started.id) {
          poisoned = failure('InvalidNativeReply', 'Native consumption start did not return an action ID'); throw poisoned;
        }
        active.id = started.id;
        if (active.cancelRequested) sendCancel(active);
        const result = await native('awaitAction', { id: active.id });
        if (!Number.isSafeInteger(result?.sequence) || result.sequence < 0 || typeof result.status !== 'string') {
          poisoned = failure('InvalidNativeReply', 'Native consumption wait did not return a terminal sequence'); throw poisoned;
        }
        active.terminal = true;
        await active.control;
        try { await waitForActionState(result); } catch (error) { poisoned = error; throw error; }
        ready();
        if (active.controlError && result.status !== 'completed') throw active.controlError;
        if (active.cancelRequested && result.status !== 'completed') throw failure('ConsumptionAborted', 'Consumption aborted');
        need(result.status === 'completed', 'ConsumptionFailed', `Native consumption failed: ${result.detail ?? result.status}`);
      } finally {
        active.terminal = true;
        if (activeConsume === active) activeConsume = undefined;
      }
    })();
    return active.done;
  };
  bot.creative.startFlying = () => { control({ type: 'creative_flying', state: true }); };
  bot.elytraFly = async () => { await perform({ type: 'elytra_fly' }); };
  bot.creative.stopFlying = () => { control({ type: 'creative_flying', state: false }); };
  let activeMovement;
  function moveTo(destination, type) {
    let position;
    try {
      ready(); position = vector(destination, 'movement destination');
      need(!activeMovement, 'MovementAlreadyRunning', 'Await or stop the current movement first');
    } catch (error) { return Promise.reject(error); }
    const active = { id: null, stopRequested: false };
    activeMovement = active;
    active.done = (async () => {
      try {
        await drainControls(); ready();
        if (active.stopRequested) throw failure('MovementStopped', 'Movement stopped before starting');
        const started = await native('startAction', { type, position });
        need(typeof started.id === 'string', 'InvalidNativeReply', 'Movement start lacks an action ID');
        active.id = started.id;
        if (active.stopRequested) await native('stopMovement', { id: active.id });
        const result = await native('awaitAction', { id: active.id });
        await waitForActionState(result);
        const arrived = type === 'walk' ? 'arrived' : 'flight_arrived';
        if (result.detail === 'movement_stopped') throw failure('MovementStopped', 'Movement stopped');
        if (result.status !== 'completed' || result.detail !== arrived)
          throw Object.assign(failure('MovementFailed', `Movement ${result.status}: ${result.detail}`),
            { status: result.status, detail: result.detail, position: result.position });
      } finally { if (activeMovement === active) activeMovement = undefined; }
    })();
    return active.done;
  }
  bot.moveTo = destination => moveTo(destination, 'walk');
  bot.creative.flyTo = destination => moveTo(destination, 'creative_fly');
  bot.stopMoving = async () => {
    ready();
    const active = activeMovement;
    if (active) active.stopRequested = true;
    // Flush earlier synchronous controls before clearing the native inputs.
    await drainControls(); ready();
    await native('stopMovement', active?.id ? { id: active.id } : {});
    for (const name of Object.keys(heldControls)) heldControls[name] = false;
    if (active) await active.done.catch(error => { if (error.code !== 'MovementStopped') throw error; });
  };
  bot.setCommandBlock = (position, command, options = {}) => {
    const pos = vector(position, 'command block position');
    need(['x','y','z'].every(axis => Number.isSafeInteger(pos[axis])), 'InvalidBlock', 'Block position must be integral');
    need(typeof command === 'string' && command.length <= 32767, 'InvalidCommand', 'Command must be a string of at most 32767 characters');
    need(options && typeof options === 'object', 'InvalidOptions', 'Command block options must be an object');
    const mode = options.mode ?? 2;
    need(Number.isInteger(mode) && mode >= 0 && mode <= 2, 'InvalidCommandBlockMode', 'Command block mode must be 0, 1 or 2');
    const flags = {};
    for (const name of ['trackOutput', 'conditional', 'alwaysActive']) {
      flags[name] = options[name] ?? false;
      need(typeof flags[name] === 'boolean', 'InvalidOptions', `${name} must be boolean`);
    }
    need(snapshot().hands.mode === 'creative_commands', 'CommandPermissionRequired', 'Command editing requires creative_commands mode');
    const block = bot.blockAt(pos);
    need(block && ['command_block', 'chain_command_block', 'repeating_command_block'].includes(block.name),
      'InvalidCommandBlock', 'The observed block is not a command block');
    control({ type: 'set_command_block', ...observed(block), command, mode, ...flags });
  };
  bot.tabComplete = async (text, assumeCommand = false, sendBlockInSight = true, timeout = 5000) => {
    need(typeof text === 'string' && text.length <= 4096, 'InvalidCompletionText', 'Completion text must be a string of at most 4096 characters');
    need(Number.isInteger(timeout) && timeout > 0 && timeout <= 300000, 'InvalidTimeout', 'Completion timeout must be 1..300000ms');
    // Minecraft 1.21.1 sends only text; the other arguments are legacy fields.
    const result = await perform({ type: 'tab_complete', text, timeout });
    return result.result.matches;
  };
  function sendChat(message, target) {
    if (typeof message === 'number') message = String(message);
    need(typeof message === 'string', 'InvalidChat', 'Chat message type must be a string or number');
    if (target === undefined && message.startsWith('/')) { control({ type: 'chat', message }); return; }
    const limit = target === undefined ? 256 : 256 - (`/tell ${target} `).length;
    need(limit > 0, 'InvalidChatTarget', 'Whisper target is too long');
    for (const line of message.split('\n')) for (let i = 0; i < line.length; i += limit)
      control({ type: 'chat', message: line.slice(i, i + limit), ...(target === undefined ? {} : { target }) });
  }
  bot.chat = message => { sendChat(message); };
  bot.whisper = (target, message) => {
    need(typeof target === 'string' && target.length > 0, 'InvalidChatTarget', 'Whisper target must be a name');
    sendChat(message, target);
  };
  function bedOccupied(block) {
    need(!!block && /^(?:white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black)_bed$/.test(block.name),
      'InvalidBed', 'wrong block : not a bed block');
    return block.getProperties().occupied;
  }
  bot.sleep = async block => {
    need(!bot.entity.isSleeping, 'AlreadySleeping', 'already sleeping');
    need(!bedOccupied(block), 'BedOccupied', 'the bed is occupied');
    const storm = bot.isRaining && bot.thunderState > 0;
    need(storm || bot.time.timeOfDay >= 12541 && bot.time.timeOfDay <= 23458, 'SleepTime', "it's not night and it's not a thunderstorm");
    const target = observed(block);
    bot.clearControlStates();
    await perform({ type: 'interact', ...target });
    need(bot.entity.isSleeping, 'SleepRejected', 'Native body did not enter sleep');
  };
  bot.wake = async () => {
    need(bot.entity.isSleeping, 'AlreadyAwake', 'already awake');
    await perform({ type: 'wake' });
    need(!bot.entity.isSleeping, 'WakeRejected', 'Native body remained asleep');
  };
  bot.fish = () => {
    const previous = activeFishing;
    cancel(previous);
    const active = { id: null, terminal: false, cancelRequested: false, control: null };
    activeFishing = active;
    active.done = (async () => {
      try {
        if (previous) await previous.done.catch(() => {});
        ready(); await drainControls(); ready();
        if (active.cancelRequested) throw failure('FishingAborted', 'Fishing cancelled');
        const started = await native('startAction', { type: 'fish' });
        if (typeof started?.id !== 'string' || !started.id) {
          poisoned = failure('InvalidNativeReply', 'Native fishing start did not return an action ID'); throw poisoned;
        }
        active.id = started.id;
        if (active.cancelRequested) sendCancel(active);
        const result = await native('awaitAction', { id: active.id });
        if (!Number.isSafeInteger(result?.sequence) || result.sequence < 0 || typeof result.status !== 'string') {
          poisoned = failure('InvalidNativeReply', 'Native fishing wait did not return a terminal sequence'); throw poisoned;
        }
        active.terminal = true;
        await active.control;
        try { await waitForActionState(result); } catch (error) { poisoned = error; throw error; }
        ready();
        if (active.controlError && result.status !== 'completed') throw active.controlError;
        if (active.cancelRequested && result.status !== 'completed') throw failure('FishingAborted', 'Fishing cancelled');
        need(result.status === 'completed', 'FishingFailed', `Native fishing failed: ${result.detail ?? result.status}`);
      } finally {
        active.terminal = true;
        if (activeFishing === active) activeFishing = undefined;
      }
    })();
    return active.done;
  };
  const heldControls = { forward: false, back: false, left: false, right: false, jump: false, sprint: false, sneak: false };
  bot.setSettings = options => {
    need(options !== null && typeof options === 'object' && !Array.isArray(options),
      'InvalidSettings', 'Settings must be an object');
    const keys = Reflect.ownKeys(options);
    need(keys.every(key => key === 'mainHand'), 'UnsupportedSetting', 'Only native mainHand settings are available');
    if (keys.length === 0) return;
    const mainHand = options.mainHand;
    need(mainHand === 'left' || mainHand === 'right', 'InvalidMainHand', 'mainHand must be left or right');
    control({ type: 'set_settings', settings: { mainHand } });
  };
  bot.setControlState = (name, state) => {
    need(Object.hasOwn(heldControls, name) && typeof state === 'boolean', 'InvalidControl', 'Expected a control name and boolean state');
    if (heldControls[name] === state) return;
    control({ type: 'control', control: name, state });
    heldControls[name] = state;
  };
  bot.getControlState = name => {
    need(Object.hasOwn(heldControls, name), 'InvalidControl', 'Unknown control name');
    return heldControls[name];
  };
  bot.clearControlStates = () => { for (const name of Object.keys(heldControls)) bot.setControlState(name, false); };
  return { async drainControls() {
    while (stops.size) await Promise.allSettled([...stops]);
    ready();
  } };
}
