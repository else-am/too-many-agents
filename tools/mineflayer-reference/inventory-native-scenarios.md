# Inventory/container/crafting native adapter handoff

Preimplementation review against lead checkout `0ee98fe` (read-only), pinned
Mineflayer 4.39.0 / prismarine-windows 2.10.0, and generated NeoForge 21.1.251 /
Minecraft 1.21.1 sources. No product implementation or live execution in this
review. The scenarios below are requirements for subsequent implementation,
not evidence that those APIs work. No reference server/EULA is assumed.

## Recommended adapter

Use real native menu clicks plus the existing hydrated Window/Item objects.
Adapt the ordinary public orchestration from inventory.js, simple_inventory.js,
craft.js and specialized window plugins. Do not load those plugins wholesale:
their packet listeners, optimistic Window.acceptClick changes, synthetic output
items and protocol acknowledgements are inappropriate for this native bridge.
No new library or fake protocol client is needed.

One guest `installInventory(bot, hooks)` module should own the cursor and serialize
whole high-level operations (transfer/equip/craft/open/close), with internal
helpers calling an unqueued primitive to avoid recursive queue deadlock. Public
clickWindow joins that same queue. A single per-script queue is enough; do not
add another lease or controller. Preserve caller order and validate the captured
window again when an operation starts. Never execute queued work after script
cancellation or world-session replacement. Event handlers can enqueue work, but
must not block the snapshot update that completes the current operation.

The primitive hook is the lead's existing `action(args)` in bot.mjs: it waits for
`completedActionSequence >= result.sequence` **before** reporting success or
failure. Hydrate all slots, cursor, inventory mirror and specialized properties;
dispatch resulting events; then resolve the action. Even an accepted native click
can make no change (empty slot, illegal placement, full destination). clickWindow
resolves void for such valid no-ops. High-level operations inspect actual progress
and reject a stalled transfer instead of looping or decrementing an imagined count.
Do not wait for an updateSlot event as the only completion signal: no-op clicks
and unchanged crafting output can legitimately emit none.

Each operation binds `(world session, script lease, menu generation, menu id)`.
The existing menu IDs wrap after 100 opens; an ID alone is insufficient. Add a
monotonic generation when the actual menu instance changes, including close,
invalidity and inventory-menu return. Carry expected generation/id on every
click/close/button/name/trade command. Check stale commands before mutation.
A saved Window handle must never operate on a newly opened menu reusing its ID.
Reuse the existing native action sequence/revision barrier; do not invent a
second packet state-ID or transaction protocol. AbstractContainerMenu.stateId
is not a replacement for the generation and may not advance without a real
network synchronizer.

A lost action outcome ends the sequence: do not replay a click, reopen the menu,
retry a craft, or run automatic recovery clicks. Inspect the next authoritative
snapshot under the same lease when available and report partial progress/cursor;
otherwise report outcome unknown. A *known* rejection can run bounded recovery
only while the same menu/session remains valid. Prefer the original source slot,
then compatible inventory capacity. Explicit toss and putSelectedItemRange's
specified overflow path may drop; ordinary error cleanup must not silently toss.
Native close/removed may legitimately return or drop cursor/inputs under vanilla
rules: reconcile those results rather than promise lossless rollback.

## Public contracts to preserve

- `clickWindow(slot, mouseButton, mode)`, simpleClick.leftMouse/rightMouse,
  transfer(options), putAway(slot), putSelectedItemRange(start,end,window,slot),
  moveSlotItem(source,dest), equip(Item|id,destination), unequip(destination),
  toss(type,metadata,count), tossStack(Item), closeWindow(window), and craft
  resolve **undefined**. Open methods resolve the actual active **Window**, not
  an action record. Slot ranges are end-exclusive. Transfer defaults count to 1;
  metadata/nbt nullish means unconstrained. Preserve its optional nbt argument,
  although TransferOptions declarations omit it. Document invalid count/range
  rejection instead of permitting negative/fractional loops to mutate indefinitely.
