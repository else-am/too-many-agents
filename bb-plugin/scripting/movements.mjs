// Adapted from mineflayer-pathfinder 2.4.5 lib/movements.js (MIT).
// See movements-LICENSE.txt. Geometry describes candidate edges; Java validates execution.
import { Vec3 } from 'vec3';
import Move from 'mineflayer-pathfinder/lib/move.js';
import passableEntities from 'mineflayer-pathfinder/lib/passableEntities.json' with { type: 'json' };
import interactableBlocks from 'mineflayer-pathfinder/lib/interactable.json' with { type: 'json' };

// The only NBT operation used by the upstream planner. Avoid the Node codec loader.
function simplify(data) {
  if (data.type === 'compound') return Object.fromEntries(Object.entries(data.value).map(([k, v]) => [k, simplify(v)]));
  if (data.type === 'list') return data.value.value.map(value => simplify({ type: data.value.type, value }));
  return data.value;
}

const cardinalDirections = [
  { x: -1, z: 0 }, // West
  { x: 1, z: 0 }, // East
  { x: 0, z: -1 }, // North
  { x: 0, z: 1 } // South
]
const diagonalDirections = [
  { x: -1, z: -1 },
  { x: -1, z: 1 },
  { x: 1, z: -1 },
  { x: 1, z: 1 }
]

export class Movements {
  constructor (bot) {
    const registry = bot.registry
    this.bot = bot

    this.canDig = true
    this.digCost = 1
    this.placeCost = 1
    this.liquidCost = 1
    this.entityCost = 1

    this.dontCreateFlow = true
    this.dontMineUnderFallingBlock = true
    this.allow1by1towers = true
    this.allowFreeMotion = false
    this.allowParkour = true
    this.allowSprinting = true
    this.allowEntityDetection = true

    this.entitiesToAvoid = new Set()
    this.passableEntities = new Set(passableEntities)
    this.interactableBlocks = new Set(interactableBlocks)

    this.blocksCantBreak = new Set()
    this.blocksCantBreak.add(registry.blocksByName.chest.id)

    registry.blocksArray.forEach(block => {
      if (block.diggable) return
      this.blocksCantBreak.add(block.id)
    })

    this.blocksToAvoid = new Set()
    this.blocksToAvoid.add(registry.blocksByName.fire.id)
    if (registry.blocksByName.cobweb) this.blocksToAvoid.add(registry.blocksByName.cobweb.id)
    if (registry.blocksByName.web) this.blocksToAvoid.add(registry.blocksByName.web.id)
    this.blocksToAvoid.add(registry.blocksByName.lava.id)

    this.liquids = new Set()
    this.liquids.add(registry.blocksByName.water.id)
    this.liquids.add(registry.blocksByName.lava.id)

    this.gravityBlocks = new Set()
    this.gravityBlocks.add(registry.blocksByName.sand.id)
    this.gravityBlocks.add(registry.blocksByName.gravel.id)

    this.climbables = new Set()
    this.climbables.add(registry.blocksByName.ladder.id)
    // this.climbables.add(registry.blocksByName.vine.id)
    this.emptyBlocks = new Set()

    this.replaceables = new Set()
    this.replaceables.add(registry.blocksByName.air.id)
    if (registry.blocksByName.cave_air) this.replaceables.add(registry.blocksByName.cave_air.id)
    if (registry.blocksByName.void_air) this.replaceables.add(registry.blocksByName.void_air.id)
    this.replaceables.add(registry.blocksByName.water.id)
    this.replaceables.add(registry.blocksByName.lava.id)

    this.scafoldingBlocks = []
    this.scafoldingBlocks.push(registry.itemsByName.dirt.id)
    this.scafoldingBlocks.push(registry.itemsByName.cobblestone.id)

    this.fences = new Set()
    this.carpets = new Set()
    this.openable = new Set()
    registry.blocksArray.forEach(definition => {
      const shapesId = registry.blockCollisionShapes?.blocks[definition.name]
      const shapes = shapesId === undefined ? definition.shapes
        : registry.blockCollisionShapes.shapes[Array.isArray(shapesId) ? shapesId[0] : shapesId]
      if (!shapes) throw new Error(`Missing collision shapes for ${definition.name}`)
      const block = { type: definition.id, shapes }
      if (block.shapes.length > 0) {
        // Fences or any block taller than 1, they will be considered as non-physical to avoid
        // trying to walk on them
        if (block.shapes[0][4] > 1) this.fences.add(block.type)
        // Carpets or any blocks smaller than 0.1, they will be considered as safe to walk in
        if (block.shapes[0][4] < 0.1) this.carpets.add(block.type)
      } else if (block.shapes.length === 0) {
        this.emptyBlocks.add(block.type)
      }
    })
    registry.blocksArray.forEach(block => {
      if (this.interactableBlocks.has(block.name) && block.name.toLowerCase().includes('gate') && !block.name.toLowerCase().includes('iron')) {
        this.openable.add(block.id)
      }
    })

    this.canOpenDoors = false // Upstream default; openable contains wooden fence gates.

    this.exclusionAreasStep = []
    this.exclusionAreasBreak = []
    this.exclusionAreasPlace = []

    this.maxDropDown = 4
    this.infiniteLiquidDropdownDistance = true

    this.entityIntersections = {}
  }

