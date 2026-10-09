/*!
 * PC 1.21.1 adaptation of prismarine-item 1.18.0 (MIT), including anvil.js.
 * Item author: Romain Beaumont <romain.rom1@gmail.com>.
 * NBT builders/simplification from prismarine-nbt 2.8.0 (MIT), author:
 * roblabla <robinlambertz+dev@gmail.com>. See items.LICENSE for the original
 * license declarations and attribution. The npm packages supply no LICENSE.
 */

// Plain build-time minecraft-data 3.117.0 fields: itemsArray and
// enchantmentsByName. No host dependencies or guest module loader.
//
// Native observations enter through Item.fromNotch with the 1.21.1 decoded Slot
// shape: { itemId, itemCount, components: [{ type, data }],
// removeComponents: [{ type }] }. Component types are protocol names without
// "minecraft:"; data must have the protocol-decoded shape (including typed NBT,
// numeric registry IDs, and nested Slots). Empty slots have itemCount: 0.
// Supply the actual component patch, not resolved defaults or synthetic NBT.
// Inventory integration sets item.slot separately. Setters
// affect local objects only; no method here authorizes or performs a world edit.
//
// Preserve pinned quirks: map setters do
// not synchronize components; durability/enchant setters still write NBT;
// component getters return upstream's raw data, even where typings disagree.
export function createItemClass (registry) {
  for (const field of ['itemsArray', 'enchantmentsByName']) {
    if (registry[field] == null) throw new Error(`Item registry is missing ${field}`)
  }
  const items = Object.fromEntries(registry.itemsArray.map(item => [item.id, item]))
  const enchantmentsByName = registry.enchantmentsByName
  class Item {
    constructor (type, count, metadata, nbt, stackId, sentByServer) {
      if (type == null) return

      if (metadata instanceof Object) {
        sentByServer = stackId
        stackId = nbt
        nbt = metadata
        metadata = 0
      }

      this.type = type
      this.count = count
      this.metadata = metadata == null ? 0 : metadata
      this.nbt = nbt || null

      this.components = []
      this.removedComponents = []
      this.componentMap = new Map()

      const itemEnum = items[type]
      if (itemEnum) {
        this.name = itemEnum.name
        this.displayName = itemEnum.displayName
        this.stackSize = itemEnum.stackSize
        this.maxDurability = itemEnum.maxDurability

        if ('variations' in itemEnum) {
          const variation = itemEnum.variations.find((item) => item.metadata === metadata)
          if (variation) this.displayName = variation.displayName
        }

        // Can't initialize fields if the item was sent by the server
        if (!sentByServer) {
          // The 'itemEnum.maxDurability' checks to see if this item can lose durability
          if (this.maxDurability && !this.durabilityUsed) this.durabilityUsed = 0
        }
      } else {
        this.name = 'unknown'
        this.displayName = 'unknown'
        this.stackSize = 1
      }
    }

    get customName () {
      if (this.componentMap?.has('custom_name')) {
        return this.componentMap.get('custom_name').data
      }
      return this?.nbt?.value?.display?.value?.Name?.value ?? null
    }

    set customName (newName) {
      if (this.componentMap) {
        this.componentMap.set('custom_name', { type: 'custom_name', data: newName })
        return
      }
      if (!this.nbt) this.nbt = nbt.comp({})
      if (!this.nbt.value.display) this.nbt.value.display = { type: 'compound', value: {} }
      this.nbt.value.display.value.Name = nbt.string(newName)
    }

    get customLore () {
      if (this.componentMap?.has('lore')) {
        return this.componentMap.get('lore').data
      }
      if (!this.nbt?.value?.display) return null
      return nbt.simplify(this.nbt).display.Lore ?? null
    }

    set customLore (newLore) {
      if (this.componentMap) {
        this.componentMap.set('lore', { type: 'lore', data: newLore })
        return
      }
      if (!this.nbt) this.nbt = nbt.comp({})
      if (!this.nbt.value.display) this.nbt.value.display = { type: 'compound', value: {} }

      this.nbt.value.display.value.Lore = nbt.string(newLore)
    }

    // gets the cost based on previous anvil uses
    get repairCost () {
      if (this.componentMap?.has('repair_cost')) {
        return this.componentMap.get('repair_cost').data
      }
      return this?.nbt?.value?.RepairCost?.value ?? 0
    }

    set repairCost (newRepairCost) {
      if (this.componentMap) {
        this.componentMap.set('repair_cost', { type: 'repair_cost', data: newRepairCost })
        return
      }
      if (!this?.nbt) this.nbt = nbt.comp({})
      this.nbt.value.RepairCost = nbt.int(newRepairCost)
    }

    get enchants () {
      if (Object.keys(this).length === 0) return []
      if (this.componentMap?.has('enchantments')) return this.componentMap.get('enchantments').data
      let itemEnch = []
      if (this.name === 'enchanted_book' && this?.nbt?.value?.StoredEnchantments) {
        itemEnch = nbt.simplify(this.nbt).StoredEnchantments
      } else if (this?.nbt?.value?.Enchantments) {
        itemEnch = nbt.simplify(this.nbt).Enchantments
      }
      return itemEnch.map(ench => ({
        lvl: ench.lvl,
        name: typeof ench.id === 'string' ? ench.id.replace('minecraft:', '') : null
      }))
    }

    set enchants (normalizedEnchArray) {
      const useStoredEnchants = this.name === 'enchanted_book'
      const enchs = normalizedEnchArray.map(({ name, lvl }) => ({
        id: nbt.string(`minecraft:${enchantmentsByName[name].name}`),
        lvl: nbt.short(lvl)
      }))
      if (enchs.length !== 0) {
        if (!this.nbt) this.nbt = nbt.comp({})
        this.nbt.value[useStoredEnchants ? 'StoredEnchantments' : 'Enchantments'] = nbt.list(nbt.comp(enchs))
      } else if (this.enchants.length !== 0) {
        // Preserve upstream's deletion target, including its missing .value.
        delete this.nbt?.[useStoredEnchants ? 'StoredEnchantments' : 'Enchantments']
      }
    }

    get blocksCanPlaceOn () {
      const blockNames = this?.nbt?.value?.CanPlaceOn?.value?.value ?? []
      return blockNames.map(name => [name])
    }

    set blocksCanPlaceOn (newBlocks) {
      if (newBlocks.length === 0) {
        if (this.blocksCanPlaceOn.length !== 0) delete this.nbt.value.CanPlaceOn
        return
      }
      if (!this.nbt) this.nbt = nbt.comp({})

      const blockNames = []
      for (const block of newBlocks) {
        let [ns, name] = block.split(':')
        if (!name) {
          name = ns
          ns = 'minecraft'
        }
        blockNames.push(`${ns}:${name}`)
      }

      this.nbt.value.CanPlaceOn = nbt.list(nbt.string(blockNames))
    }

    get blocksCanDestroy () {
      const blockNames = this?.nbt?.value?.CanDestroy?.value?.value ?? []
      return blockNames.map(name => [name])
    }

    set blocksCanDestroy (newBlocks) {
      if (newBlocks.length === 0) {
        if (this.blocksCanDestroy.length !== 0) delete this.nbt.value.CanDestroy
        return
      }
      if (!this.nbt) this.nbt = nbt.comp({})

      const blockNames = []
      for (const block of newBlocks) {
        let [ns, name] = block.split(':')
        if (!name) {
          name = ns
          ns = 'minecraft'
        }
        blockNames.push(`${ns}:${name}`)
      }

      this.nbt.value.CanDestroy = nbt.list(nbt.string(blockNames))
    }

    get durabilityUsed () {
      let ret
      if (this.componentMap && this.componentMap.has('damage')) ret = this.componentMap.get('damage').data
      if (ret === undefined) ret = this.nbt?.value?.Damage?.value
      return ret ?? (this.maxDurability ? 0 : null)
    }

    set durabilityUsed (value) {
      if (!this?.nbt) this.nbt = nbt.comp({})
      this.nbt.value.Damage = nbt.int(value)
    }

    get spawnEggMobName () {
      return this.name.replace('_spawn_egg', '')
    }
  }

  return Item
}