- `openBlock(block,direction?,cursorPos?)` and `openEntity(entity)` attach close,
  deposit and withdraw to the same Window. openContainer/openChest/openDispenser
  share the source container classifier (generic rows/9x3, chest/barrel/hopper,
  shulker/ender/trapped chest and entity containers). Returned storage objects
  are decorated Windows; exported Chest/Furnace/etc declaration classes are not
  separate runtime classes constructed by these plugins. Preserve Window methods,
  slot-local Item.slot values, cursor, title/type/id, and existing class identity.
- `setQuickBarSlot(0..8)`, getEquipmentDestSlot and updateHeldItem stay synchronous;
  setQuickBarSlot returns void and immediately updates selected/held public state
  as upstream does. Queue its native select action in the same ordered action
  stream before subsequent clicks/use/equip, and drain pending controls before
  script completion. Native rejection must fail the script/next barrier and
  reconcile selection, not become an unhandled detached promise. This is a
  requested control change, not evidence that the server has acknowledged it.
  Native selectHotbar already exists; expose it without moving/recreating stacks.
- Slot coordinates of a container differ from inventory coordinates. Use native
  `inventorySlot` mappings when equipping or reflecting player slots; never send
  inventory slot 36 as if it always meant container hotbar slot 36. A single Item
  object cannot have two different slot coordinates; preserve identity within
  each Window rather than share one mutable Item.slot across both views.
- Source permits a current cursor to satisfy transfer's selected source; retain
  that behavior where safe. Reserve/restore an unrelated occupied cursor before
  a new high-level action, or reject before mutation if no safe storage exists.
  Do not silently swap arbitrary cursor contents into the first source slot.
- `craft(recipe,count?,craftingTable?)`: count is operations, not output items;
  source uses parseInt(count ?? 1,10), so 2 stick crafts yield 8 sticks. Preserve
  shaped orientation, null cells, shapeless ingredient placement, table-required
  failure and explicit table interaction. No autoapproach. Reuse Recipe queries
  as static discovery, not proof of native craftability/datapack availability.

### Authoritative transfer and crafting algorithms

For transfer, retain upstream's matching order and bulk-left/single-right click
strategy, but measure moved quantities from native slot/cursor snapshots after
each awaited click. Merge only stacks the native menu says are compatible. NBT
query filtering remains the public legacy filter; it must not cause named,
damaged, enchanted or component-distinct stacks to merge. Reject destination
full/no-progress with the actual remaining cursor and partial counts. putAway
must not quick-move a result slot when that would repeat craft/trade operations.
moveSlotItem checks the active Window cursor, not only bot.inventory.selectedItem.

For craft, clear/recover preexisting crafting inputs using real clicks, fill one
recipe operation's grid using Recipe.inShape/ingredients, then check the native
result slot before taking it. Empty/wrong result is a known crafting failure,
not permission to synthesize an Item. Pick up one result and store it before the
next operation; vanilla ResultSlot.onTake supplies ingredient consumption,
component-sensitive output, recipe unlock/XP hooks and remaining items. Recover
all actual remaining inputs/buckets through native slots, regardless of missing
Recipe.outShape data (the pinned 1.21.1 recipe table has none, including cake).
Do not copy craft.js's window.updateSlot result/ingredient edits. Close a table
opened by this operation after the final authoritative barrier. Preexisting
unrelated menus need explicit close/recovery handling before starting a craft.

## Exact native extensions and ownership

Proposed ownership for implementation (no assignments or edits performed here):