  _geometry () {
    const width = this.bot.entity.width === undefined ? 0.6 : this.bot.entity.width
    const height = this.bot.entity.height === undefined ? 1.8 : this.bot.entity.height
    if (!Number.isFinite(width) || width <= 0 || !Number.isFinite(height) || height <= 0) {
      throw new Error('Body width and height must be finite positive numbers')
    }
    return { width, height }
  }

  _capabilities () {
    const native = this.bot.nativeBody
    if (native === undefined) return { stepHeight: 0.6, jumpHeight: 1.2, canJump: true, canSwim: true,
      maxJumpDistance: 2, maxSprintJumpDistance: 4 }
    const distance = key => Number.isFinite(native?.[key]) && native[key] >= 0 ? native[key] : 0
    return { stepHeight: distance('stepHeight'), jumpHeight: distance('jumpHeight'),
      canJump: native?.canJump === true, canSwim: native?.canSwim === true,
      maxJumpDistance: distance('maxJumpDistance'), maxSprintJumpDistance: distance('maxSprintJumpDistance') }
  }

  _canRise (height) {
    const caps = this._capabilities()
    return height <= caps.stepHeight + 1e-7 || (caps.canJump && height <= caps.jumpHeight + 1e-7)
  }

  _feetY (node) {
    if (this.bot.nativeBody?.locomotion === 'submerged') return node.y + this.bot.nativeBody.swimTargetYOffset
    const block = this.getBlock(node, 0, 0, 0)
    const below = this.getBlock(node, 0, -1, 0)
    if (this.carpets.has(block.type) && block.physical) return block.height
    return block.liquid || block.climbable || !below.physical ? node.y : below.height
  }

  // Check every block touched by the body's volume, including partial block shapes.
  // Feet touching a slab and sides touching a wall are not intersections.
  _volumeCost (x, z, minY, maxY, toBreak, canBreak = true, ignore = []) {
    const { width } = this._geometry()
    const minX = x + 0.5 - width / 2, maxX = x + 0.5 + width / 2
    const minZ = z + 0.5 - width / 2, maxZ = z + 0.5 + width / 2
    const epsilon = 1e-7
    const { canSwim } = this._capabilities()
    let cost = 0
    for (let y = Math.ceil(maxY - epsilon) - 1; y >= Math.floor(minY + epsilon) - 1; y--) {
      for (let bx = Math.floor(minX + epsilon); bx < Math.ceil(maxX - epsilon); bx++) {
        for (let bz = Math.floor(minZ + epsilon); bz < Math.ceil(maxZ - epsilon); bz++) {
          const b = this.getBlock(new Vec3(bx, y, bz), 0, 0, 0)
          if (!b.position) return 100
          if (ignore.some(p => p.x === bx && p.y === y && p.z === bz)) continue
          const intersects = b.shapes.some(s => bx + s[0] < maxX - epsilon && bx + s[3] > minX + epsilon &&
            y + s[1] < maxY - epsilon && y + s[4] > minY + epsilon &&
            bz + s[2] < maxZ - epsilon && bz + s[5] > minZ + epsilon)
          // Fences/walls can protrude above the cell below this volume.
          if (y < Math.floor(minY + epsilon) && !intersects) continue
          if (b.liquid && !canSwim) return 100
          // Safe volumes still carry exclusions/entity costs. A solid shape below
          // the feet (e.g. the supporting slab) does not require a dig.
          if (!b.safe && b.shapes.length && !intersects) continue
          if (!canBreak && !b.safe) return 100
          cost += this.safeOrBreak(b, toBreak)
          if (cost >= 100) return cost
        }
      }
    }
    return cost
  }

  _clearance (node, feetY, toBreak, canBreak = true, ignore = []) {
    return this._volumeCost(node.x, node.z, feetY, feetY + this._geometry().height, toBreak, canBreak, ignore)
  }

