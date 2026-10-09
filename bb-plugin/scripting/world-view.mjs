import { Vec3 } from 'vec3'

const BlockFace = {
  UNKNOWN: -999,
  BOTTOM: 0,
  TOP: 1,
  NORTH: 2,
  SOUTH: 3,
  WEST: 4,
  EAST: 5
}

// Preserve the upstream DDA ordering (ties step Z, then Y, then X), mutable
// iterator.block and callback iterator API. No world/chunk loader is included.
export class RaycastIterator {
  #visited = 1
  #shapes = 0
  constructor (pos, dir, maxDistance) {
    this.block = {
      x: Math.floor(pos.x),
      y: Math.floor(pos.y),
      z: Math.floor(pos.z),
      face: BlockFace.UNKNOWN
    }

    this.pos = pos
    this.dir = dir

    this.invDirX = (dir.x === 0) ? Number.MAX_VALUE : 1 / dir.x
    this.invDirY = (dir.y === 0) ? Number.MAX_VALUE : 1 / dir.y
    this.invDirZ = (dir.z === 0) ? Number.MAX_VALUE : 1 / dir.z

    this.stepX = Math.sign(dir.x)
    this.stepY = Math.sign(dir.y)
    this.stepZ = Math.sign(dir.z)

    this.tDeltaX = (dir.x === 0) ? Number.MAX_VALUE : Math.abs(1 / dir.x)
    this.tDeltaY = (dir.y === 0) ? Number.MAX_VALUE : Math.abs(1 / dir.y)
    this.tDeltaZ = (dir.z === 0) ? Number.MAX_VALUE : Math.abs(1 / dir.z)

    this.tMaxX = (dir.x === 0) ? Number.MAX_VALUE : Math.abs((this.block.x + (dir.x > 0 ? 1 : 0) - pos.x) / dir.x)
    this.tMaxY = (dir.y === 0) ? Number.MAX_VALUE : Math.abs((this.block.y + (dir.y > 0 ? 1 : 0) - pos.y) / dir.y)
    this.tMaxZ = (dir.z === 0) ? Number.MAX_VALUE : Math.abs((this.block.z + (dir.z > 0 ? 1 : 0) - pos.z) / dir.z)

    this.maxDistance = maxDistance
  }