| Owner/module | Required change and source basis |
| --- | --- |
| Native: AgentHands.java | Extend clickMenu validation to allow `slot=-999` for PICKUP, buttons 0/1. Ordinary modes 0..4 map to PICKUP, QUICK_MOVE, SWAP, CLONE, THROW. Keep creative CLONE and slot/armor permissions native. Add generation-checked close; current closeMenu has no expected menu argument. |
| Native: AgentHands.java | Capture provider.getDisplayName() at openMenu as typed component/title; add menu generation. Keep slot roles/inventorySlot/crafting dimensions. With a nonempty carried stack expose each slot's `mayPlace(carried)`, `getMaxStackSize(carried)` and `ItemStack.isSameItemSameComponents(slotItem,carried)`; expose carried's actual max stack size. Existing `mayPickup` alone is insufficient. These are bounded observations of the already-open menu, not extra inventory access. |
| Native: AgentHands.java | Capture property arrays with a small ContainerSynchronizer attached to this fake player's menus: sendInitialData includes ALL data integers, including zero; sendDataChange updates the cached array; no-op slot/carried callbacks are sufficient if existing snapshots serialize those separately. A ContainerListener alone misses initial zero properties because DataSlot.checkAndClearUpdateFlag only reports changes. Attach before exposing a new menu; broadcast each tick as already done. Do not replace a real player's synchronizer. |
| Native: AgentHands.java / AgentActions.java | Add bounded generation-checked menu_button, anvil_name and select_trade actions: AbstractContainerMenu.clickMenuButton(this,choice); AnvilMenu.setItemName(name); MerchantMenu.setSelectionHint(index) then tryMoveItems(index), matching vanilla packet-handler logic. Validate correct menu, reach/validity, index, permissions; broadcast/save through existing action completion. |
| Native: specialized snapshot helper or AgentHands.java | MerchantMenu.getOffers() and getTraderXp/getTraderLevel/canRestock/showProgressBar; serialize actual costs/results through shared ScriptItems, plus uses/maxUses/price fields/disabled state. Include actual fake-player XP/level/enchantment seed as applicable, not body-health proxies. Costs and XP must persist with AgentHands state if they are gameplay-owned there. |
| Lead: GameAccess/AgentActions/runner | Carry generation and specialized properties in snapshots/actions; reuse scoped single-body lease, ordered completedActionSequence barrier and error/cancellation handling. Expose ordered synchronous-control enqueue/drain for hotbar/use, with errors owned by script execution. Host continues decoding every item wire with the existing decoder; no changes to lossless component transport. |
| Guest inventory module | Public operations, current-window correlation, one operation queue, bounded recovery, transfer planner; injected hooks for authoritative actions/current snapshot and lifecycle cancellation. Use existing Window/Item classes. |
| Guest craft/specialized helpers (can initially share that module) | Recipe placement and real result pickup; decorated Furnace/EnchantmentTable/Anvil/Villager methods; consume native properties rather than packet listeners. |
| Lead: bot.mjs hydration | Preserve Window per generation, Item identity for unchanged full component state, accurate inventory/menu mirroring, cursor and title. Emit updateSlot and updateSlot:N with old/new Items only for actual changes; all callbacks see complete state. Install methods before windowOpen. Emit bot.windowClose(oldWindow) on actual closure and storage.close once; dispose specialized subscriptions on forced closes too. |

For modes 5/6, Mineflayer docs explicitly call drag/double-click unimplemented
and Window.acceptClick throws. They remain pending applicability review in the
full goal, not new exclusions. Native support is straightforward but requires
separate scenarios: QUICK_CRAFT begin/add/end buttons encode drag type/header,
with -999 allowed only for begin/end and valid slots for add; PICKUP_ALL requires
its native slot/button rules. Do not enable them by bypassing validation or claim
upstream parity for functionality upstream itself lacks.

### Specialized and adjacent applicable APIs

Implement each decorator on the same native menu foundation; none should become
a generic no-op storage facade:

- Furnace/blast furnace/smoker: takeInput/takeFuel/takeOutput return the original
  **Item** after storage, putInput/putFuel resolve void, slot getters return Item
  or null. Properties 0..3 are current fuel/total fuel/current cook/total cook;
  expose source fuel/progress ratios and fuelSeconds/remaining progressSeconds,
  totals, and update events. Initialize complete properties before returning;
  packet-arrival timing quirks are not authoritative native values.
- EnchantmentTable: targetItem, putTargetItem, putLapis, takeTargetItem, enchant;
  costs, xpseed, expected enchant IDs/levels from properties 0..9. `enchant(choice)`
  returns the resulting Item after native XP/lapis consumption. putTargetItem and
  putLapis resolve void in source despite Promise<Item> declarations. Ready must
  be derived from hydrated options, with no listener race or indefinite wait.
