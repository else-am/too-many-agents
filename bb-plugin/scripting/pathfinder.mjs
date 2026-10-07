import { Vec3 } from 'vec3';
import { Movements } from './movements.mjs';
import { installPlanning } from './planning.mjs';

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
  const { getRawPath } = installPlanning(bot, pathfinder, { canShortcut });

  Object.defineProperties(pathfinder, {
    goal: { get: () => goal },
    movements: { get: () => movements },
  });
  pathfinder.setGoal = (next, isDynamic = false) => {
    goal = next;
    dynamic = isDynamic;
    const version = ++epoch;
    bot.emit('goal_updated', next, isDynamic);
    if (epoch === version) reset('goal_updated');
  };
  pathfinder.setMovements = next => { movements = next; reset('movements_updated'); };
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
  function atGoal() {
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

  function runRoute(nodes) {
    let count = 0, edits = 0;
    while (count < Math.min(nodes.length, 128)) {
      const next = nodes[count].toBreak.length + nodes[count].toPlace.length;
      if (edits + next > 128) break;
      edits += next; count++;
    }
    if (!count) { stop(error('NoPath', 'A single route edge exceeds the native edit limit')); return; }
    const active = { epoch, id: null, cancelled: false, terminal: false, stopSent: false, control: null };
    route = active;
    const { blocks } = snapshot();
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
          // Only an explicit changed-snapshot rejection permits a fresh plan.
          // A failed placement/mining outcome is never automatically replayed.
          if (result.detail === 'route_world_changed') { reset('block_updated'); return; }
          throw error('NoPath', `Native route failed: ${result.detail ?? result.status}`);
        }
        path = [];
        if (stopRequested || result.result?.stopped) { stop(); return; }
        planned = false;
        search = undefined;
        if (goal && atGoal()) reachedGoal();
      } catch (failure) {
        active.terminal = true;
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
    if (goal?.hasChanged()) reset('goal_moved');
    if (stopRequested) {
      if (!route) { stop(); return; }
      if (route.id && !route.stopSent && !route.cancelled && !route.terminal) {
        route.stopSent = true;
        control(route, 'stopRoute');
      }
    }
    if (route || !goal || !movements || planned) return;
    if (atGoal()) { reachedGoal(); return; }
    const version = epoch;
    search ??= pathfinder.getPathFromTo(movements, bot.entity.position, goal);
    const step = search.next();
    if (step.done) { planned = true; return; }
    const { result } = step.value;
    bot.emit('path_update', result);
    if (epoch !== version || !goal) return;
    if (result.status === 'partial') return;
    planned = true;
    search = undefined;
    if (result.status !== 'success') return;
    path = getRawPath(result);
    if (path.length === 0) {
      if (atGoal()) reachedGoal();
      else stop(error('NoPath', 'An empty path did not satisfy the goal'));
      return;
    }
    runRoute(path);
  });
  return pathfinder;
}
