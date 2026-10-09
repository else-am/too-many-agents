import { fromNotch } from './items.mjs'
import { Vec3 } from 'vec3'

export function createEntityClass (registry, { Item, ChatMessage }) {
  class Entity {
    constructor (id) {
      this.id = id
      this.position = new Vec3(0, 0, 0)
      this.velocity = new Vec3(0, 0, 0)
      this.yaw = 0
      this.pitch = 0
      this.onGround = true
      this.height = 0
      this.width = 0
      this.effects = {}
      // Keep the upstream constructor's five sparse slots; updates extend it.
      this.equipment = new Array(5)
      this.isValid = true
      this.metadata = []
      this.passengers = []
      this.vehicle = null
    }

    get heldItem () {
      return this.equipment[0]
    }

    getCustomName () {
      const name = this.metadata[2]
      if (name === undefined) {
        return null
      }
      return ChatMessage.fromNotch(name)
    }

    getDroppedItem () {
      if (this.name !== 'item' && this.name !== 'Item' && this.name !== 'item_stack') {
        return null // not a dropped item
      }
      return fromNotch(Item, this.metadata[8])
    }
  }

  // Preserve the public constructor name through bundling.
  Object.defineProperty(Entity, 'name', { value: 'Entity' })
  return Entity
}
function printMobTypeWarning () {
  (console.trace ?? console.warn ?? console.log)?.call(console, 'Warning: entity.mobType is deprecated. Use entity.displayName instead')
}
function printObjectTypeWarning () {
  (console.trace ?? console.warn ?? console.log)?.call(console, 'Warning: entity.objectType is deprecated. Use entity.displayName instead')
}