  // Intersect the forward, range-limited ray with block-local collision boxes.
  // A containing box hits at the origin; face is its entry face along the ray.
  intersect (shapes, offset) {
    if (!Array.isArray(shapes)) throw new TypeError('Block shapes must be an array')
    this.#shapes += shapes.length
    if (this.#shapes > 262144) throw new RangeError('Raycast exceeds 262144 shape checks')
    if (this.stepX === 0 && this.stepY === 0 && this.stepZ === 0) return null
    const p = this.pos.minus(offset)
    const origin = [p.x, p.y, p.z]
    const direction = [this.dir.x, this.dir.y, this.dir.z]
    const positiveFaces = [BlockFace.WEST, BlockFace.BOTTOM, BlockFace.NORTH]
    const negativeFaces = [BlockFace.EAST, BlockFace.TOP, BlockFace.SOUTH]
    let nearest = Infinity
    let face = BlockFace.UNKNOWN
    for (const shape of shapes) {
      if (!Array.isArray(shape) || shape.length !== 6 || !shape.every(Number.isFinite) ||
          shape[0] > shape[3] || shape[1] > shape[4] || shape[2] > shape[5]) {
        throw new TypeError('Invalid block collision shape')
      }
      let entry = -Infinity
      let exit = Infinity
      let entryFace = BlockFace.UNKNOWN
      for (let axis = 0; axis < 3; axis++) {
        const low = shape[axis], high = shape[axis + 3], at = origin[axis], dir = direction[axis]
        if (dir === 0) {
          // Explicit parallel handling avoids 0 * Infinity and boundary NaNs.
          if (at < low || at > high) { exit = -Infinity; break }
          continue
        }
        const near = ((dir > 0 ? low : high) - at) / dir
        const far = ((dir > 0 ? high : low) - at) / dir
        if (near > entry) {
          entry = near
          entryFace = dir > 0 ? positiveFaces[axis] : negativeFaces[axis]
        }
        exit = Math.min(exit, far)
        if (entry > exit) break
      }
      const distance = Math.max(0, entry)
      if (entry > exit || exit < 0 || distance > this.maxDistance || distance >= nearest) continue
      nearest = distance
      face = entryFace
    }
    if (nearest === Infinity) return null
    return { pos: this.pos.plus(this.dir.scaled(nearest)), face }
  }

  next () {
    if (this.stepX === 0 && this.stepY === 0 && this.stepZ === 0) return null
    if (Math.min(Math.min(this.tMaxX, this.tMaxY), this.tMaxZ) > this.maxDistance) { return null }
    if (this.#visited++ >= 65536) throw new RangeError('Raycast exceeds 65536 visited cells')

    if (this.tMaxX < this.tMaxY) {
      if (this.tMaxX < this.tMaxZ) {
        this.block.x += this.stepX
        this.tMaxX += this.tDeltaX
        this.block.face = this.stepX > 0 ? BlockFace.WEST : BlockFace.EAST
      } else {
        this.block.z += this.stepZ
        this.tMaxZ += this.tDeltaZ
        this.block.face = this.stepZ > 0 ? BlockFace.NORTH : BlockFace.SOUTH
      }
    } else {
      if (this.tMaxY < this.tMaxZ) {
        this.block.y += this.stepY
        this.tMaxY += this.tDeltaY
        this.block.face = this.stepY > 0 ? BlockFace.BOTTOM : BlockFace.TOP
      } else {
        this.block.z += this.stepZ
        this.tMaxZ += this.tDeltaZ
        this.block.face = this.stepZ > 0 ? BlockFace.NORTH : BlockFace.SOUTH
      }
    }

    return this.block
  }
}

function finiteVector (vector) {
  return vector && Number.isFinite(vector.x) && Number.isFinite(vector.y) && Number.isFinite(vector.z)
}

/**
 * Supply synchronous lookup(Vec3 integerPosition) -> typed Block | null.
 * Shapes must be the Block's local collision boxes; do not replace them with a
 * guessed full cube. getBlock sets position, like WorldSync. Hits annotate the
 * supplied Block with face/intersect, so fresh lookup objects avoid stale hit
 * annotations when the native cache is reused. No observations are fabricated.
 *
 * raycast(from, direction, range, matcher = null) is synchronous. As upstream,
 * direction must be normalized for range to measure world distance; nonunit
 * input retains upstream's parametric interpretation. Finite nonnegative range
 * is required. A zero direction has no shape hit (a matcher can select its
 * starting cell). Traversal/shape budgets throw, never masquerade as a miss.
 *
 * A matcher receives (block, iterator), bypasses automatic shape testing and
 * returns the Block unchanged on truthy success. It may use iterator.intersect
 * and attach face/intersect itself. Null lookups are skipped, as upstream:
 * a hit beyond unloaded cache cells is NOT proof of unobstructed visibility.
 *
 * Corrected upstream defects: anchor first shapes to the floored cell, reject
 * behind-origin/out-of-range intersections, and handle parallel axes explicitly.
 * This retains upstream cell traversal, not native shape clipping: shapes that
 * extend into adjacent cells are tested only when their owning cell is visited.
 * Native column delivery is installed separately (columns.mjs).
 */
export function createWorldView (lookup) {
  if (typeof lookup !== 'function') throw new TypeError('World view requires a synchronous block lookup')
  return {
    getBlock (position) {
      if (!finiteVector(position)) throw new RangeError('Block position must be finite')
      const cell = position.floored()
      const block = lookup(cell)
      if (block === null) return null
      if (!block || typeof block.then === 'function') throw new TypeError('Block lookup must return Block or null synchronously')
      block.position = cell
      return block
    },
    raycast (from, direction, range, matcher = null) {
      if (!finiteVector(from) || !finiteVector(direction) || !Number.isFinite(range) || range < 0) {
        throw new RangeError('Raycast requires finite vectors and a finite nonnegative range')
      }
      const iter = new RaycastIterator(from, direction, range)
      let pos = iter.block
      while (pos) {
        const position = new Vec3(pos.x, pos.y, pos.z)
        const block = this.getBlock(position)
        if (block) {
          if (matcher) {
            if (matcher(block, iter)) return block
          } else {
            const intersect = iter.intersect(block.shapes, position)
            if (intersect) {
              block.face = intersect.face
              block.intersect = intersect.pos
              return block
            }
          }
        }
        pos = iter.next()
      }
      return null
    }
  }
}