  _liftClearance (node, rise, toBreak) {
    if (rise <= 0) return 0
    const oldTop = this._feetY(node) + this._geometry().height
    return this._volumeCost(node.x, node.z, oldTop, oldTop + rise, toBreak)
  }

  _fits (node, feetY = this._feetY(node), extraHeight = 0) {
    return this._volumeCost(node.x, node.z, feetY, feetY + this._geometry().height + extraHeight, [], false) < 100
  }

  exclusionPlace (block) {
    if (this.exclusionAreasPlace.length === 0) return 0
    let weight = 0
    for (const a of this.exclusionAreasPlace) {
      weight += a(block)
    }
    return weight
  }

  exclusionStep (block) {
    if (this.exclusionAreasStep.length === 0) return 0
    let weight = 0
    for (const a of this.exclusionAreasStep) {
      weight += a(block)
    }
    return weight
  }

  exclusionBreak (block) {
    if (this.exclusionAreasBreak.length === 0) return 0
    let weight = 0
    for (const a of this.exclusionAreasBreak) {
      weight += a(block)
    }
    return weight
  }

  countScaffoldingItems () {
    let count = 0
    const items = this.bot.inventory.items()
    for (const id of this.scafoldingBlocks) {
      for (const j in items) {
        const item = items[j]
        if (item.type === id) count += item.count
      }
    }
    return count
  }

  getScaffoldingItem () {
    const items = this.bot.inventory.items()
    for (const id of this.scafoldingBlocks) {
      for (const j in items) {
        const item = items[j]
        if (item.type === id) return item
      }
    }
    return null
  }

  clearCollisionIndex () {
    this.entityIntersections = {}
  }

  /**
   * Finds blocks intersected by entity bounding boxes
   * and sets the number of ents intersecting in a dict.
   * Ignores entities that do not affect block placement
   */
  updateCollisionIndex () {
    for (const ent of Object.values(this.bot.entities)) {
      if (ent === this.bot.entity) { continue }

      const avoidedEnt = this.entitiesToAvoid.has(ent.name)
      if (avoidedEnt || !this.passableEntities.has(ent.name)) {
        const entSquareRadius = ent.width / 2.0
        const minY = Math.floor(ent.position.y)
        const maxY = Math.ceil(ent.position.y + ent.height)
        const minX = Math.floor(ent.position.x - entSquareRadius)
        const maxX = Math.ceil(ent.position.x + entSquareRadius)
        const minZ = Math.floor(ent.position.z - entSquareRadius)
        const maxZ = Math.ceil(ent.position.z + entSquareRadius)

        const cost = avoidedEnt ? 100 : 1

        for (let y = minY; y < maxY; y++) {
          for (let x = minX; x < maxX; x++) {
            for (let z = minZ; z < maxZ; z++) {
              this.entityIntersections[`${x},${y},${z}`] = this.entityIntersections[`${x},${y},${z}`] ?? 0
              this.entityIntersections[`${x},${y},${z}`] += cost // More ents = more weight
            }
          }
        }
      }
    }
  }

  /**
   * Gets number of entities who's bounding box intersects the node + offset
   * @param {import('vec3').Vec3} pos node position
   * @param {number} dx X axis offset
   * @param {number} dy Y axis offset
   * @param {number} dz Z axis offset
   * @returns {number} Number of entities intersecting block
   */
  getNumEntitiesAt (pos, dx, dy, dz) {
    if (this.allowEntityDetection === false) return 0
    if (!pos) return 0
    const y = pos.y + dy
    const x = pos.x + dx
    const z = pos.z + dz

    return this.entityIntersections[`${x},${y},${z}`] ?? 0
  }

  getBlock (pos, dx, dy, dz) {
    const b = pos ? this.bot.blockAt(new Vec3(pos.x + dx, pos.y + dy, pos.z + dz), false) : null
    if (!b) {
      return {
        replaceable: false,
        canFall: false,
        safe: false,
        physical: false,
        liquid: false,
        climbable: false,
        height: dy,
        openable: false
      }
    }
    b.climbable = this.climbables.has(b.type)
    b.safe = (b.boundingBox === 'empty' || b.climbable || this.carpets.has(b.type)) && !this.blocksToAvoid.has(b.type)
    b.physical = b.boundingBox === 'block' && !this.fences.has(b.type)
    b.replaceable = this.replaceables.has(b.type) && !b.physical
    b.liquid = this.liquids.has(b.type)
    b.height = pos.y + dy
    b.canFall = this.gravityBlocks.has(b.type)
    b.openable = this.openable.has(b.type)

    for (const shape of b.shapes) {
      b.height = Math.max(b.height, pos.y + dy + shape[4])
    }
    return b
  }

