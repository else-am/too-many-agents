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

## Unsuitable harvest tool — preauthored

New isolated reference fixture: empty survival inventory and hand, grounded at
60.5/-60/0.5, stone target60/-60/3 above stone floor. Clear air, dry, no effects,
no nearby item entities in the fixture box. One public dig(target,true,NORTH)
call must resolve void and expose air plus one diggingCompleted(newBlock) callback
with cleared target fields. Empty hand is an unsuitable harvest tool for stone,
not a prohibition on breaking it. No cobblestone/item yield may be invented.
Record digTime estimate and elapsed time but do not require matching timers.
After ten observation ticks verify no event replay/inventory gain; independently
confirm target air, empty inventory and no item entities in the fixture box.
Stop at first failure, retain source/error, no retry or second dig. Native
counterpart remains pending; this checks a distinct plan requirement.

Unsuitable-tool reference result: first invocation PASS, estimated7500ms and
actual7502ms (timing recorded, not a compatibility tolerance). Void result,
one new-air completion event with cleared target fields, finite lastDigTime,
empty inventory and no replay after ten ticks. Three independent server checks
confirmed air, empty inventory and no item entities in the fixture. All dimensions
saved; process exited0 and port25575 unbound. Source/result/log retained under
/Users/scott/.bb/thread-storage/dig-unsuitable-reference/. Existing protocol and
setup teleport warnings are preserved, not a clean-protocol claim.

Native counterpart remains unrun. Use the same source on a fresh empty adult
bound body after independent dry/grounded/health/reach/NORTH-face and loaded-cell
inspection at the declared coordinates. Never clear ScriptProbe's inventory.
Record the exact new body's identity outside the common procedure and verify its
own completed mine action, target air and no dropped item independently. Actual
AgentHands.beginMine/advanceMine use native destroy progress, and finishMine
calls gameMode.destroyBlock for harvest rules; this source review identifies no
missing no-harvest branch, but cannot establish runtime behavior. Do not require
player timing or change native attributes. No cancellation, retry, extra dig or
unrelated loot cleanup is part of the measured procedure.

## Explicit digging cancellation — preauthored

Separate empty-hand stone fixture at60/-60/3, survival body60.5/-60/0.5.
Start exactly one dig and attach both fulfillment/rejection handlers immediately.
Wait boundedly for the public targetDigBlock to identify that target, then three
physics ticks; stone must still exist. Call stopDigging twice, requiring void
returns. Await the original promise: it must reject, emit one diggingAborted with
the original target and cleared target fields, emit no completion and leave the
inventory empty. Observe for160ticks (longer than this fixture's7500ms reference
dig estimate) to catch a stale finish timer, without another action. Independent
server checks confirm stone and no item entity/inventory. Stop on failure; no
replacement dig, retry or catch-and-repair. Native active-action evidence must
independently establish cancellation happened after native start when later run.

Explicit cancellation reference result: first invocation PASS. Original promise
rejected Error/Digging aborted; both stop calls returned void. One abort event
retained original target identity and observed cleared target fields. No completed
event, inventory gain or late block break over160ticks (elapsed8238ms). Three
server conditions independently confirmed stone/empty inventory/no item entity.
All dimensions saved, process exited0 and port25575 unbound. Exact source/result
and server log: /Users/scott/.bb/thread-storage/dig-cancel-reference/. Existing
protocol warning retained. Native counterpart remains unrun; no replacement-dig
or unknown-outcome claim. The harness helper was renamed miningScenario after
execution for clarity; the recorded measured source is unchanged.

Historical native equipment provenance recovered without rerun: exact completed
BB call2e13080f, 2026-10-07T22:04:23.765Z–22:04:25.656Z,16/16requests32updates35ops,
1794ms script/1880ms tool with release confirmed. Exact code is retained in
recorded/inventory-equipment-native.js; it differs from the failed original
reference only by one trailing newline. Passed native chest Item head/offhand,
both unequips and numeric sword hand/damage7. Independent saved tick5467 inventory
supports final item counts/components/equipment/selection, but auxiliary producer
arguments/time and tested JAR identity were not recovered. Common ordinary
reference adds numeric offhand, hand unequip, void results and whole component
assertions that this native call did NOT establish. Do not promote these gaps or
reinterpret the original reference failure. Full raw events/source/results:
/Users/scott/.bb/thread-storage/thr_xykqkgui57/equipment-provenance-recovery.json.

## Missing crafting materials — preauthored

Isolated survival Reference with empty inventory/cursor and no open window.
recipesAll(crafting_table,null,null) must expose a 2x2 recipe independently of
stock; recipesFor(crafting_table,null,1,null) must return an empty array. Call
craft on exactly one discovered recipe once. It must reject without producing
items or leaving cursor/menu state; record actual error, not exact cross-backend
error wording. Recheck after five ticks and independently confirm empty server
inventory. No fixture crafting table or ingredients are supplied, so no native
world change is needed. Stop on first failure; no retry/repair/alternate recipe.
This is distinct from the prior successful/partially divergent gathering-craft
runs and does not alter their evidence.

Missing-materials reference result: first invocation PASS,11 recipesAll entries,
0 available recipes, one Error/Error: missing ingredient rejection. No inventory,
cursor or menu change immediately or after5ticks; independent server inventory
condition passed. All dimensions saved, process exited0 and port25575 unbound.
Exact source/result/log: /Users/scott/.bb/thread-storage/craft-missing-reference/.
Existing protocol warning retained. Native counterpart remains unrun; use a fresh
empty disposable body through its actual bound script, without changing any
existing body's belongings. No exact error wording parity, successful crafting,
partial ingredient consumption or full-inventory behavior is implied.