- Anvil rename/combine resolve void; preserve supplied item order/optional name
  and the public 35-character rejection. Native output/cost and mayPickup decide
  actual applicability/XP. Pinned Item.anvil and plugin cost arithmetic are not
  sufficient for modern component-aware native results. Send one final name via
  native setItemName, not artificial 50ms-per-character protocol animation. Keep
  name clearing meaningful; source's falsy-name skip/creative XP wait are defects
  to identify in tests, not reasons to hang.
- Villager: openVillager waits for actual offers; expose trades, selectedTrade,
  inputs/outputs, realPrice, disabled/uses/maxUses/XP/demand/special price/multiplier.
  trade(villager,index,times) and window.trade resolve void; source nullish/falsy
  times means remaining uses. Choose native trade, fill actual requirements, take
  one output per operation, then recover leftovers; never increment uses locally.
  Keep unconsumed input costs distinct from demand-adjusted actual price to avoid
  double-applying the adjustment. New offer snapshots, not type/count guesses,
  establish discounts, component predicates, exhaustion and restocking.
- Inventory-adjacent public work remains applicable: activateItem(offHand)/
  deactivateItem synchronous controls and consume's completion must distinguish
  use-start from use-finished/interrupted; native useHeld currently uses mainhand
  only. activateBlock needs the actual supplied cursorPos, not checkedHit's chosen
  outline center; activateEntityAt needs hit-relative native interaction. Retain
  physical reach/obstruction and no autoapproach for all open operations.
- writeBook/signBook and creative.setInventorySlot/clearSlot/clearInventory need
  bounded native component mutations with vanilla permission/type/length checks,
  selection restoration and authoritative completion. Existing creativeItem
  creates a named default stack and is not a lossless arbitrary Item setter.
  Do not route general survival transfers through it. Books should follow native
  WRITABLE_BOOK_CONTENT/WRITTEN_BOOK_CONTENT and identity/author semantics, not
  upstream client-side NBT rewrites. These are additional work, not exclusions.
  Brewing/horse/beacon/stonecutter/loom/etc have Window/ordinary click behavior
  where upstream exposes it; there is no pinned openBrewingStand plugin to invent.
  Additional menu buttons needed for actual gameplay remain native extensions.

## Source discrepancies to record in differential evidence

Actual pinned source is the oracle for ordinary results, with explicit corrections
for harmful defects/documented gaps: transfer only treats **null** range ends as
single-slot while docs also allow undefined; withdraw rejects a full inventory
even if a compatible partial stack has room; NBT identity checks and Item.equal
ignore components; moveSlotItem checks the wrong cursor when a container is open;
putAway waits for slot events even on no-op paths; openBlock/openEntity launch
interaction without awaiting its failure; closeWindow optimistically closes before
ack (docs promise acknowledgement); extendWindow emits close on invocation and
server-close doesn't emit that storage close event; craft simulates results and
remainders; anvil's minimum-cost calculation can become zero. Preserve useful
signatures and truthful native completion; label deliberate timing/error fixes.
Do not claim packet event counts/order exactly match a source-only comparison.
StorageEvents declares open but inspected preparation emits bot.windowOpen, not
window.open; settle any added storage open event through an explicit contract case.

## Preauthored live scenarios

Lead alone creates fixtures in the guarded development world. Use survival first,
fixed known inventories, no background hopper/player changes except in designated
race tests. Independently inspect block-entity inventories, AgentHands saved
inventory/cursor/crafting state, dropped entities and XP; snapshot echo alone is
insufficient. Record before/after, script return/error, event arguments/order,
component wires and action sequence/revision. Run equivalent pinned reference
scripts only when a reference server is separately authorized; until then report
native slices and source/library comparisons separately.