  /**
   * Takes into account if the block is within a break exclusion area.
   * @param {import('prismarine-block').Block} block
   * @returns
   */
  safeToBreak (block) {
    if (!this.canDig) {
      return false
    }

    if (this.dontCreateFlow) {
      // false if next to liquid
      if (this.getBlock(block.position, 0, 1, 0).liquid) return false
      if (this.getBlock(block.position, -1, 0, 0).liquid) return false
      if (this.getBlock(block.position, 1, 0, 0).liquid) return false
      if (this.getBlock(block.position, 0, 0, -1).liquid) return false
      if (this.getBlock(block.position, 0, 0, 1).liquid) return false
    }

    if (this.dontMineUnderFallingBlock) {
      // TODO: Determine if there are other blocks holding the entity up
      if (this.getBlock(block.position, 0, 1, 0).canFall || (this.getNumEntitiesAt(block.position, 0, 1, 0) > 0)) {
        return false
      }
    }

    return block.type && !this.blocksCantBreak.has(block.type) && this.exclusionBreak(block) < 100
  }

  /**
   * Takes into account if the block is within the stepExclusionAreas. And returns 100 if a block to be broken is within break exclusion areas.
   * @param {import('prismarine-block').Block} block block
   * @param {[]} toBreak
   * @returns {number}
   */
  safeOrBreak (block, toBreak) {
    let cost = 0
    cost += this.exclusionStep(block) // Is excluded so can't move or break
    cost += this.getNumEntitiesAt(block.position, 0, 0, 0) * this.entityCost
    if (block.safe) return cost
    if (!this.safeToBreak(block)) return 100 // Can't break, so can't move
    toBreak.push(block.position)

    if (block.physical) cost += this.getNumEntitiesAt(block.position, 0, 1, 0) * this.entityCost // Add entity cost if there is an entity above (a breakable block) that will fall

    const tool = this.bot.pathfinder.bestHarvestTool(block)
    const enchants = (tool && tool.nbt) ? simplify(tool.nbt).Enchantments : []
    const effects = this.bot.entity.effects
    const digTime = block.digTime(tool ? tool.type : null, false, false, false, enchants, effects)
    const laborCost = (1 + 3 * digTime / 1000) * this.digCost
    cost += laborCost
    return cost
  }

  getMoveJumpUp (node, dir, neighbors) {
    if (this.bot.nativeBody?.locomotion === 'submerged') return
    const destination = new Vec3(node.x + dir.x, node.y, node.z + dir.z)
    const blockC = this.getBlock(node, dir.x, 0, dir.z)

    let cost = 2 // move cost (move+jump)
    const toBreak = []
    const toPlace = []

    if (!blockC.physical) {
      if (node.remainingBlocks === 0) return // not enough blocks to place

      if (this.getNumEntitiesAt(blockC.position, 0, 0, 0) > 0) return // Check for any entities in the way of a block placement

      const blockD = this.getBlock(node, dir.x, -1, dir.z)
      if (!blockD.physical) {
        if (node.remainingBlocks === 1) return // not enough blocks to place

        if (this.getNumEntitiesAt(blockD.position, 0, 0, 0) > 0) return // Check for any entities in the way of a block placement

        if (!blockD.replaceable) {
          if (!this.safeToBreak(blockD)) return
          cost += this.exclusionBreak(blockD)
          toBreak.push(blockD.position)
        }
        cost += this.exclusionPlace(blockD)
        toPlace.push({ x: node.x, y: node.y - 1, z: node.z, dx: dir.x, dy: 0, dz: dir.z, returnPos: new Vec3(node.x, node.y, node.z) })
        cost += this.placeCost // additional cost for placing a block
      }

      if (!blockC.replaceable) {
        if (!this.safeToBreak(blockC)) return
        cost += this.exclusionBreak(blockC)
        toBreak.push(blockC.position)
      }
      cost += this.exclusionPlace(blockC)
      toPlace.push({ x: node.x + dir.x, y: node.y - 1, z: node.z + dir.z, dx: 0, dy: 1, dz: 0 })
      cost += this.placeCost // additional cost for placing a block

      blockC.height += 1
    }

    const rise = blockC.height - this._feetY(node)
    if (!this._canRise(rise)) return
    cost += this._liftClearance(node, rise, toBreak)
    if (cost > 100) return
    cost += this._clearance(destination, blockC.height, toBreak)
    if (cost > 100) return

    neighbors.push(new Move(destination.x, destination.y + 1, destination.z, node.remainingBlocks - toPlace.length, cost, toBreak, toPlace))
  }

