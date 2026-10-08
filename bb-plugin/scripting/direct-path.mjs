import { Vec3 } from 'vec3';
import Move from 'mineflayer-pathfinder/lib/move.js';

// Candidate geometry, never a replacement for native trajectory validation.
// Native execution keeps its center within .2 of this line. The extra .1
// covers our <=.125 sampling interval and floating-point boundary differences.
const margin = 0.3;
const xyz = p => ({ x: p.x, y: p.y, z: p.z });
const finite = p => p && ['x', 'y', 'z'].every(k => Number.isFinite(p[k]));

export function logicalPosition(movements, position) {
  const p = new Vec3(Math.floor(position.x), Math.floor(position.y), Math.floor(position.z));
  const block = movements.bot.blockAt(p, false);
  if (position.y - p.y > 0.001 && block?.shapes.some(s =>
    Math.abs(p.y + s[4] - position.y) < 0.001 &&
    position.x - p.x >= s[0] && position.x - p.x <= s[3] &&
    position.z - p.z >= s[2] && position.z - p.z <= s[5])) p.y++;
  return p;
}

/** A bounded, edit-free straight corridor permitted by this exact Movements. */
export function directCandidate(movements, from, to, { jump = false, pursuitEntity = null } = {}) {
  if (!finite(from) || !finite(to)) return null;
  const bot = movements.bot, native = bot.nativeBody;
  if (native && native.physics !== 'native-ground-post-tick') return null;
  const { width, height } = movements._geometry();
  const caps = movements._capabilities();
  const distance = Math.hypot(to.x - from.x, to.z - from.z);
  if (distance < 0.05 || distance > 8 || Math.abs(to.y - from.y) > 1 || width > 4 || height > 8) return null;
  jump = jump && caps.canJump;
  const rise = jump ? Math.min(4, caps.jumpHeight) : caps.stepHeight;
  const minY = Math.min(from.y, to.y) - 0.125;
  const maxY = Math.max(from.y, to.y) + (jump ? Math.min(4, caps.jumpHeight) : caps.stepHeight) + 0.125;
  const cache = new Map();
  function block(x, y, z) {
    const key = `${x},${y},${z}`;
    if (!cache.has(key)) cache.set(key, bot.blockAt(new Vec3(x, y, z), false));
    return cache.get(key);
  }
  // Free pursuit may approach the target within the same coarse collision-index
  // cell. Keep its actual box (and avoided-entity policy), not that cell, as the
  // boundary. Other indexed entities and caller-supplied entries remain blocked.
  const approach = pursuitEntity && !movements.entitiesToAvoid.has(pursuitEntity.name) &&
    Number.isFinite(pursuitEntity.width) && Number.isFinite(pursuitEntity.height) ? pursuitEntity : null;
  function targetInCell(x, y, z) {
    if (!approach || movements.passableEntities.has(approach.name)) return 0;
    const p = approach.position, r = approach.width / 2;
    return x >= Math.floor(p.x - r) && x < Math.ceil(p.x + r) &&
      z >= Math.floor(p.z - r) && z < Math.ceil(p.z + r) &&
      y >= Math.floor(p.y) && y < Math.ceil(p.y + approach.height) ? 1 : 0;
  }
  const checked = new Set();
  function policy(x, y, z) {
    const key = `${x},${y},${z}`;
    if (checked.has(key)) return true;
    checked.add(key);
    const b = block(x, y, z);
    return !!b && b.stateId >= 0 && !movements.blocksToAvoid.has(b.type) &&
      !movements.liquids.has(b.type) && !movements.climbables.has(b.type) &&
      movements.exclusionStep(b) === 0 && movements.getNumEntitiesAt(b.position, 0, 0, 0) <= targetInCell(x, y, z);
  }
  const half = width / 2;
  const start = logicalPosition(movements, from);
  let previous = new Move(start.x, start.y, start.z, movements.countScaffoldingItems(), 0);
  let lastSupport = from.y;
  const steps = Math.ceil(distance / 0.125);
  for (let i = 0; i <= steps; i++) {
    const t = i / steps, x = from.x + (to.x - from.x) * t, z = from.z + (to.z - from.z) * t;
    if (approach) {
      const p = approach.position, radius = half + approach.width / 2 + 0.2625;
      if (Math.abs(x - p.x) < radius && Math.abs(z - p.z) < radius &&
          maxY + height > p.y && minY < p.y + approach.height) return null;
    }
    // The entire possible native arc is surveyed for policy, entities and loading.
    for (let bx = Math.floor(x - half - margin); bx <= Math.floor(x + half + margin); bx++)
      for (let bz = Math.floor(z - half - margin); bz <= Math.floor(z + half + margin); bz++)
        for (let by = Math.floor(minY - 1); by < Math.ceil(maxY + height); by++)
          if (!policy(bx, by, bz)) return null;
    let support = -Infinity, centerSupport = -Infinity;
    const shapes = [];
    for (let bx = Math.floor(x - half); bx <= Math.floor(x + half); bx++)
      for (let bz = Math.floor(z - half); bz <= Math.floor(z + half); bz++)
        for (let by = Math.floor(minY - 1); by < Math.ceil(maxY + height); by++) {
          const b = block(bx, by, bz);
          if (!b) return null;
          for (const s of b.shapes) {
            if (bx + s[3] <= x - half + 1e-7 || bx + s[0] >= x + half - 1e-7 ||
              bz + s[5] <= z - half + 1e-7 || bz + s[2] >= z + half - 1e-7) continue;
            const bottom = by + s[1], top = by + s[4];
            shapes.push([bottom, top]);
            if (top <= lastSupport + rise + 1e-6 && top >= minY - 1) {
              support = Math.max(support, top);
              if (x >= bx + s[0] && x < bx + s[3] && z >= bz + s[2] && z < bz + s[5])
                centerSupport = Math.max(centerSupport, top);
            }
          }
        }
    if (support === -Infinity || support < minY) {
      if (!jump || i === steps) return null;
      // A gap must be authorized by an actual parkour neighbor at its landing.
      continue;
    }
    if (support > maxY || shapes.some(([bottom, top]) => top > support + 1e-6 && bottom < support + height - 1e-6)) return null;
    lastSupport = support;
    if (centerSupport < minY) continue;
    const cell = new Vec3(Math.floor(x), Math.ceil(centerSupport - 1e-6), Math.floor(z));
    if (cell.x !== previous.x || cell.y !== previous.y || cell.z !== previous.z) {
      const next = movements.getNeighbors(previous).find(n => n.x === cell.x && n.y === cell.y && n.z === cell.z &&
        n.toBreak.length === 0 && n.toPlace.length === 0 && (jump || !n.parkour));
      if (!next) return null;
      previous = next;
    }
    if (i === steps && Math.abs(support - to.y) > 0.001) return null;
  }
  const end = logicalPosition(movements, to);
  if (previous.x !== end.x || previous.y !== end.y || previous.z !== end.z) return null;
  return { ...xyz(to), from: xyz(from), jump, minY, maxY };
}

export function directNode(movements, direct) {
  return { ...xyz(logicalPosition(movements, direct)), toBreak: [], toPlace: [], direct };
}
