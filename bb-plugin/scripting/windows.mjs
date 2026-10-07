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

// The public Click type omits item; Mineflayer supplies it. Support both.
const withItem = (window, click) => Object.hasOwn(click, 'item')
  ? click : { ...click, item: window.slots[click.slot] ?? null }

// Components can contain NBT longs and nested Items/Slots. Preserve prototypes
// and non-JSON values when splitting; keep the legacy NBT alias as upstream did.
function copyData (value) {
  if (!value || typeof value !== 'object') return value
  if (Array.isArray(value)) return value.map(copyData)
  if (value instanceof Map) return new Map([...value].map(([k, v]) => [k, copyData(v)]))
  const copy = Object.create(Object.getPrototypeOf(value))
  for (const key of Object.keys(value)) copy[key] = copyData(value[key])
  return copy
}

function sameData (left, right) {
  if (Object.is(left, right)) return true
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object') return false
  if (Array.isArray(left) !== Array.isArray(right)) return false
  if (left instanceof Map || right instanceof Map) {
    if (!(left instanceof Map && right instanceof Map) || left.size !== right.size) return false
    return [...left].every(([key, value]) => right.has(key) && sameData(value, right.get(key)))
  }
  const keys = Object.keys(left)
  return keys.length === Object.keys(right).length && keys.every(key => Object.hasOwn(right, key) && sameData(left[key], right[key]))
}