  getMoveForward (node, dir, neighbors) {
    if (this.bot.nativeBody?.locomotion === 'submerged') return this._getSubmergedMove(node, dir.x, 0, dir.z, neighbors)
    const destination = new Vec3(node.x + dir.x, node.y, node.z + dir.z)
    const blockC = this.getBlock(node, dir.x, 0, dir.z)
    const blockD = this.getBlock(node, dir.x, -1, dir.z)

    let cost = 1 // move cost
    cost += this.exclusionStep(blockC)

    const toBreak = []
    const toPlace = []

    if (!blockD.physical && !blockC.liquid) {
      if (node.remainingBlocks === 0) return // not enough blocks to place

      if (this.getNumEntitiesAt(blockD.position, 0, 0, 0) > 0) return // D intersects an entity hitbox

      if (!blockD.replaceable) {
        if (!this.safeToBreak(blockD)) return
        cost += this.exclusionBreak(blockD)
        toBreak.push(blockD.position)
      }
      cost += this.exclusionPlace(blockC)
      toPlace.push({ x: node.x, y: node.y - 1, z: node.z, dx: dir.x, dy: 0, dz: dir.z })
      cost += this.placeCost // additional cost for placing a block
    }

    // Open fence gates
    if (this.canOpenDoors && blockC.openable && blockC.shapes && blockC.shapes.length !== 0) {
      toPlace.push({ x: node.x + dir.x, y: node.y, z: node.z + dir.z, dx: 0, dy: 0, dz: 0, useOne: true }) // Indicate that a block should be used on this block not placed
    }
    const feetY = this._feetY(destination)
    const rise = feetY - this._feetY(node)
    if (!this._canRise(rise)) return
    cost += this._liftClearance(node, rise, toBreak)
    if (cost > 100) return
    cost += this._clearance(destination, feetY, toBreak, true, toPlace.filter(p => p.useOne))
    if (cost > 100) return

    if (this.getBlock(node, 0, 0, 0).liquid) cost += this.liquidCost

    neighbors.push(new Move(blockC.position.x, blockC.position.y, blockC.position.z, node.remainingBlocks - toPlace.length, cost, toBreak, toPlace))
  }

  getMoveDiagonal (node, dir, neighbors) {
    if (this.bot.nativeBody?.locomotion === 'submerged') return this._getSubmergedMove(node, dir.x, 0, dir.z, neighbors)
    let cost = Math.SQRT2 // move cost
    const toBreak = []

    const blockC = this.getBlock(node, dir.x, 0, dir.z) // Landing block or standing on block when jumping up by 1
    const y = blockC.physical ? 1 : 0

    const toBreak1 = [], toBreak2 = []
    const cost1 = this._clearance(new Vec3(node.x, node.y + y, node.z + dir.z), node.y + y, toBreak1)
    const cost2 = this._clearance(new Vec3(node.x + dir.x, node.y + y, node.z), node.y + y, toBreak2)

    if (cost1 < cost2) {
      cost += cost1
      toBreak.push(...toBreak1)
    } else {
      cost += cost2
      toBreak.push(...toBreak2)
    }
    if (cost > 100) return

    const destination = new Vec3(node.x + dir.x, node.y + y, node.z + dir.z)
    const feetY = y ? blockC.height : this._feetY(destination)
    if (!this._canRise(feetY - this._feetY(node))) return
    if (y === 0) cost += this._liftClearance(node, feetY - this._feetY(node), toBreak)
    cost += this._clearance(destination, feetY, toBreak)
    if (cost > 100) return

    if (this.getBlock(node, 0, 0, 0).liquid) cost += this.liquidCost

    const blockD = this.getBlock(node, dir.x, -1, dir.z)
    if (y === 1) { // Case jump up by 1
      const rise = blockC.height - this._feetY(node)
      if (!this._canRise(rise)) return
      cost += this._liftClearance(node, rise, toBreak)
      if (cost > 100) return
      cost += 1
      neighbors.push(new Move(blockC.position.x, blockC.position.y + 1, blockC.position.z, node.remainingBlocks, cost, toBreak))
    } else if (blockD.physical || blockC.liquid) {
      neighbors.push(new Move(blockC.position.x, blockC.position.y, blockC.position.z, node.remainingBlocks, cost, toBreak))
    } else if (this.getBlock(node, dir.x, -2, dir.z).physical || blockD.liquid) {
      if (!blockD.safe) return // don't self-immolate
      const landing = destination.offset(0, -1, 0)
      if (this._volumeCost(landing.x, landing.z, this._feetY(landing),
        this._feetY(node) + this._geometry().height, [], false, toBreak) >= 100) return
      cost += this.getNumEntitiesAt(blockC.position, 0, -1, 0) * this.entityCost
      neighbors.push(new Move(blockC.position.x, blockC.position.y - 1, blockC.position.z, node.remainingBlocks, cost, toBreak))
    }
  }

