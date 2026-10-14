# Inventory and workstations

Use observed Item/Window handles from the current script. Replaced/closed windows reject operations. Whole inventory operations serialize through one queue; exact native menu generations and cursor state are checked between clicks. A known failure attempts safe cursor recovery without tossing. An unknown outcome poisons further inventory work. Recovery failure is attached as `error.recoveryError`; completed work is not rolled back.

## Exact slots and components

`await bot.clickWindow(slot, button, mode)` supports PICKUP (0), QUICK_MOVE (1), SWAP (2), CLONE (3), THROW (4). Drag/double-click (5/6) are unsupported. Button is 0/1, except SWAP uses hotbar 0..8 or offhand 40 and CLONE uses 2. Outside slot -999 is an intentional PICKUP drop. Creative/native slot permissions still apply.

`await bot.moveSlotItem(source, destination)` moves an exact observed stack inside the current menu, preserving component identity. Inspect `window.slots`, `selectedItem` (cursor), `inventoryStart`, `inventoryEnd` (exclusive), `hotbarStart`, `craftingResultSlot`, `type` and `title`. Inventory slot numbers are the player menu convention: hotbar 36..44, offhand 45, armor 5..8. Open menus have their own slot layout.

Queries: `items()`, `containerItems()`, `count(type, metadata?)`, `containerCount(type, metadata?)`, `findInventoryItem(typeOrName, metadata?, notFull?)`, `findContainerItem(...)`, `firstEmptyInventorySlot(hotbarFirst=true)`, `firstEmptyHotbarSlot()`, `firstEmptyContainerSlot()`, `emptySlotCount()`. Query matches are not exact modern component predicates. Withdraw/deposit accept an optional legacy NBT filter; use exact slot operations to select a particular modern variant.

`bot.setQuickBarSlot(0..8)` queues selection; `quickBarSlot` reflects pending selection. `await bot.tossStack(item)` intentionally drops that observed stack and closes the menu. Item getters retain the upstream local behavior: `components`/`removedComponents` are the serialized patch arrays; `componentMap` is a convenience index. Mutating only componentMap does not reliably serialize a change. Legacy metadata/NBT and legacy setters are local compatibility data, not a replacement for modern components. Never use legacy NBT equality to infer stack identity.

## Workstation details

Named block workstations and table crafting use the top face without a face override. A short body can receive `target_out_of_reach` when that face is occluded even one block away; approach a visible top face (for example a workstation flush with the ground). Native reach validation remains authoritative.

Furnace queries return Items or null. `fuel` and `progress` are fractions; `fuelSeconds`, `progressSeconds` (remaining), and `totalProgress` (ticks) come from native properties. Smelting advances with game ticks, not the script's wall clock. Enchanting exposes three `enchantments` entries `{level, expected:{enchant, level}}` and `xpseed`; options can be unavailable. `enchant(choice)` takes an index 0..2, pays native costs and returns the resulting Item.

Anvil operations take observed Items and preserve component-identical inputs. Names are at most 35 characters. The native output/XP rules decide success, not an Item-side prediction. Merchant `trades` contain `inputItem1` (base cost), optional `inputItem2`, `costA` (actual first payment), `outputItem`, `tradeDisabled`, `nbTradeUses`, `maximumNbTradeUses`, `demand`, `specialPrice`, `priceMultiplier`, `xp`, `rewardExp`; `selectedTrade` refers to the selected offer. Native component predicates and autofill remain authoritative. Supply an explicit positive `times` ≤256; omitted/falsy times means all remaining uses. Errors expose `completedTrades`.

`bot.openContainer(target, direction?, cursorPos?)` and `bot.openBlock(block, direction?, cursorPos?)` and `openEntity(entity)` retain generic menu access for unusual native menus. `openContainer` accepts supported storage blocks/entities; it is not a generic workstation opener. Use `clickWindow` when a retained generic menu has no named operation. The default block face is up; a short body may not see it. Supply an exposed cardinal face and block-local cursor point, for example west `new Vec3(-1,0,0)` with `new Vec3(0,0.5,0.5)`. Do not simulate slot changes locally.

Window `on`/`once`/`off` support `updateSlot(slot, before, after)`, `close()` and workstation `ready()`. Native slot/cursor hydration completes before callbacks run. Prefer current state and awaited operations over maintaining a duplicate inventory model.
