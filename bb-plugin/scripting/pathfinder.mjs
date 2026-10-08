import { Vec3 } from 'vec3';
import { Movements } from './movements.mjs';
import { installPlanning } from './planning.mjs';
import { directCandidate, directNode } from './direct-path.mjs';

const nativeSlot = slot => slot >= 36 && slot <= 44 ? slot - 36 : slot === 45 ? 40 : slot < 9 ? 44 - slot : slot;
const error = (name, message) => Object.assign(new Error(message), { name });

// Java executes only these selected edges. JS owns goals, policy callbacks,
// synchronous planning and events; the native action owns physics and edits.
export function installPathfinder(bot, { request, waitForActionState, snapshot, canShortcut }) {
  const pathfinder = bot.pathfinder = {};
  let movements = new Movements(bot);
  let goal = null;
  let dynamic = false;
  let epoch = 0;
  let search;
  let path = [];
  let route;
  let stopRequested = false;
  let planned = false;
  let lastFailure;
  let directRejected = false;
  let startReplanned = false;
  let replanAfterTick = null;
  const { getExecutionPath } = installPlanning(bot, pathfinder, { canShortcut });

  Object.defineProperties(pathfinder, {
    goal: { get: () => goal },
    movements: { get: () => movements },
  });
  pathfinder.setGoal = (next, isDynamic = false) => {
    directRejected = false;
    startReplanned = false;
    replanAfterTick = null;
    goal = next;
    dynamic = isDynamic;
    const version = ++epoch;
    bot.emit('goal_updated', next, isDynamic);
    if (epoch === version) reset('goal_updated');
  };
  pathfinder.setMovements = next => { directRejected = false; movements = next; reset('movements_updated'); };
  pathfinder.isMoving = () => path.length > 0;
  pathfinder.isMining = () => route != null && !route.terminal && snapshot().action.id === route.id && snapshot().action.progress?.isMining === true;
  pathfinder.isBuilding = () => route != null && !route.terminal && snapshot().action.id === route.id && snapshot().action.progress?.isBuilding === true;
  pathfinder.stop = () => { stopRequested = true; };
  pathfinder.goto = wanted => new Promise((resolve, reject) => {
    let settled = false;
    const reached = reachedGoal => { if (reachedGoal === wanted) finish(); };
    const changed = next => { if (next !== wanted) finish(error('GoalChanged', 'The goal was changed before it could be completed!')); };
    const stopped = () => finish(lastFailure ?? error('PathStopped', 'Path was stopped before it could be completed! Thus, the desired goal was not reached.'));
    const updated = result => {
      // An empty unreachable result is not arrival (a pinned upstream defect).
      if (result.status === 'noPath') finish(error('NoPath', 'No path to the goal!'));
      else if (result.status === 'timeout') finish(error('Timeout', 'Took to long to decide path to goal!'));
    };
    function finish(failure) {
      if (settled) return;
      settled = true;
      bot.removeListener('goal_reached', reached);
      bot.removeListener('goal_updated', changed);
      bot.removeListener('path_stop', stopped);
      bot.removeListener('path_update', updated);
      // A replaced goal may still have an action-start reply in flight. Its
      // promise settles after that exact action is cancelled and observed.
      const drained = route?.done ?? Promise.resolve();
      drained.then(() => setTimeout(() => failure ? reject(failure) : resolve(), 0));
    }
    bot.on('goal_reached', reached);
    bot.on('goal_updated', changed);
    bot.on('path_stop', stopped);
    bot.on('path_update', updated);
    pathfinder.setGoal(wanted);
  });

  function cancelRoute() {
    if (!route || route.cancelled || route.terminal) return;
    route.cancelled = true;
    if (route.id) control(route, 'cancelAction');
  }
  function control(active, operation) {
    active.control = Promise.all([active.control, request(operation, { id: active.id })]);
  }
  function reset(reason) {
    epoch++;
    search = undefined;
    planned = false;
    lastFailure = undefined;
    const wasMoving = path.length > 0;
    path = [];
    movements.clearCollisionIndex();
    cancelRoute();
    if (wasMoving && !stopRequested) bot.emit('path_reset', reason);
  }
  function stop(failure) {
    goal = null;
    search = undefined;
    path = [];
    planned = false;
    stopRequested = false;
    lastFailure = failure;
    bot.emit('path_stop');
  }
  function freeGoal() {
    return movements.allowFreeMotion && goal?.entity?.position && Number.isFinite(goal.rangeSq) && goal.rangeSq >= 0;
  }
  function atGoal(physical = false) {
    if (physical && freeGoal()) return bot.entity.position.distanceSquared(goal.entity.position) <= goal.rangeSq;
    const point = bot.entity.position.floored();
    const block = bot.blockAt(point);
    if (block && bot.entity.onGround && bot.entity.position.y - point.y > 0.001 && !movements.emptyBlocks.has(block.type))
      point.y++;
    return goal.isEnd(point);
  }
  function reachedGoal() {
    if (dynamic) return;
    const reached = goal, version = epoch;
    bot.emit('goal_reached', reached);
    if (epoch === version) goal = null;
  }

  function runRoute(nodes, pursuit = null) {
    let count = 0, edits = 0;
    const padding = Math.ceil(bot.entity.width / 2) + 2;
    const top = Math.ceil(bot.entity.height) + 2;
    let low = bot.entity.position.floored(), high = low.clone();
    function extend(point, min, max) {
      min.x = Math.min(min.x, Math.floor(point.x)); min.y = Math.min(min.y, Math.floor(point.y)); min.z = Math.min(min.z, Math.floor(point.z));
      max.x = Math.max(max.x, Math.floor(point.x)); max.y = Math.max(max.y, Math.floor(point.y)); max.z = Math.max(max.z, Math.floor(point.z));
    }
    while (count < Math.min(nodes.length, 128)) {
      const node = nodes[count], next = node.toBreak.length + node.toPlace.length;
      if (edits + next > 128) break;
      const min = low.clone(), max = high.clone();
      extend(node, min, max);
      if (node.direct) {
        extend(node.direct.from, min, max);
        extend({ ...node.direct, y: node.direct.minY }, min, max);
        extend({ ...node.direct, y: node.direct.maxY }, min, max);
      }
      for (const point of node.toBreak) extend(point, min, max);
      for (const point of node.toPlace) {
        extend(point, min, max);
        extend({ x: point.x + (point.dx ?? 0), y: point.y + (point.dy ?? 0), z: point.z + (point.dz ?? 0) }, min, max);
      }
      const cells = (max.x - min.x + padding * 2 + 1) * (max.y - min.y + top + 3) * (max.z - min.z + padding * 2 + 1);
      if (cells > 65536) break;
      low = min; high = max; edits += next; count++;
    }
    if (!count) { stop(error('NoPath', 'A single route edge exceeds the native snapshot/edit limit')); return; }
    const min = [low.x - padding, low.y - 2, low.z - padding];
    const size = [high.x - low.x + padding * 2 + 1, high.y - low.y + top + 3, high.z - low.z + padding * 2 + 1];
    const states = [];
    for (let y = min[1]; y < min[1] + size[1]; y++) for (let z = min[2]; z < min[2] + size[2]; z++) for (let x = min[0]; x < min[0] + size[0]; x++) {
      const position = new Vec3(x, y, z);
      const state = bot.world?.getBlockStateId ? bot.world.getBlockStateId(position) : bot.blockAt(position, false)?.stateId;
      states.push(state ?? -1);
    }
    const blocks = { min, size, states };
    const active = { epoch, id: null, cancelled: false, terminal: false, stopSent: false, control: null, pursuit };
    route = active;
    const scaffold = movements.getScaffoldingItem();
    const selected = nodes.slice(0, count).map(node => ({
      ...node,
      toBreak: node.toBreak.map(position => {
        const block = bot.blockAt(new Vec3(position.x, position.y, position.z));
        const tool = block && pathfinder.bestHarvestTool(block);
        return { ...position, ...(tool ? { toolSlot: nativeSlot(tool.slot) } : {}) };
      }),
    }));
    active.done = (async () => {
      try {
        const started = await request('startAction', {
          type: 'route', nodes: selected, start: bot.entity.position,
          blocks: { min: blocks.min, size: blocks.size, states: blocks.states },
          allowSprinting: movements.allowSprinting,
          ...(scaffold ? { scaffoldingSlot: nativeSlot(scaffold.slot) } : {}),
        });
        active.id = started.id;
        if (active.cancelled) control(active, 'cancelAction');
        else if (stopRequested) {
          active.stopSent = true;
          control(active, 'stopRoute');
        }
        const result = await request('awaitAction', { id: active.id });
        active.terminal = true;
        await active.control;
        await waitForActionState(result);
        if (active.cancelled || active.epoch !== epoch) return;
        if (result.status !== 'completed') {
          // Known snapshot changes or a direct preflight rejection may replan.
          // A failed placement/mining outcome is never automatically replayed.
          if (result.detail === 'route_world_changed') { reset('block_updated'); return; }
          if (result.detail === 'route_direct_preflight_rejected' && selected[0]?.direct) {
            // Java emits this only on tick one, before any route travel or edit.
            // Suppress further attempts for this target; unknown outcomes and
            // start-position rejections never take this branch.
            directRejected = true;
            path = []; planned = false; search = undefined;
            bot.emit('path_reset', 'direct_rejected');
            return;
          }
          throw error('NoPath', `Native route failed: ${result.detail ?? result.status}`);
        }
        path = [];
        if (stopRequested || result.result?.stopped) { stop(); return; }
        planned = false;
        search = undefined;
        if (goal && atGoal(pursuit !== null)) reachedGoal();
        else if (goal && pursuit) {
          if (bot.entity.position.distanceSquared(pursuit.start) < 0.01)
            throw error('NoPath', 'Direct pursuit completed without progress');
        } else if (goal && count === nodes.length) {
          // The complete selected path ended without satisfying its goal.
          // Replaying it can repeat edits indefinitely without making progress.
          throw error('NoPath', 'Native route completed without reaching the goal');
        }
      } catch (failure) {
        active.terminal = true;
        // This typed rejection precedes native preparation and owns no action.
        // Rebuild once from a newer observation; never resend the stale nodes.
        if (active.id === null && !active.cancelled && active.epoch === epoch && !stopRequested && !startReplanned
            && failure.phase === 'before_start' && failure.detail?.startsWith('route_start_changed: ')) {
          startReplanned = true;
          replanAfterTick = snapshot().tick;
          reset('start_changed');
          return;
        }
        if (!active.cancelled && active.epoch === epoch) {
          path = [];
          bot.emit('path_reset', 'execution_error');
          if (active.epoch === epoch) stop(failure);
        }
      } finally { if (route === active) route = undefined; }
    })();
  }

  bot.on('blockUpdate', (before, after) => {
    // Running routes validate their own relevant live cells and own edits.
    // An unfinished AStar search must not retain a graph from an older world.
    if (!route && search && before?.stateId !== after?.stateId) reset('block_updated');
  });
  bot.on('physicsTick', () => {
    if (goal && !goal.isValid()) { cancelRoute(); if (!route) stop(); return; }
    if (goal?.hasChanged()) { directRejected = false; reset('goal_moved'); }
    if (!stopRequested && route?.pursuit && !route.cancelled && !route.terminal && goal?.entity?.position &&
        goal.entity.position.distanceSquared(route.pursuit.target) > 0.0625) {
      directRejected = false;
      reset('goal_moved');
    }
    if (stopRequested) {
      if (!route) { stop(); return; }
      if (route.id && !route.stopSent && !route.cancelled && !route.terminal) {
        route.stopSent = true;
        control(route, 'stopRoute');
      }
    }
    if (route || !goal || !movements || planned) return;
    if (replanAfterTick !== null) {
      if (snapshot().tick <= replanAfterTick) return;
      replanAfterTick = null;
    }
    if (atGoal(!!freeGoal())) { reachedGoal(); return; }
    const version = epoch;
    if (!search && freeGoal() && !directRejected) {
      const from = bot.entity.position.clone(), target = goal.entity.position.clone();
      const distance = from.distanceTo(target);
      const travel = Math.min(8, distance - Math.max(0, Math.sqrt(goal.rangeSq) - 0.125));
      if (travel > 0.05) {
        if (movements.allowEntityDetection) { movements.clearCollisionIndex(); movements.updateCollisionIndex(); }
        const to = from.plus(target.minus(from).scaled(travel / distance));
        const direct = directCandidate(movements, from, to, { jump: true, pursuitEntity: goal.entity });
        if (epoch !== version || !goal) return;
        if (direct) {
          path = [directNode(movements, direct)];
          runRoute(path, { start: from, target });
          return;
        }
      }
    }
    // Upstream uses physical range only while taking its free-motion branch.
    // AStar fallback retains the caller's public (usually grid-based) isEnd.
    if (atGoal()) { reachedGoal(); return; }
    search ??= pathfinder.getPathFromTo(movements, bot.entity.position, goal, { optimizePath: !directRejected });
    const step = search.next();
    if (step.done) { planned = true; return; }
    const { result } = step.value;
    bot.emit('path_update', result);
    if (epoch !== version || !goal) return;
    if (result.status === 'partial') return;
    planned = true;
    search = undefined;
    if (result.status !== 'success') return;
    path = getExecutionPath(result);
    if (path.length === 0) {
      if (atGoal()) reachedGoal();
      else stop(error('NoPath', 'An empty path did not satisfy the goal'));
      return;
    }
    runRoute(path);
  });
  return pathfinder;
}