  getLandingBlock (node, dir) {
    // 2.4.5 starts at -2, skipping water immediately below the entry edge.
    // Keep its dry support scan, but enter the first liquid cell encountered.
    const below = this.getBlock(node, dir.x, -1, dir.z)
    if (below.liquid) return below.safe && this._capabilities().canSwim ? below : null
    let blockLand = this.getBlock(node, dir.x, -2, dir.z)
    while (blockLand.position && blockLand.position.y > this.bot.game.minY) {
      if (blockLand.liquid && blockLand.safe) return this._capabilities().canSwim ? blockLand : null
      if (blockLand.physical) {
        if (node.y - blockLand.position.y <= this.maxDropDown) return this.getBlock(blockLand.position, 0, 1, 0)
        return null
      }
      if (!blockLand.safe) return null
      blockLand = this.getBlock(blockLand.position, 0, -1, 0)
    }
    return null
  }

  getMoveDropDown (node, dir, neighbors) {
    if (this.bot.nativeBody?.locomotion === 'submerged') return
    const destination = new Vec3(node.x + dir.x, node.y, node.z + dir.z)
    const blockC = this.getBlock(node, dir.x, 0, dir.z)
    const blockD = this.getBlock(node, dir.x, -1, dir.z)

    let cost = 1 // move cost
    const toBreak = []
    const toPlace = []

    const blockLand = this.getLandingBlock(node, dir)
    if (!blockLand) return
    if (!this.infiniteLiquidDropdownDistance && ((node.y - blockLand.position.y) > this.maxDropDown)) return // Don't drop down into water

    cost += this._clearance(destination, this._feetY(node), toBreak)
    if (cost > 100) return
    cost += this.safeOrBreak(blockD, toBreak)
    if (cost > 100) return
    if (this._volumeCost(node.x + dir.x, node.z + dir.z, this._feetY(blockLand.position),
      this._feetY(node) + this._geometry().height, [], false, toBreak) >= 100) return

    if (blockC.liquid) return // dont go underwater

    cost += this.getNumEntitiesAt(blockLand.position, 0, 0, 0) * this.entityCost // add cost for entities

    neighbors.push(new Move(blockLand.position.x, blockLand.position.y, blockLand.position.z, node.remainingBlocks - toPlace.length, cost, toBreak, toPlace))
  }

  getMoveDown (node, neighbors) {
    if (this.bot.nativeBody?.locomotion === 'submerged') return this._getSubmergedMove(node, 0, -1, 0, neighbors)
    if (this.getBlock(node, 0, 0, 0).liquid) {
      this._getMoveSwim(node, -1, neighbors)
      return
    }
    const block0 = this.getBlock(node, 0, -1, 0)

    let cost = 1 // move cost
    const toBreak = []
    const toPlace = []

    const blockLand = this.getLandingBlock(node, { x: 0, z: 0 })
    if (!blockLand) return

    cost += this.safeOrBreak(block0, toBreak)
    if (cost > 100) return

    if (this._volumeCost(node.x, node.z, this._feetY(blockLand.position),
      this._feetY(node) + this._geometry().height, [], false, toBreak) >= 100) return

    cost += this.getNumEntitiesAt(blockLand.position, 0, 0, 0) * this.entityCost // add cost for entities

    neighbors.push(new Move(blockLand.position.x, blockLand.position.y, blockLand.position.z, node.remainingBlocks - toPlace.length, cost, toBreak, toPlace))
  }