| ID | Setup and calls | Required independent outcome |
| --- | --- | --- |
| I01 open | In-reach single chest has stone32; `w=await bot.openChest(block)`; also generic double chest, hopper, barrel, shulker, ender chest and chest minecart through aliases/openEntity. | Same `w===bot.currentWindow`, Window identity/title/layout/Item.slot correct, full contents before windowOpen/Promise resolution. One correlated open, no walking. Bad block/noncontainer/entity and obstructed/out-of-reach targets fail with no indefinitely pending listener. |
| I02 deposits | Inventory stone20+stone15, chest stone60, empty destination slot. `await w.deposit(stone,null,10)` then withdraw7. | Deposit makes chest64+6, source total25; withdraw restores source total32 and chest total63. Exact count across splits, cursor empty, events carry original old/new component values. Source identity for unchanged slots persists. |
| I03 full/merge | Fill all player slots but leave one compatible stone60; withdraw4 then withdraw1 with no room. | First succeeds by merging; second rejects without disappearance/duplication. Repeat full destination and insufficient source; record partial movement and actual cursor on failure. |
| I04 components | Two same-ID swords with distinct name/damage/enchantments; partially filled same-ID stacks with different custom components; component max-stack override. Transfer with/without legacy nbt filter. | Right matching selection, no incompatible merge, no component/default/removal loss; respect native capacity. Full inventory cannot be declared full merely from emptySlotCount if compatible room exists. |
| I05 clicks/split | Left pickup stack7, right-click empty, left return; right pickup odd stack7; outside right then outside left; THROW button0/1; QUICK_MOVE; SWAP 0..8 and40; CLONE in survival and creative. | Actual odd split is ceil(7/2), one-item placement/drop exact; native dropped entities retain components. Ordinary no-op click resolves; invalid slot/button/mode and survival clone cannot mutate. Window cursor updated before continuation. |
| I06 transfer ranges | Test count omitted/null/0, sourceEnd/destEnd omitted/null, single-slot and multi-slot ranges, nbt constraint, empty source, source==dest/overlap, invalid ID, negative/fractional/infinite count. | Documented defaults work; reject unsafe ranges/counts before destructive looping; no self-swap or unbounded recursion. Compare ordinary results to pinned source; record explicit defect corrections. |
| I07 cursor/move | Occupy cursor; transfer another type, moveSlotItem into empty/compatible/incompatible slots, putAway armor/result, putSelectedItemRange with source fallback and full inventory. | Preserve unrelated cursor or fail before action; successful move restores displaced Item to source. Only explicit upstream overflow/toss path drops. Result putAway takes one result, never shift-crafts/trades extras. |
| I08 hotbar/equip | Equip existing hotbar item, then inventory item with free/full hotbar; equip armor/offhand and unequip; setQuickBarSlot then immediately use/click. Repeat with container open and curse-of-binding armor. | Correct destination coordinates, LRU/free hotbar selection and native use ordering. Sync selection returns undefined; awaited equip sees final native stack. Armor permissions honored; no stack recreation. |
| I09 close | Close with empty cursor, occupied cursor, crafting inputs, full inventory; call both await w.close() and await bot.closeWindow(w). | Native removed() recovery/drop is independently accounted; currentWindow null and player inventory synchronized at resolve; bot/window close exactly once. Stale window close cannot close another menu. |
| C01 2x2 | Two planks arranged vertically, `await bot.craft(stickRecipe,2,null)` with enough inputs; shapeless planks/dye recipe; count default/null/0 and 1.9. | Two operations produce8 sticks and consume4 planks. Native output/ingredients authoritative, all actual leftovers recovered, cursor empty. Record parseInt count semantics without promising fractional crafts. |
| C02 3x3 | Exact chest/pickaxe ingredients and in-reach table; call with and without table, wrong/blocked/distant table; preexisting occupied grid. | Required-table error before mutation when absent; native table opens without walking, one correct output, no overwritten existing ingredients, final table closure after result barrier. |
| C03 remainders | Cake recipe with three milk buckets and exact other ingredients; run twice with two sets. | Each operation produces one cake AND three empty buckets; native ResultSlot remainders survive absent Recipe.outShape. Check all grid/inventory/drop locations, not recipe.delta. |
| C04 insufficiency | Missing final ingredient, destination full, recipe result blocked by datapack/disabled item, wrong recipe output, container invalidated between placements. | No invented result or silent success; report completed crafts/remaining material state. Do not replay an uncertain pickup or restore by copying guessed ingredient stacks. |
| W01 furnace | Input raw ore, coal, empty output in furnace/smoker/blast-furnace types. putInput/putFuel, observe update, takeOutput/takeFuel/takeInput. | Correct valid input restrictions, real progressing fuel/cook properties and seconds, Item return values, XP/output consumption; all listeners removed on forced close. Initial zero properties present. |
| W02 enchant/anvil | Known XP/lapis, enchantable item, fixed enchanting setup; enchant string/number choices; rename/combine native-valid and invalid/too-expensive cases. | Native XP/lapis/component results; ready/options/Item returns correct; creative doesn't hang awaiting XP change; actual name/cost rejection and cleanup checked. |
| W03 trade | Known villager offer, adjusted price, one/two-input offers, almost exhausted uses and insufficient funds; default times and explicit1. | Correct menu/offer readiness, actual price/inputs/output/uses/XP, no extra repeated shift-trades or guessed components; no local increment before native completion. |
| R01 closure race | Open B while retaining A, replace block/remove entity/invalidate reach during transfer, reuse ID after100 opens. | All A operations reject by generation; new window untouched. No listener leak, replacement/cursor state faithfully reconciled. |
| R02 interruption | Cancel after pickup, after placement, and after craft result; expire lease/session during outstanding action; artificially lose action reply after known server execution. | No automatic replay, no subsequent queued mutation. Known partial state exposed; unknown stays unknown. New session cannot consume old cursor recovery/action. Validate existing barrier also precedes rejected action continuation. |
| R03 event ordering | Handlers on bot.windowOpen/windowClose, w.updateSlot/updateSlot:N, heldItemChanged read current slots/cursor and enqueue another action. | Complete state visible inside callbacks; no queue deadlock, duplicate optimistic updates or resolving before native state; unchanged slots retain Items. |
| R04 specialized/adjacent | Write/sign real book with pages; creative full-component set/clear guarded by mode; interrupted consume and offhand use; explicit cursorPos/entityAt hit. | Real components, selection restored, permission/length errors bounded; use-start is not consume completion. Body reach never broadened by adapter. |

