// Selected/adapted planning code from mineflayer-pathfinder 2.4.5 (MIT).
// See planning.LICENSE. Bundle AStar's perf_hooks import with the trusted guest clock alias.
import AStar from 'mineflayer-pathfinder/lib/astar.js';
import Move from 'mineflayer-pathfinder/lib/move.js';
import { Vec3 } from 'vec3';

// Upstream only needs NBT simplification, not its Node compression/parser entrypoint.
function simplify(data) {
  if (data.type === 'compound') return Object.fromEntries(Object.entries(data.value).map(([key, value]) => [key, simplify(value)]));
  if (data.type === 'list') return data.value.value.map(value => simplify({ type: data.value.type, value }));
  return data.value;
}

function copyRecord(value) {
  return Object.assign(Object.create(Object.getPrototypeOf(value)), value);
}

// AStar retains these Moves across partial results. Neither optimization nor the
// caller consuming action arrays may change the search graph on the next resume.
function copyPath(path) {
  return path.map(node => Object.assign(copyRecord(node), {
    toBreak: node.toBreak.map(copyRecord),
    toPlace: node.toPlace.map(action => Object.assign(copyRecord(action),
      action.returnPos ? { returnPos: copyRecord(action.returnPos) } : {})),
  }));
}

// Match upstream's center-biased average of the highest faces, including slabs/stairs.
function positionOnTop(block) {
  if (!block || block.shapes.length === 0) return null;
  const point = new Vec3(0.5, 0, 0.5);
  let count = 1;
  for (const shape of block.shapes) {
    const height = shape[4];
    if (height === point.y) {
      point.x += (shape[0] + shape[3]) / 2;
      point.z += (shape[2] + shape[5]) / 2;
      count++;
    } else if (height > point.y) {
      count = 2;
      point.x = 0.5 + (shape[0] + shape[3]) / 2;
      point.y = height;
      point.z = 0.5 + (shape[2] + shape[5]) / 2;
    }
  }
  point.x /= count;
  point.z /= count;
  return block.position.plus(point);
}

/** Install only synchronous planning. The caller owns movements, execution and events. */
export function installPlanning(bot, pathfinder, { canShortcut } = {}) {
  pathfinder.thinkTimeout ??= 5000;
  pathfinder.tickTimeout ??= 40;
  pathfinder.searchRadius ??= -1;
  pathfinder.enablePathShortcut ??= false;
  pathfinder.LOSWhenPlacingBlocks ??= true;

  pathfinder.bestHarvestTool = block => {
    let fastest = Number.MAX_VALUE;
    let bestTool = null;
    for (const tool of bot.inventory.items()) {
      const enchantments = tool?.nbt ? simplify(tool.nbt).Enchantments : [];
      const time = block.digTime(tool ? tool.type : null, false, false, false, enchantments, bot.entity.effects);
      if (time < fastest) {
        fastest = time;
        bestTool = tool;
      }
    }
    return bestTool;
  };

  pathfinder.getPathTo = (movements, goal, timeout) =>
    pathfinder.getPathFromTo(movements, bot.entity.position, goal, { timeout }).next().value.result;

  pathfinder.getPathFromTo = function* (movements, startPos, goal, options = {}) {
    const optimizePath = options.optimizePath ?? true;
    const resetEntityIntersects = options.resetEntityIntersects ?? true;
    const timeout = options.timeout ?? pathfinder.thinkTimeout;
    const tickTimeout = options.tickTimeout ?? pathfinder.tickTimeout;
    const searchRadius = options.searchRadius ?? pathfinder.searchRadius;
    requireShortcutCapability(optimizePath);
    let start = options.startMove;
    if (!start) {
      const p = startPos.floored();
      const block = bot.blockAt(p);
      const offset = block && startPos.y - p.y > 0.001 && bot.entity.onGround && !movements.emptyBlocks.has(block.type) ? 1 : 0;
      start = new Move(p.x, p.y + offset, p.z, movements.countScaffoldingItems(), 0);
    }
    const origin = startPos ? new Vec3(startPos.x, startPos.y, startPos.z) : null;
    if (movements.allowEntityDetection) {
      if (resetEntityIntersects) movements.clearCollisionIndex();
      movements.updateCollisionIndex();
    }
    const astarContext = new AStar(start, movements, goal, timeout, tickTimeout, searchRadius);
    let result;
    do {
      result = astarContext.compute();
      result.path = copyPath(result.path);
      if (optimizePath) {
        requireShortcutCapability(true);
        result.path = postProcess(result.path, movements, origin, start);
      }
      yield { result, astarContext };
    } while (result.status === 'partial');
  };

  function requireShortcutCapability(optimizePath) {
    if (optimizePath && pathfinder.enablePathShortcut && typeof canShortcut !== 'function') {
      throw new Error('Path shortcuts require a synchronous body-aware canShortcut(from, to) capability');
    }
  }

  function postProcess(path, movements, origin, start) {
    const water = bot.registry.blocksByName.water.id;
    const ladder = bot.registry.blocksByName.ladder.id;
    const vine = bot.registry.blocksByName.vine.id;
    let prefixLength = 0;
    for (; prefixLength < path.length; prefixLength++) {
      const node = path[prefixLength];
      if (node.toBreak.length || node.toPlace.length) break;
      const block = bot.blockAt(new Vec3(node.x, node.y, node.z));
      if (block && (block.type === water || ((block.type === ladder || block.type === vine) &&
        prefixLength + 1 < path.length && path[prefixLength + 1].y < node.y))) {
        node.x = Math.floor(node.x) + 0.5;
        node.y = Math.floor(node.y);
        node.z = Math.floor(node.z) + 0.5;
        continue;
      }
      const position = positionOnTop(block) ?? positionOnTop(bot.blockAt(new Vec3(node.x, node.y - 1, node.z)));
      if (position) {
        node.x = position.x;
        node.y = position.y;
        node.z = position.z;
      } else {
        node.x = Math.floor(node.x) + 0.5;
        node.y -= 1;
        node.z = Math.floor(node.z) + 0.5;
      }
    }
    if (!pathfinder.enablePathShortcut || movements.exclusionAreasStep.length || prefixLength < 2) return path;
    const shortened = [];
    let anchor = origin;
    if (!anchor) {
      // startMove also accepts a plain Move-shaped record. Only shortcutting
      // needs to project that grid coordinate to a physical standing position.
      const p = new Vec3(start.x, start.y, start.z);
      const block = bot.blockAt(p);
      anchor = block && [water, ladder, vine].includes(block.type) ? p.offset(0.5, 0, 0.5)
        : positionOnTop(block) ?? positionOnTop(bot.blockAt(p.offset(0, -1, 0))) ?? p.offset(0.5, 0, 0.5);
    }
    for (let i = 1; i < prefixLength; i++) {
      const node = path[i];
      let reachable = false;
      if (Math.abs(node.y - anchor.y) <= 0.5) {
        reachable = canShortcut(anchor, node);
        if (typeof reachable !== 'boolean') throw new TypeError('canShortcut must return a boolean synchronously');
      }
      if (!reachable) {
        shortened.push(path[i - 1]);
        anchor = path[i - 1];
      }
    }
    shortened.push(path[prefixLength - 1]);
    // Once a path changes the world, later nodes still have raw planning coordinates.
    // Keep that entire suffix, including its actions, until execution replans it.
    return shortened.concat(path.slice(prefixLength));
  }
}