  getMoveUp (node, neighbors) {
    if (this.bot.nativeBody?.locomotion === 'submerged') return this._getSubmergedMove(node, 0, 1, 0, neighbors)
    const block1 = this.getBlock(node, 0, 0, 0)
    if (block1.liquid) {
      this._getMoveSwim(node, 1, neighbors)
      return
    }
    if (this.getNumEntitiesAt(node, 0, 0, 0) > 0) return // an entity (besides the player) is blocking the building area

    let cost = 1 // move cost
    const toBreak = []
    const toPlace = []
    cost += this._liftClearance(node, node.y + 1 - this._feetY(node), toBreak)
    if (cost > 100) return

    if (!block1.climbable) {
      const caps = this._capabilities()
      if (!caps.canJump || caps.jumpHeight < node.y + 1 - this._feetY(node)) return
      if (!this.allow1by1towers || node.remainingBlocks === 0) return // not enough blocks to place

      if (!block1.replaceable) {
        if (!this.safeToBreak(block1)) return
        toBreak.push(block1.position)
      }

      const block0 = this.getBlock(node, 0, -1, 0)
      if (block0.physical && block0.height - node.y < -0.2) return // cannot jump-place from a half block

      cost += this.exclusionPlace(block1)
      toPlace.push({ x: node.x, y: node.y - 1, z: node.z, dx: 0, dy: 1, dz: 0, jump: true })
      cost += this.placeCost // additional cost for placing a block
    }

    if (cost > 100) return

    neighbors.push(new Move(node.x, node.y + 1, node.z, node.remainingBlocks - toPlace.length, cost, toBreak, toPlace))
  }

  // Deliberate 2.4.5 correction: its liquid guards omit vertical swim edges.
  // Both endpoints must be liquid; this grants neither flight nor a dry jump.
  _getMoveSwim (node, dy, neighbors) {
    const from = this.getBlock(node, 0, 0, 0)
    const to = this.getBlock(node, 0, dy, 0)
    if (!this._capabilities().canSwim || !from.liquid || !from.safe || !to.liquid || !to.safe) return
    const toBreak = []
    const cost = 1 + this.liquidCost + this._volumeCost(node.x, node.z,
      Math.min(node.y, to.position.y), Math.max(node.y, to.position.y) + this._geometry().height, toBreak)
    if (cost > 100) return
    neighbors.push(new Move(node.x, to.position.y, node.z, node.remainingBlocks, cost, toBreak))
  }

  // Fish targets are above the cell floor. The entire swept body and eyes
  // must stay in ordinary water; native execution rechecks actual motion.
  _getSubmergedMove (node, dx, dy, dz, neighbors) {
    if (!this._capabilities().canSwim) return
    const offset = this.bot.nativeBody.swimTargetYOffset
    if (!Number.isFinite(offset) || offset < 0 || offset >= 1) return
    const { width, height } = this._geometry()
    const eye = this.bot.entity.eyeHeight ?? height
    if (!Number.isFinite(eye) || eye < 0) return
    const minY = node.y + Math.min(0, dy) + offset
    const maxY = node.y + Math.max(0, dy) + offset + Math.max(height, eye)
    const minX = node.x + .5 + Math.min(0, dx) - width / 2
    const maxX = node.x + .5 + Math.max(0, dx) + width / 2
    const minZ = node.z + .5 + Math.min(0, dz) - width / 2
    const maxZ = node.z + .5 + Math.max(0, dz) + width / 2
    const epsilon = 1e-7
    let cost = Math.hypot(dx, dy, dz) + this.liquidCost
    for (let y = Math.floor(minY + epsilon); y < Math.ceil(maxY - epsilon); y++) {
      for (let x = Math.floor(minX + epsilon); x < Math.ceil(maxX - epsilon); x++) {
        for (let z = Math.floor(minZ + epsilon); z < Math.ceil(maxZ - epsilon); z++) {
          const b = this.getBlock(new Vec3(x, y, z), 0, 0, 0)
          if (!b.position || b.type !== this.bot.registry.blocksByName.water.id || !b.safe) return
          const level = Number(b.getProperties().level)
          if (!Number.isInteger(level) || level < 0 || level > 15) return
          const above = this.getBlock(b.position, 0, 1, 0)
          if (!above.position) return
          // LiquidBlock maps falling levels to amount 8; FlowingFluid uses
          // amount / 9 unless another water fluid occupies the cell above.
          const props = above.getProperties()
          const waterAbove = above.type === this.bot.registry.blocksByName.water.id ||
            above.name === 'bubble_column' || props.waterlogged === true
          const fluidHeight = waterAbove ? 1 : Math.fround((level >= 8 ? 8 : 8 - level) / 9)
          if (y + fluidHeight + epsilon < Math.min(maxY, y + 1)) return
          cost += this.safeOrBreak(b, [])
          if (cost >= 100) return
        }
      }
    }
    neighbors.push(new Move(node.x + dx, node.y + dy, node.z + dz, node.remainingBlocks, cost))
  }

