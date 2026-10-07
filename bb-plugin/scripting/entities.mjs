// PC 1.21.1 prismarine-entity 2.6.0 object contract; see entities.LICENSE.
// registry is reserved for the shared factory integration signature. No tables
// are needed here: dropped-item metadata is index 8, custom-name metadata is 2.
// Inject the guest's existing Item and ChatMessage classes to preserve identity.
// Metadata must contain decoded protocol values: anonymous NBT for names and
// decoded Slot data for dropped items, not already-created Item/Chat objects.
//
// Constructor defaults follow source, including five sparse equipment slots;
// PC equipment updates can extend it to six (0 main hand, 1 offhand, 2 feet,
// 3 legs, 4 torso, 5 head). Undefined name returns null; null name still throws
// through upstream fromNotch. Health/food/type/etc are native hydration fields,
// not invented defaults. Deprecated aliases warn using the available console.
// No native actions or native event generation occurs in this class.

import { Vec3 } from 'vec3'
import { EventEmitter } from 'events'

export function createEntityClass (registry, { Item, ChatMessage }) {
  class Entity extends EventEmitter {
    constructor (id) {
      super()
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

    get mobType () {
      printMobTypeWarning()
      return this.displayName
    }

    set mobType (name) {
      printMobTypeWarning()
      this.displayName = name
    }

    get objectType () {
      printObjectTypeWarning()
      return this.displayName
    }

    set objectType (name) {
      printObjectTypeWarning()
      this.displayName = name
    }

    get heldItem () {
      return this.equipment[0]
    }

    setEquipment (index, item) {
      this.equipment[index] = item
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
      return Item.fromNotch(this.metadata[8])
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
