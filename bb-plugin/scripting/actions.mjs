// Mineflayer 4.39.0 public action orchestration, adapted to native bodies.
// See actions.LICENSE. No client packets, predicted block edits or mining timers.
import { Vec3 } from 'vec3';
import { performance } from 'perf_hooks'; // Existing trusted QuickJS clock alias.

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

export function installActions(bot, { request, waitForActionState, action, snapshot, drainControls = async () => {}, isKnownActionError = () => false }) {
  const session = snapshot().session;
  let activeDig, targetOwner, poisoned, controlFailure;
  const stops = new Set();
  bot.targetDigBlock = null;
  bot.targetDigFace = null;
  bot.lastDigTime = null;
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
    need(Number.isFinite(bot.entity.eyeHeight), 'MissingBodyEyes', 'Native body eye height is unavailable');
    return vector(bot.entity.position, 'body position').offset(0, bot.entity.eyeHeight, 0);
  }
  bot._getBlockAtEyeLevel = () => bot.blockAt(eyes());
  bot.digTime = block => {
    need(block && typeof block.digTime === 'function', 'InvalidBlock', 'digTime requires a Block');
    const held = bot.heldItem, helmet = bot.inventory.slots[bot.getEquipmentDestSlot('head')];
    return block.digTime(held?.type ?? null, bot.game.gameMode === 'creative',
      ['water','flowing_water'].includes(bot._getBlockAtEyeLevel()?.name), !bot.entity.onGround,
      [...(held?.enchants ?? []), ...(helmet?.enchants ?? [])], bot.entity.effects);
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
      const hit = bot.world.raycast(eye, delta.normalize(), delta.norm() + .01);
      if (hit?.position.equals(block.position) && Number.isInteger(hit.face) && hit.face >= 0 && hit.face < 6)
        candidates.push({ face: hit.face, distance: eye.distanceSquared(hit.intersect) });
    }
    candidates.sort((a,b) => a.distance - b.distance);
    need(candidates.length, 'BlockNotInView', 'No visible native candidate face in the loaded block snapshot');
    return candidates[0].face;
  }
  function updateDigFace() {
    const active = targetOwner, state = snapshot().action;
    if (!active?.id || state?.id !== active.id || active.terminal) return;
    const face = state.progress?.face;
    const index = typeof face === 'string' ? faces.indexOf(face) : face;
    if (Number.isInteger(index) && index >= 0 && index < 6) bot.targetDigFace = index;
  }
  bot.on('physicsTick', updateDigFace);
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
      let completedBlock, knownFailure;
      try {
        if (previous) await previous.done.catch(() => {});
        ready(); await drainControls(); ready();
        if (active.cancelRequested) throw failure('DiggingAborted', 'Digging aborted');
        observed(block);
        targetOwner = active; bot.targetDigBlock = block; bot.targetDigFace = face ?? null;
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
        completedBlock = after;
      } catch (error) { knownFailure = !poisoned; throw error; }
      finally {
        active.terminal = true;
        if (targetOwner === active) { targetOwner = undefined; bot.targetDigBlock = null; bot.targetDigFace = null; }
        if (activeDig === active) activeDig = undefined;
        bot.lastDigTime = performance.now();
        // Native action/control/state have drained before event handlers can
        // synchronously start another mutation. Unknown outcome is not an abort.
        if (completedBlock) bot.emit('diggingCompleted', completedBlock);
        else if (knownFailure) bot.emit('diggingAborted', block);
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
  async function place(referenceBlock, faceVector, options, verify) {
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
    await perform({ type: 'place', ...reference, face: faces[index], cursorPos: cursor, forceLook: force,
      offhand: !!options.offhand, ...(options.swingArm ? { swingArm: options.swingArm, showHand: options.showHand ?? true } : {}),
      ...(verify ? { expectedDestination: destination } : {}) });
    if (verify) {
      const after = bot.blockAt(destination);
      need(after && after.stateId !== before.stateId, 'PlacementNotVerified', 'Native placement did not change the requested destination');
      bot.emit('blockPlaced', before, after);
    }
    return referenceBlock.position;
  }
  bot._genericPlace = (block, face, options = {}) => place(block, face, options, false);
  bot._placeBlockWithOptions = async (block, face, options = {}) => { await place(block, face, options, true); };
  bot.placeBlock = (block, face) => bot._placeBlockWithOptions(block, face, { swingArm: 'right' });
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
  return { async drainControls() {
    while (stops.size) await Promise.allSettled([...stops]);
    ready();
  } };
}