// Local synchronous inventory model only. These methods do not send clicks,
// enforce native slot predicates, run recipes, or confirm world mutations.
// Registry data is already captured by Item; Window needs no registry fields.
// Deliberate fixes to 2.10.0 are exercised separately in the reference script:
// modern layouts, exclusive range ends, non-destructive transfers, component
// copies/stackability, empty-cursor drops, typed Clicks, and documented clear.
// Upstream dragClick/doubleClick remain explicit unsupported errors. Count-only
// mutations retain upstream event/return behavior, including undefined returns.
export function createWindowFactory (Item) {
  const copyItem = (item, count) => {
    const copy = new Item(item.type, count, item.metadata, item.nbt)
    copy.components = copyData(item.components)
    copy.removedComponents = copyData(item.removedComponents)
    copy.componentMap = new Map([...item.componentMap].map(([key, value]) => {
      const index = item.components.indexOf(value)
      return [key, index === -1 ? copyData(value) : copy.components[index]]
    }))
    return copy
  }
  const stackable = (left, right) => {
    if (!left || !right || !Item.equal(left, right, false)) return false
    const patches = item => new Map((item.components ?? []).map(c => [c.type, c]))
    const removals = item => new Map((item.removedComponents ?? []).map(c => [c.type ?? c, true]))
    return sameData(patches(left), patches(right)) && sameData(removals(left), removals(right)) &&
      sameData(left.componentMap, right.componentMap)
  }
  class Window extends EventEmitter {
    constructor (id, type, title, slotCount,
      inventorySlotsRange = { start: 27, end: 62 },
      craftingResultSlot = -1,
      requiresConfirmation = true) {
      super()
      this.id = id
      this.type = type
      this.title = title
      this.slots = new Array(slotCount).fill(null)
      this.inventoryStart = inventorySlotsRange.start
      this.inventoryEnd = inventorySlotsRange.end + 1
      this.hotbarStart = Math.max(this.inventoryStart, this.inventoryEnd - 9)
      this.craftingResultSlot = craftingResultSlot
      this.requiresConfirmation = requiresConfirmation
      // in vanilla client, this is the item you are holding with the
      // mouse cursor
      this.selectedItem = null
    }

    acceptClick (click, gamemode = 0) {
      const { mode, slot, mouseButton } = click
      assert.ok(
        (mode >= 0 && mode <= 6) &&
        (mouseButton >= 0 && mouseButton <= 8) &&
        ((slot >= 0 && slot < this.inventoryEnd) || slot === -999 ||
         (this.type === 'minecraft:inventory' && slot === 45)),
        'invalid operation')

      switch (click.mode) {
        case 0:
          assert.ok(mouseButton <= 1, 'invalid operation')
          return this.mouseClick(click)

        case 1:
          assert.ok(mouseButton <= 1, 'invalid operation')
          return this.shiftClick(click)

        case 2:
          assert.ok(mouseButton <= 8, 'invalid operation')
          return this.numberClick(click)

        case 3:
          assert.ok(mouseButton === 2, 'invalid operation')
          return this.middleClick(click, gamemode)

        case 4:
          assert.ok(mouseButton <= 1, 'invalid operation')
          return this.dropClick(click)

        case 5:
          assert.ok([1, 5, 9, 2, 6, 10].includes(mouseButton), 'invalid operation')
          return this.dragClick(click, gamemode)

        case 6:
          assert.ok(mouseButton === 0, 'invalid operation')
          return this.doubleClick(click)
      }
    }

    mouseClick (click) {
      click = withItem(this, click)
      if (click.slot === -999) {
        this.dropSelectedItem(click.mouseButton === 0)
      } else {
        let { item } = click
        if (click.mouseButton === 0) { // left click
          if (item && this.selectedItem) {
            if (stackable(item, this.selectedItem)) {
              if (click.slot === this.craftingResultSlot) {
                const maxTransferrable = this.selectedItem.stackSize - this.selectedItem.count
                if (item.count > maxTransferrable) {
                  this.selectedItem.count += maxTransferrable
                  item.count -= maxTransferrable
                } else if (item.count <= maxTransferrable) {
                  this.selectedItem.count += item.count
                  this.updateSlot(item.slot, null)
                }
              } else {
                this.fillSlotWithSelectedItem(item, true)
              }
            } else {
              this.swapSelectedItem(click.slot, item)
            }

            return [click.slot]
          } else if (this.selectedItem || item) {
            this.swapSelectedItem(click.slot, item)

            return [click.slot]
          }
        } else if (click.mouseButton === 1) { // right click
          if (this.selectedItem) {
            if (item) {
              if (stackable(item, this.selectedItem)) {
                this.fillSlotWithSelectedItem(item, false)
              } else {
                this.swapSelectedItem(click.slot, item)
              }
            } else {
              item = copyItem(this.selectedItem, 0)
              this.updateSlot(click.slot, item)
              this.fillSlotWithSelectedItem(item, false)
            }

            return [click.slot]
          } else if (item && click.slot !== this.craftingResultSlot) {
            this.splitSlot(item)

            return [click.slot]
          }
        }
      }

      return []
    }

    shiftClick (click) {
      click = withItem(this, click)
      const { item } = click
      if (!item) return
      if (this.type === 'minecraft:inventory') {
        if (click.slot < this.inventoryStart) {
          this.fillAndDump(item, this.inventoryStart, this.inventoryEnd, click.slot === this.craftingResultSlot)
        } else {
          if (click.slot >= this.inventoryStart && click.slot < this.hotbarStart) {
            this.fillAndDump(item, this.hotbarStart, this.inventoryEnd)
          } else {
            this.fillAndDump(item, this.inventoryStart, this.hotbarStart)
          }
        }
      } else {
        if (click.slot < this.inventoryStart) {
          this.fillAndDump(item, this.inventoryStart, this.inventoryEnd, this.craftingResultSlot === -1 || click.slot === this.craftingResultSlot)
        } else {
          this.fillAndDump(item, 0, this.inventoryStart)
        }
      }
    }

    numberClick (click) {
      click = withItem(this, click)
      if (this.selectedItem) return
      const { item } = click
      const hotbarSlot = this.hotbarStart + click.mouseButton
      if (hotbarSlot >= this.inventoryEnd) return // Lecterns have no hotbar.
      const itemAtHotbarSlot = this.slots[hotbarSlot]
      if (Item.equal(item, itemAtHotbarSlot) && item?.slot === hotbarSlot) return
      if (item) {
        if (itemAtHotbarSlot) {
          if (click.slot !== this.craftingResultSlot) {
            this.updateSlot(click.slot, itemAtHotbarSlot)
            this.updateSlot(hotbarSlot, item)
          } else {
            this.dumpItem(itemAtHotbarSlot, this.hotbarStart, this.inventoryEnd)
            if (this.slots[hotbarSlot]) {
              this.dumpItem(itemAtHotbarSlot, this.inventoryStart, this.hotbarStart)
            }
            if (this.slots[hotbarSlot] === null) {
              this.updateSlot(item.slot, null)
              this.updateSlot(hotbarSlot, item)
              let slots = this.findItemsRange(this.hotbarStart, this.inventoryEnd, itemAtHotbarSlot.type, itemAtHotbarSlot.metadata, true, itemAtHotbarSlot.nbt)
              slots.push(...this.findItemsRange(this.inventoryStart, this.hotbarStart, itemAtHotbarSlot.type, itemAtHotbarSlot.metadata, true, itemAtHotbarSlot.nbt))
              slots = slots.filter(slot => slot.slot !== itemAtHotbarSlot.slot)
              this.fillSlotsWithItem(slots, itemAtHotbarSlot)
            }
          }
        } else {
          this.updateSlot(item.slot, null)
          this.updateSlot(hotbarSlot, item)
        }
      } else if (itemAtHotbarSlot && click.slot !== this.craftingResultSlot) {
        this.updateSlot(click.slot, itemAtHotbarSlot)
        this.updateSlot(hotbarSlot, null)
      }
    }

    middleClick (click, gamemode) {
      click = withItem(this, click)
      if (this.selectedItem) return []
      const { item } = click
      if (gamemode === 1 && item) {
        this.selectedItem = copyItem(item, item.stackSize)
      }
      return []
    }

    dropClick (click) {
      click = withItem(this, click)
      const { item } = click
      if (this.selectedItem || item === null) return []
      if (click.mouseButton === 0) {
        if (--click.item.count === 0) this.updateSlot(click.slot, null)
        return [click.slot]
      } else if (click.mouseButton === 1) {
        this.updateSlot(click.slot, null)
        return [click.slot]
      }
    }

    dragClick (click, gamemode) {
      // unimplemented
      assert.ok(false, 'unimplemented')
    }

    doubleClick (click) {
      // unimplemented
      assert.ok(false, 'unimplemented')
    }

    acceptOutsideWindowClick = this.acceptClick
    acceptInventoryClick = this.acceptClick
    acceptNonInventorySwapAreaClick = this.acceptClick
    acceptSwapAreaLeftClick = this.acceptClick
    acceptSwapAreaRightClick = this.acceptClick
    acceptCraftingClick = this.acceptClick

    fillAndDump (item, start, end, lastToFirst = false) {
      this.fillSlotsWithItem(this.findItemsRange(start, end, item.type, item.metadata, true, item.nbt, true), item, lastToFirst)
      if (this.slots[item.slot]) {
        this.dumpItem(item, start, end, lastToFirst)
      }
    }

    fillSlotsWithItem (slots, item, lastToFirst = false) {
      while (slots.length && item.count) {
        this.fillSlotWithItem(lastToFirst ? slots.pop() : slots.shift(), item)
      }
    }

    fillSlotWithItem (itemToFill, itemToTake) {
      if (itemToFill === itemToTake || !stackable(itemToFill, itemToTake)) return
      const newCount = itemToFill.count + itemToTake.count
      const leftover = newCount - itemToFill.stackSize
      if (leftover <= 0) {
        itemToFill.count = newCount
        itemToTake.count = 0
        this.updateSlot(itemToTake.slot, null)
      } else {
        itemToFill.count = itemToFill.stackSize
        itemToTake.count = leftover
      }
    }

    fillSlotWithSelectedItem (item, untilFull) {
      if (item === this.selectedItem || !stackable(item, this.selectedItem)) return
      if (untilFull) {
        const newCount = item.count + this.selectedItem.count
        const leftover = newCount - item.stackSize
        if (leftover <= 0) {
          item.count = newCount
          this.selectedItem = null
        } else {
          item.count = item.stackSize
          this.selectedItem.count = leftover
        }
      } else {
        if (item.count + 1 <= item.stackSize) {
          item.count++
          if (--this.selectedItem.count === 0) this.selectedItem = null
        }
      }
    }

    dumpItem (item, start, end, lastToFirst = false) {
      const emptySlot = lastToFirst ? this.lastEmptySlotRange(start, end) : this.firstEmptySlotRange(start, end)
      if (emptySlot !== null && emptySlot !== this.craftingResultSlot) {
        const slot = item.slot
        this.updateSlot(emptySlot, item)
        this.updateSlot(slot, null)
      }
    }

    splitSlot (item) {
      if (!item) return
      this.selectedItem = copyItem(item, Math.ceil(item.count / 2))
      item.count -= this.selectedItem.count
      if (item.count === 0) this.updateSlot(item.slot, null)
    }

    swapSelectedItem (slot, item) {
      this.updateSlot(slot, this.selectedItem)
      this.selectedItem = item
    }

    dropSelectedItem (untilEmpty) {
      if (!this.selectedItem) return
      if (untilEmpty || --this.selectedItem.count === 0) this.selectedItem = null
    }

    updateSlot (slot, newItem) {
      if (newItem) newItem.slot = slot
      const oldItem = this.slots[slot]
      this.slots[slot] = newItem

      this.emit('updateSlot', slot, oldItem, newItem)
      this.emit(`updateSlot:${slot}`, oldItem, newItem)
    }

    findItemsRange (start, end, itemType, metadata, notFull, nbt, withoutCraftResultSlot = false) {
      const items = []
      while (start < end) {
        const item = this.findItemRange(start, end, itemType, metadata, notFull, nbt, withoutCraftResultSlot)
        if (!item) break
        start = item.slot + 1
        items.push(item)
      }
      return items
    }

    findItemRange (start, end, itemType, metadata, notFull, nbt, withoutCraftResultSlot = false) {
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

    findItemRangeName (start, end, itemName, metadata, notFull) {
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
        ? this.findItemRange(this.inventoryStart, this.inventoryEnd, item, metadata, notFull)
        : this.findItemRangeName(this.inventoryStart, this.inventoryEnd, item, metadata, notFull)
    }

    findContainerItem (item, metadata, notFull) {
      assert(typeof item === 'number' || typeof item === 'string' || typeof item === 'undefined', 'No valid type given')
      return typeof item === 'number'
        ? this.findItemRange(0, this.inventoryStart, item, metadata, notFull)
        : this.findItemRangeName(0, this.inventoryStart, item, metadata, notFull)
    }

    firstEmptySlotRange (start, end) {
      for (let i = start; i < end; ++i) {
        if (this.slots[i] === null) return i
      }
      return null
    }

    lastEmptySlotRange (start, end) {
      for (let i = end - 1; i >= start; i--) {
        if (this.slots[i] === null) return i
      }
      return null
    }

    firstEmptyHotbarSlot () {
      return this.firstEmptySlotRange(this.hotbarStart, this.inventoryEnd)
    }

    firstEmptyContainerSlot () {
      return this.firstEmptySlotRange(0, this.inventoryStart)
    }

    firstEmptyInventorySlot (hotbarFirst = true) {
      if (hotbarFirst) {
        const slot = this.firstEmptyHotbarSlot()
        if (slot !== null) return slot
      }
      return this.firstEmptySlotRange(this.inventoryStart, this.inventoryEnd)
    }

    sumRange (start, end) {
      let sum = 0
      for (let i = start; i < end; i++) {
        const item = this.slots[i]
        if (item) sum += item.count
      }
      return sum
    }

    countRange (start, end, itemType, metadata) {
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

    itemsRange (start, end) {
      const results = []
      for (let i = start; i < end; ++i) {
        const item = this.slots[i]
        if (item) results.push(item)
      }
      return results
    }

    count (itemType, metadata) {
      itemType = parseInt(itemType, 10) // allow input to be string
      return this.countRange(this.inventoryStart, this.inventoryEnd, itemType, metadata)
    }

    items () {
      return this.itemsRange(this.inventoryStart, this.inventoryEnd)
    }

    containerCount (itemType, metadata) {
      itemType = parseInt(itemType, 10) // allow input to be string
      return this.countRange(0, this.inventoryStart, itemType, metadata)
    }

    containerItems () {
      return this.itemsRange(0, this.inventoryStart)
    }

    emptySlotCount () {
      let count = 0
      for (let i = this.inventoryStart; i < this.inventoryEnd; ++i) {
        if (this.slots[i] === null) count += 1
      }
      return count
    }

    transactionRequiresConfirmation (click) {
      return this.requiresConfirmation
    }

    clear (blockId, count) {
      if (count != null && count <= 0) return 0
      let clearedCount = 0
      const clearSlot = slot => {
        const item = this.slots[slot]
        if (!item || (blockId != null && item.type !== blockId)) return
        const take = count == null ? item.count : Math.min(item.count, count - clearedCount)
        if (take <= 0) return
        clearedCount += take
        this.updateSlot(slot, take === item.count ? null : copyItem(item, item.count - take))
      }
      // Keep upstream's inventory order, then cover the other slots promised
      // by clear(). The cursor is separate from the window's slots.
      if (this.type === 'minecraft:inventory') clearSlot(45)
      for (let i = this.inventoryEnd - 1; i >= this.hotbarStart; i--) clearSlot(i)
      for (let i = this.inventoryStart; i < this.hotbarStart; i++) clearSlot(i)
      for (let i = 0; i < this.inventoryStart; i++) clearSlot(i)
      for (let i = this.inventoryEnd; i < this.slots.length; i++) {
        if (this.type !== 'minecraft:inventory' || i !== 45) clearSlot(i)
      }
      return clearedCount
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
    createWindow: (id, type, title, slotCount = undefined) => {
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
      return new Window(id, winData.key, title, slotCount, winData.inventory, winData.craft, winData.requireConfirmation)
    },
    Window,
    windows
  }
}
