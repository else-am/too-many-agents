# Core action scenarios (preauthored)

Source oracle: installed Mineflayer 4.39.0 `digging.js`, `generic_place.js`,
`place_block.js`, `inventory.js` activation functions, and `physics.js` look/lookAt;
docs/api.md corresponding public methods. Native validation is lead-owned.

- Dig lifecycle: held tool/helmet effects change Block.digTime estimate; loaded,
  diggable/reach predicate uses body eyes/native range. Auto, explicit face,
  raycast, and forceLook='ignore' preserve their requested intent. Native
  completion must precede verified block change and diggingCompleted(newBlock).
  Aborted/failed dig clears target fields, timestamps once, and emits aborted.
- Cancellation ordering: stop before start reply; replacement dig while start or
  cancel is pending; repeated stop; completed-event reentrancy. Cancel only that
  dig's ID, drain cancellation and terminal state before replacement/settlement.
  Known prestart rejection can be caught. Unknown start/await/control outcome
  forbids any subsequent module mutation; never replay.
- Placement: six faces, top/bottom half cursor, explicit delta, offhand, forced
  and ignored look. Verify actual destination state change before blockPlaced;
  same-type slab merging may change state without type. Native refused/no-op,
  unloaded target, empty hand and stale reference reject without invented blocks.
  _genericPlace returns reference position; placeBlock resolves void.
- Activation: block-local cursor and face are preserved; entity UUID identifies
  target. activateEntityAt converts the caller's world point into entity-relative
  hit coordinates without mutating the caller's Vec3. Reach/occlusion remain native.
- Look: cardinal/up/down yaw/pitch against pinned lookAt formula using actual eye
  height; force boolean propagated. Promise resolves after native facing state,
  including unchanged-angle no-op, rather than waiting for an absent event.
- Native combined check only: stop a partial dig and replace it, explicit-face
  dig, one real placement and one interaction/look. Check independent world,
  actual tool/item effects and terminal ordered state. No broad conformance claim.

Intentional corrections: no optimistic air insertion or local mining timer;
native auto mining selects a reachable face instead of forcing upstream's top;
canDigBlock uses native body eye/range, not fixed player 1.65/5.1; look does not
apply the player's sensitivity quantization; placement checks state changes,
including same-type slab merges; entityAt never mutates its supplied vector.
Combat, fishing, mounts, creative and general control-state APIs remain pending.

## Reference equipment pairing — preauthored

Run inventory-equipment.js unchanged once against pinned Mineflayer. Isolated
survival Reference at12.5/-60/0.5, chest12/-60/2 with iron helmet slot2 and shield
slot3; player stone17 in main inventory slot9, diamond pickaxe hotbar6, named
diamond sword damage7 hotbar0. Empty other slots/cursor and selected hotbar6.
The procedure itself checks split/reassembly, container offhand transfer, armor
staging/close, unequip, and preservation of the sword's damage. Its final
setQuickBarSlot intentionally has no await; do not invent completion timing
parity with the native queue. Independently confirm stone17, helmet1, shield1,
sword damage7 and selected slot6 after successful return. Preserve literal
source/result and stop at first failure without repair or replay. Reference
setup uses only its own server/world, not the guarded native client.

Reference equipment result (2026-10-08T11:16:42.428Z): unchanged source ran once,
passed odd split/one-item placement/reassembly, then failed Container offhand
mapping failed. Armor/unequip/sword/final control phases were not reached. No
repair or retry. Pinned simple_inventory.js sets off-hand destination45 and calls
moveSlotItem(sourceSlot,45), whose clicks use the active window. In a 27-slot
chest, window slot45 maps to player inventory slot27, not offhand. Independently
parsed saved player NBT confirms shield in Slot27, stone17 in Slot9, sworddamage7
in Slot0, pickaxe in Slot6 and selection6. Thus this is a concrete upstream
container-slot defect, not a reason to mis-equip the native body. Preserve the
native adapter's equipment mapping and require its own native evidence; this
failure does not establish the unreached methods' parity. Existing protocol and
setup teleport warnings remain. All dimensions saved; owned server stopped and
port25575 unbound. Exact result/source/server log/player NBT and decoded inventory:
/Users/scott/.bb/thread-storage/equipment-reference/. No production code changed.

## Ordinary equipment — preauthored separate case

The container-slot failure left armor/unequip/sword phases unexecuted. Use a new
ordinary inventory fixture with no open container: stone17 in slot9, helmet in
slot10, shield in slot11, named damage7 diamond sword hotbar0, pickaxe hotbar6.
Equip helmet by Item to head and shield by numeric type to off-hand; each awaited
result is void and corresponding equipment slot must reflect the item. Unequip
both, equip sword by numeric type, then unequip hand to an available empty slot.
Verify all item counts and sword components unchanged, cursor/window clear.
Select hotbar6 and wait one ordinary physics tick before checking held pickaxe;
this deliberate common observation barrier does not claim immediate native
control semantics. Independently inspect server inventory/equipment and selected
slot after return. Invoke once, retain first failure and stop; no container
attempt, split repetition, full-inventory toss behavior or retry.

Ordinary equipment reference result: first invocation PASS, source and report in
/Users/scott/.bb/thread-storage/equipment-common-reference/. Item/numeric equip,
head/offhand/hand transitions, all awaited void results, unequip, damage7 sword
components and all five item counts passed. Seven independent server conditions
confirmed final counts/damage, empty head/offhand and selection6. One physics
tick after explicit quickbar selection remains part of this common procedure.
Existing protocol warning retained. All dimensions saved; process exited0 and
port25575 unbound. The separate container-slot failure is unchanged. This common
source is ready for a future native fixture with identical ordinary inventory;
no native execution or broad equip/unequip conformance claim follows from this
reference pass. Full inventory and other equipment destinations remain separate
coverage requirements, not exclusions.