  // Jump up, down or forward over a 1 block gap
  getMoveParkourForward (node, dir, neighbors) {
    if (this.bot.nativeBody?.locomotion === 'submerged') return
    const caps = this._capabilities()
    if (!caps.canJump || caps.jumpHeight <= 0) return
    const block0 = this.getBlock(node, 0, -1, 0)
    const block1 = this.getBlock(node, dir.x, -1, dir.z)
    if ((block1.physical && block1.height >= block0.height) ||
      !this._fits(new Vec3(node.x + dir.x, node.y, node.z + dir.z), node.y)) return
    if (this.getBlock(node, 0, 0, 0).liquid) return // cant jump from water

    let cost = 1

    // Leaving entities at the ceiling level (along path) out for now because there are few cases where that will be important
    cost += this.getNumEntitiesAt(node, dir.x, 0, dir.z) * this.entityCost

    // If we have a block on the ceiling, we cannot jump but we can still fall
    let ceilingClear = this._fits(node, this._feetY(node), caps.jumpHeight) &&
      this._fits(new Vec3(node.x + dir.x, node.y, node.z + dir.z), node.y, caps.jumpHeight)

    // Similarly for the down path
    let floorCleared = !this.getBlock(node, dir.x, -2, dir.z).physical

    const maxD = this.allowSprinting
      ? Math.min(4, Math.max(caps.maxJumpDistance, caps.maxSprintJumpDistance))
      : Math.min(2, caps.maxJumpDistance)

    for (let d = 2; d <= maxD; d++) {
      const dx = dir.x * d
      const dz = dir.z * d
      const blockA = this.getBlock(node, dx, 2, dz)
      const blockB = this.getBlock(node, dx, 1, dz)
      const blockC = this.getBlock(node, dx, 0, dz)
      const blockD = this.getBlock(node, dx, -1, dz)

      if (blockC.safe) cost += this.getNumEntitiesAt(blockC.position, 0, 0, 0) * this.entityCost

      const levelClear = this._fits(new Vec3(node.x + dx, node.y, node.z + dz), node.y)
      if (ceilingClear && levelClear && blockD.physical) {
        cost += this.exclusionStep(blockB)
        // Forward
        neighbors.push(new Move(blockC.position.x, blockC.position.y, blockC.position.z, node.remainingBlocks, cost, [], [], true))
        break
      } else if (ceilingClear && blockC.physical) {
        // Up
        if (this._fits(new Vec3(node.x + dx, node.y + 1, node.z + dz), blockC.height) && d !== 4) { // 4 Blocks forward 1 block up is very difficult and fails often
          cost += this.exclusionStep(blockA)
          if (blockC.height - this._feetY(node) > caps.jumpHeight) break
          cost += this.getNumEntitiesAt(blockB.position, 0, 0, 0) * this.entityCost
          neighbors.push(new Move(blockB.position.x, blockB.position.y, blockB.position.z, node.remainingBlocks, cost, [], [], true))
          break
        }
      } else if ((ceilingClear || d === 2) && levelClear && blockD.safe && floorCleared) {
        // Down
        const blockE = this.getBlock(node, dx, -2, dz)
        if (blockE.physical && this._fits(new Vec3(node.x + dx, node.y - 1, node.z + dz), blockE.height)) {
          cost += this.exclusionStep(blockD)
          cost += this.getNumEntitiesAt(blockD.position, 0, 0, 0) * this.entityCost
          neighbors.push(new Move(blockD.position.x, blockD.position.y, blockD.position.z, node.remainingBlocks, cost, [], [], true))
        }
        floorCleared = floorCleared && !blockE.physical
      } else if (!levelClear) {
        break
      }

      ceilingClear = ceilingClear && this._fits(new Vec3(node.x + dx, node.y, node.z + dz), node.y, caps.jumpHeight)
    }
  }

  getNeighbors (node) {
    const neighbors = []

    // Simple moves in 4 cardinal points
    for (const i in cardinalDirections) {
      const dir = cardinalDirections[i]
      this.getMoveForward(node, dir, neighbors)
      this.getMoveJumpUp(node, dir, neighbors)
      this.getMoveDropDown(node, dir, neighbors)
      if (this.allowParkour) {
        this.getMoveParkourForward(node, dir, neighbors)
      }
    }

    // Diagonals
    for (const i in diagonalDirections) {
      const dir = diagonalDirections[i]
      this.getMoveDiagonal(node, dir, neighbors)
    }

    this.getMoveDown(node, neighbors)
    this.getMoveUp(node, neighbors)

    return neighbors
  }
}

export default Movements;
