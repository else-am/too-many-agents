// Read-only Mineflayer 4.39.0 queries; attribution: world-queries.LICENSE.
import { Vec3 } from 'vec3'
import { createWorldView } from './world-view.mjs'

const MAX_SEARCH_CELLS = 262144
const MAX_RESULTS = 65536
const MAX_RAY_DISTANCE = 4096
const MAX_ENTITIES = 65536

function finitePosition (position) {
  if (!position || ![position.x, position.y, position.z].every(Number.isFinite)) {
    throw new RangeError('World query requires a finite position')
  }
}

function range (distance) {
  if (!Number.isFinite(distance) || distance < 0 || distance > MAX_RAY_DISTANCE) {
    throw new RangeError('World query ray distance must be between 0 and 4096')
  }
  return distance
}

function eyePosition (entity) {
  finitePosition(entity?.eyePosition)
  return new Vec3(entity.eyePosition.x, entity.eyePosition.y, entity.eyePosition.z)
}

function viewDirection (entity) {
  const { pitch, yaw } = entity
  if (!Number.isFinite(pitch) || !Number.isFinite(yaw)) throw new RangeError('World query requires finite yaw and pitch')
  return new Vec3(-Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch))
}

// First-visit ordering of prismarine-world's OctahedronIterator: shell, signs,
// absolute Y ascending, absolute X descending. Zero first appears on the negative
// side. Generate only intersecting sections, without iterating empty distant shells.
function sectionOrder (a, b) {
  for (let i = 0; i < a.order.length; i++) {
    const difference = a.order[i] - b.order[i]
    if (difference) return difference
  }
  return 0
}

/**
 * Install synchronous queries over the current observed Block/Entity cache.
 * getLoadedBounds(), if provided, returns integer Vec3 {min,max}, min inclusive,
 * max exclusive, covering ALL cached cells; null means no observed cells.
 * It may contain holes. blockAt still decides whether a cell is known.
 *
 * Searches return only observed matches, not a completeness claim about the
 * unloaded world. Callbacks receive actual positioned Blocks, never palette
 * prototypes or null; extra info is supplied by bot.blockAt(pos, extraInfos).
 * All candidate sections are searched so nearest results cannot be skipped by
 * upstream's premature shell stop. Equal-distance ties retain upstream order.
 *
 * Rays fail on unknown cells before the result. Geometry uses world-view's
 * bounded collision-shape raycast, including its documented upstream fixes.
 * Eye origin is observed eyeHeight, not a guessed player size or body height.
 */