// Only typed NBT objects are needed; binary I/O is not part of Item's API.
const nbt = {
  comp: (value, name = '') => ({ type: 'compound', name, value }),
  string: value => ({ type: 'string', value }),
  short: value => ({ type: 'short', value }),
  int: value => ({ type: 'int', value }),
  list: value => ({ type: 'list', value: { type: value?.type ?? 'end', value: value?.value ?? [] } }),
  simplify
}

function simplify (data) {
  function transform (value, type) {
    if (type === 'compound') {
      return Object.keys(value).reduce(function (acc, key) {
        acc[key] = simplify(value[key])
        return acc
      }, {})
    }
    if (type === 'list') {
      return value.value.map(function (v) { return transform(v, value.type) })
    }
    return value
  }
  return transform(data.value, data.type)
}

export function toNotch (item, serverAuthoritative = true) {
      // Upstream evaluates this even in component versions. Preserve malformed
      // NBT errors instead of silently accepting input that upstream rejects.
      if (item && item.nbt) Object.keys(item.nbt.value)
      if (!item) return { itemCount: 0, components: [], removeComponents: [] }
      return {
        present: true,
        itemCount: item.count,
        itemId: item.type,
        addedComponentCount: item.components.length,
        removedComponentCount: item.removedComponents.length,
        components: item.components,
        removeComponents: item.removedComponents
      }
    }

export function fromNotch (Item, networkItem) {
      if (networkItem.present === false) return null
      if (networkItem.itemCount === 0) return null
      // This argument order deliberately matches prismarine-item 1.18.0.
      const item = new Item(networkItem.itemId, networkItem.itemCount, null, null, true)
      item.components = networkItem.components
      item.removedComponents = networkItem.removeComponents
      item.componentMap = new Map()
      if (item.components) {
        for (const component of item.components) item.componentMap.set(component.type, component)
      }
      return item
    }