## Local source references and evidence boundary

Paths below are relative to the pinned reference node_modules (or project root).
Line anchors identify the inspected versions, not a promise of future offsets.

- mineflayer/lib/plugins/inventory.js:173 putSelectedItemRange; 275 transfer;
  360 extendWindow; 404 openBlock; 435 closeWindow; 475 window update waits;
  573 clickWindow; 677 putAway; 696 moveSlotItem; 717 prepareWindow; 856 syncWindow.
- mineflayer/lib/plugins/simple_inventory.js:22 tossStack; 43 unequip;
  51 synchronous selection; 88 equip; 128 destinations; 138 simpleClick.
- mineflayer/lib/plugins/craft.js:11 count/error/close; 39 craftOnce;
  136 putMaterialsAway; 145 synthetic grabResult; 153 updateOutShape;
  206 recipesFor. See recipes.mjs differential evidence separately.
- mineflayer/lib/plugins/chest.js:14 classifier/aliases; furnace.js:15/43/75;
  enchantment_table.js:7/30/74; anvil.js:11/45/73;
  villager.js:72/137; book.js:37/109; creative.js:27.
- mineflayer/index.d.ts:357–425 bot signatures; 606 TransferOptions;
  653–794 storage/decorated classes/events. docs/api.md headings for craft,
  transfer, closeWindow and clickWindow supply defaults and pending modes.
- AgentHands.java:233 useBlock; 279 interact; 299 selectHotbar;
  436 openMenu; 449 clickMenu; 468 closeMenu; 478 menuSnapshot;
  540 scriptSnapshot; 656 validMenu; 664 checkedHit.
  GameAccess.java:713 scriptSnapshot; bot.mjs:146 action/state barrier.
- Generated native sources: AbstractContainerMenu:122 set listener,
  129 setSynchronizer/sendAllDataToRemote, 167 broadcast, 284 clicked,
  302 QUICK_CRAFT, 370 outside PICKUP, 487 PICKUP_ALL, 546 removed;
  ResultSlot:61 onTake; ContainerSynchronizer full initial data interface;
  ServerGamePacketListenerImpl:660 rename, 769 trade selection, 784 books,
  1713 container button, 1729 creative; MerchantMenu public offers/getters.

Validation performed for this handoff: read pinned code/declarations/docs and
native generated source. No simulation, native fixture, reference client, game,
server, installation or lifecycle operation ran. All scenario rows remain unrun.