export function installWorldQueries (bot, { getBlock, getLoadedBounds } = {}) {
  const observedWorld = createWorldView(position => {
    const block = getBlock(position)
    if (block === null) throw new Error('World query entered an unknown block cell')
    return block
  })

  bot.findBlocks = options => {
    const point = options.point || bot.entity.position
    finitePosition(point)
    const center = point.floored()
    // Retain pinned falsy-zero defaults, but reject nonfinite/unsafe requests.
    const distance = options.maxDistance ?? 16
    const requestedCount = options.count ?? 1
    if (!Number.isFinite(distance) || distance < 0 || !Number.isInteger(requestedCount) || requestedCount < 0 || requestedCount > MAX_RESULTS) {
      throw new RangeError('Invalid world search distance or count (maximum 65536 results)')
    }
    const maxDistance = distance || 16
    const count = requestedCount || 1
    const extra = options.useExtraInfo || false
    if (typeof extra !== 'boolean' && typeof extra !== 'function') throw new TypeError('useExtraInfo must be boolean or function')
    const matching = options.matching
    const types = Array.isArray(matching) ? matching : [matching]
    const matcher = typeof matching === 'function' ? matching : block => types.includes(block.type)
    let min = center.offset(-maxDistance, -maxDistance, -maxDistance)
    let max = center.offset(maxDistance, maxDistance, maxDistance)
    min = new Vec3(Math.ceil(min.x), Math.ceil(min.y), Math.ceil(min.z))
    max = max.floored().offset(1, 1, 1)
    if (getLoadedBounds) {
      const bounds = getLoadedBounds()
      if (bounds === null) return []
      finitePosition(bounds?.min)
      finitePosition(bounds?.max)
      if (![bounds.min.x, bounds.min.y, bounds.min.z, bounds.max.x, bounds.max.y, bounds.max.z].every(Number.isSafeInteger)) {
        throw new RangeError('Loaded bounds must use safe integer coordinates')
      }
      min = new Vec3(Math.max(min.x, bounds.min.x), Math.max(min.y, bounds.min.y), Math.max(min.z, bounds.min.z))
      max = new Vec3(Math.min(max.x, bounds.max.x), Math.min(max.y, bounds.max.y), Math.min(max.z, bounds.max.z))
    }
    if (![center.x, center.y, center.z, min.x, min.y, min.z, max.x, max.y, max.z].every(Number.isSafeInteger)) {
      throw new RangeError('World search coordinates must be safe integers')
    }
    if (min.x >= max.x || min.y >= max.y || min.z >= max.z) return []
    if ((max.x - min.x) * (max.y - min.y) * (max.z - min.z) > MAX_SEARCH_CELLS) {
      throw new RangeError('World search exceeds 262144 candidate cells')
    }
    const start = center.scaled(1 / 16).floored()
    const sections = []
    for (let x = Math.floor(min.x / 16); x <= Math.floor((max.x - 1) / 16); x++) {
      for (let y = Math.floor(min.y / 16); y <= Math.floor((max.y - 1) / 16); y++) {
        for (let z = Math.floor(min.z / 16); z <= Math.floor((max.z - 1) / 16); z++) {
          const dx = x - start.x, dy = y - start.y, dz = z - start.z
          sections.push({ x, y, z, order: [Math.abs(dx) + Math.abs(dy) + Math.abs(dz), +(dx > 0), +(dy > 0), +(dz > 0), Math.abs(dy), -Math.abs(dx)] })
        }
      }
    }
    sections.sort(sectionOrder)
    const found = []
    for (const section of sections) {
      for (let x = Math.max(min.x, section.x * 16); x < Math.min(max.x, section.x * 16 + 16); x++) {
        for (let y = Math.max(min.y, section.y * 16); y < Math.min(max.y, section.y * 16 + 16); y++) {
          for (let z = Math.max(min.z, section.z * 16); z < Math.min(max.z, section.z * 16 + 16); z++) {
            const position = new Vec3(x, y, z)
            if (position.distanceTo(center) > maxDistance) continue
            const block = bot.blockAt(position, extra !== false)
            if (block === null) continue
            if (matcher(block) && (typeof extra !== 'function' || extra(block))) found.push(position)
          }
        }
      }
    }
    found.sort((a, b) => a.distanceTo(center) - b.distanceTo(center))
    return found.slice(0, count)
  }

  bot.findBlock = options => {
    const positions = bot.findBlocks(options)
    return positions.length ? bot.blockAt(positions[0]) : null
  }

  bot.blockAtCursor = (maxDistance = 256, matcher = null, entity = bot.entity) => {
    range(maxDistance)
    const eye = eyePosition(entity)
    const direction = viewDirection(entity)
    if (matcher !== null && typeof matcher !== 'function') throw new TypeError('Ray matcher must be a function or null')
    return observedWorld.raycast(eye, direction, maxDistance, matcher)
  }


  bot.canSeeBlock = block => {
    finitePosition(block?.position)
    const eye = eyePosition(bot.entity)
    const target = block.position.offset(0.5, 0.5, 0.5)
    const distance = range(eye.distanceTo(target))
    const direction = target.minus(eye).normalize()
    const hit = observedWorld.raycast(eye, direction, distance, (input, iterator) => {
      return !!iterator.intersect(input.shapes, input.position) || block.position.equals(input.position)
    })
    return !!hit && hit.position.equals(block.position)
  }

  bot.entityAtCursor = (maxDistance = 3.5) => {
    range(maxDistance)
    const eye = eyePosition(bot.entity)
    const direction = viewDirection(bot.entity)
    const entities = Object.values(bot.entities)
    if (entities.length > MAX_ENTITIES) throw new RangeError('World query exceeds 65536 entities')
    // Reuse the existing ray/AABB intersection with the actual eye and range.
    let iterator
    observedWorld.raycast(eye, direction, maxDistance, (block, iter) => { iterator = iter; return true })
    let target = null, nearest = maxDistance
    for (const entity of entities) {
      if (entity === bot.entity || entity.id === bot.entity.id || entity.type === 'object') continue
      finitePosition(entity.position)
      if (!Number.isFinite(entity.width) || entity.width < 0 || !Number.isFinite(entity.height) || entity.height < 0) {
        throw new RangeError('World query requires observed entity width and height')
      }
      const half = entity.width / 2
      const hit = iterator.intersect([[-half, 0, -half, half, entity.height, half]], entity.position)
      if (!hit) continue
      const distance = eye.distanceTo(hit.pos)
      if (distance < nearest || target === null && distance <= nearest) {
        target = entity
        nearest = distance
      }
    }
    if (target === null) return null
    // Unknown cells beyond the target cannot occlude it; do not inspect them.
    const block = observedWorld.raycast(eye, direction, nearest)
    return block === null ? target : null
  }
}
