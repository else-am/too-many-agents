// PC Minecraft 1.21.1 adaptation of prismarine-windows 2.10.0.
// Upstream attribution and license declaration: windows.LICENSE.
import { EventEmitter } from 'events'

// No host assertions or registry loader enters the guest bundle.
function assert (value, message) {
  if (value) return
  const error = new Error(message)
  error.name = 'AssertionError'
  error.code = 'ERR_ASSERTION'
  error.actual = value
  error.expected = true
  error.operator = '=='
  error.generatedMessage = false
  throw error
}
assert.ok = assert
assert.notStrictEqual = (actual, expected) => {
  if (actual !== expected) return
  const error = new Error('Expected "actual" to be strictly unequal to: null')
  error.name = 'AssertionError'
  error.code = 'ERR_ASSERTION'
  error.actual = actual
  error.expected = expected
  error.operator = 'notStrictEqual'
  error.generatedMessage = true
  throw error
}

// Observed native slots and read-only queries. Mutations go through inventory.
const emitters = new WeakMap();
export function emitWindow(window, event, ...args) {
  emitters.get(window).emit(event, ...args);
}
export function createWindowFactory () {
  class Window {
    #listeners = new WeakMap();
    #listen(method, event, listener) {
      if (typeof listener !== 'function') throw new TypeError('Listener must be a function');
      let wrapped = this.#listeners.get(listener);
      if (!wrapped) { wrapped = (...args) => listener.apply(this, args); this.#listeners.set(listener, wrapped); }
      emitters.get(this)[method](event, wrapped);
      return this;
    }
    on(event, listener) { return this.#listen('on', event, listener); }
    once(event, listener) { return this.#listen('once', event, listener); }
    off(event, listener) { return this.#listen('off', event, listener); }
    constructor (type, title, slotCount,
      inventorySlotsRange = { start: 27, end: 62 },
      craftingResultSlot = -1) {
      emitters.set(this, new EventEmitter())
      this.type = type
      this.title = title
      this.slots = new Array(slotCount).fill(null)
      this.inventoryStart = inventorySlotsRange.start
      this.inventoryEnd = inventorySlotsRange.end + 1
      this.hotbarStart = Math.max(this.inventoryStart, this.inventoryEnd - 9)
      this.craftingResultSlot = craftingResultSlot
      // in vanilla client, this is the item you are holding with the
      // mouse cursor
      this.selectedItem = null
    }


    #findItemRange (start, end, itemType, metadata, notFull, nbt, withoutCraftResultSlot = false) {
      assert.notStrictEqual(itemType, null)
      for (let i = start; i < end; ++i) {
        const item = this.slots[i]
        if (
          item && itemType === item.type &&
          (metadata == null || metadata === item.metadata) &&
          (!notFull || item.count < item.stackSize) &&
          (nbt == null || JSON.stringify(nbt) === JSON.stringify(item.nbt)) &&
          !(item.slot === this.craftingResultSlot && withoutCraftResultSlot)) {
          return item
        }
      }
      return null
    }

    #findItemRangeName (start, end, itemName, metadata, notFull) {
      assert.notStrictEqual(itemName, null)
      for (let i = start; i < end; ++i) {
        const item = this.slots[i]
        if (item && itemName === item.name &&
          (metadata == null || metadata === item.metadata) &&
          (!notFull || item.count < item.stackSize)) {
          return item
        }
      }
      return null
    }

    findInventoryItem (item, metadata, notFull) {
      assert(typeof item === 'number' || typeof item === 'string' || typeof item === 'undefined', 'No valid type given')
      return typeof item === 'number'
        ? this.#findItemRange(this.inventoryStart, this.inventoryEnd, item, metadata, notFull)
        : this.#findItemRangeName(this.inventoryStart, this.inventoryEnd, item, metadata, notFull)
    }

    findContainerItem (item, metadata, notFull) {
      assert(typeof item === 'number' || typeof item === 'string' || typeof item === 'undefined', 'No valid type given')
      return typeof item === 'number'
        ? this.#findItemRange(0, this.inventoryStart, item, metadata, notFull)
        : this.#findItemRangeName(0, this.inventoryStart, item, metadata, notFull)
    }

    #firstEmptySlotRange (start, end) {
      for (let i = start; i < end; ++i) {
        if (this.slots[i] === null) return i
      }
      return null
    }

    firstEmptyHotbarSlot () {
      return this.#firstEmptySlotRange(this.hotbarStart, this.inventoryEnd)
    }

    firstEmptyContainerSlot () {
      return this.#firstEmptySlotRange(0, this.inventoryStart)
    }

    firstEmptyInventorySlot (hotbarFirst = true) {
      if (hotbarFirst) {
        const slot = this.firstEmptyHotbarSlot()
        if (slot !== null) return slot
      }
      return this.#firstEmptySlotRange(this.inventoryStart, this.inventoryEnd)
    }

    #countRange (start, end, itemType, metadata) {
      let sum = 0
      for (let i = start; i < end; ++i) {
        const item = this.slots[i]
        if (item && itemType === item.type &&
          (metadata == null || item.metadata === metadata)) {
          sum += item.count
        }
      }
      return sum
    }

    #itemsRange (start, end) {
      const results = []
      for (let i = start; i < end; ++i) {
        const item = this.slots[i]
        if (item) results.push(item)
      }
      return results
    }

    count (itemType, metadata) {
      itemType = parseInt(itemType, 10) // allow input to be string
      return this.#countRange(this.inventoryStart, this.inventoryEnd, itemType, metadata)
    }

    items () {
      return this.#itemsRange(this.inventoryStart, this.inventoryEnd)
    }

    containerCount (itemType, metadata) {
      itemType = parseInt(itemType, 10) // allow input to be string
      return this.#countRange(0, this.inventoryStart, itemType, metadata)
    }

    containerItems () {
      return this.#itemsRange(0, this.inventoryStart)
    }

    emptySlotCount () {
      let count = 0
      for (let i = this.inventoryStart; i < this.inventoryEnd; ++i) {
        if (this.slots[i] === null) count += 1
      }
      return count
    }
  }

  // Corrected from PC1.21.1 CrafterMenu.addSlots, LecternMenu's constructor,
  // and SmithingMenu.createInputSlotDefinitions. The crafter preview remains
  // in slots[45], outside player inventory, and is not a takeable result.
  const windows = {}
  let protocolId = -1
  windows['minecraft:inventory'] = { type: protocolId++, inventory: { start: 9, end: 44 }, slots: 46, craft: 0, requireConfirmation: true }
  windows['minecraft:generic_9x1'] = { type: protocolId++, inventory: { start: 1 * 9, end: 1 * 9 + 35 }, slots: 1 * 9 + 36, craft: -1, requireConfirmation: true }
  windows['minecraft:generic_9x2'] = { type: protocolId++, inventory: { start: 2 * 9, end: 2 * 9 + 35 }, slots: 2 * 9 + 36, craft: -1, requireConfirmation: true }
  windows['minecraft:generic_9x3'] = { type: protocolId++, inventory: { start: 3 * 9, end: 3 * 9 + 35 }, slots: 3 * 9 + 36, craft: -1, requireConfirmation: true }
  windows['minecraft:generic_9x4'] = { type: protocolId++, inventory: { start: 4 * 9, end: 4 * 9 + 35 }, slots: 4 * 9 + 36, craft: -1, requireConfirmation: true }
  windows['minecraft:generic_9x5'] = { type: protocolId++, inventory: { start: 5 * 9, end: 5 * 9 + 35 }, slots: 5 * 9 + 36, craft: -1, requireConfirmation: true }
  windows['minecraft:generic_9x6'] = { type: protocolId++, inventory: { start: 6 * 9, end: 6 * 9 + 35 }, slots: 6 * 9 + 36, craft: -1, requireConfirmation: true }
  windows['minecraft:generic_3x3'] = { type: protocolId++, inventory: { start: 3 * 3, end: 3 * 3 + 35 }, slots: 3 * 3 + 36, craft: -1, requireConfirmation: true }
  windows['minecraft:crafter_3x3'] = { type: protocolId++, inventory: { start: 9, end: 44 }, slots: 46, craft: -1, requireConfirmation: true }
  windows['minecraft:anvil'] = { type: protocolId++, inventory: { start: 3, end: 38 }, slots: 39, craft: 2, requireConfirmation: true }
  windows['minecraft:beacon'] = { type: protocolId++, inventory: { start: 1, end: 36 }, slots: 37, craft: -1, requireConfirmation: true }
  windows['minecraft:blast_furnace'] = { type: protocolId++, inventory: { start: 3, end: 38 }, slots: 39, craft: 2, requireConfirmation: true }
  windows['minecraft:brewing_stand'] = { type: protocolId++, inventory: { start: 5, end: 40 }, slots: 41, craft: -1, requireConfirmation: true }
  windows['minecraft:crafting'] = { type: protocolId++, inventory: { start: 10, end: 45 }, slots: 46, craft: 0, requireConfirmation: true }
  windows['minecraft:enchantment'] = { type: protocolId++, inventory: { start: 2, end: 37 }, slots: 38, craft: -1, requireConfirmation: true }
  windows['minecraft:furnace'] = { type: protocolId++, inventory: { start: 3, end: 38 }, slots: 39, craft: 2, requireConfirmation: true }
  windows['minecraft:grindstone'] = { type: protocolId++, inventory: { start: 3, end: 38 }, slots: 39, craft: 2, requireConfirmation: true }
  windows['minecraft:hopper'] = { type: protocolId++, inventory: { start: 5, end: 40 }, slots: 41, craft: -1, requireConfirmation: true }
  windows['minecraft:lectern'] = { type: protocolId++, inventory: { start: 1, end: 0 }, slots: 1, craft: -1, requireConfirmation: true }
  windows['minecraft:loom'] = { type: protocolId++, inventory: { start: 4, end: 39 }, slots: 40, craft: 3, requireConfirmation: true }
  windows['minecraft:merchant'] = { type: protocolId++, inventory: { start: 3, end: 38 }, slots: 39, craft: 2, requireConfirmation: true }
  windows['minecraft:shulker_box'] = { type: protocolId++, inventory: { start: 27, end: 62 }, slots: 63, craft: -1, requireConfirmation: true }
  windows['minecraft:smithing'] = { type: protocolId++, inventory: { start: 4, end: 39 }, slots: 40, craft: 3, requireConfirmation: true }
  windows['minecraft:smoker'] = { type: protocolId++, inventory: { start: 3, end: 38 }, slots: 39, craft: 2, requireConfirmation: true }
  windows['minecraft:cartography'] = { type: protocolId++, inventory: { start: 3, end: 38 }, slots: 39, craft: 2, requireConfirmation: true }
  windows['minecraft:stonecutter'] = { type: protocolId++, inventory: { start: 2, end: 37 }, slots: 38, craft: 1, requireConfirmation: true }

  const windowByType = new Map()
  for (const key of Object.keys(windows)) {
    const win = windows[key]
    if (win) {
      windowByType.set(win.type, win)
      win.key = key
    }
  }

  return {
    createWindow: (type, title, slotCount = undefined) => {
      let winData = windowByType.get(type) ?? windows[type]
      if (!winData) {
        if (slotCount === undefined) return null
        winData = {
          type,
          key: type,
          inventory: { start: slotCount, end: slotCount + 35 },
          slots: slotCount + 36,
          craft: -1,
          requireConfirmation: type !== 'minecraft:container'
        }
      }
      slotCount = winData.slots
      return new Window(winData.key, title, slotCount, winData.inventory, winData.craft)
    },
    Window,
    windows
  }
}
