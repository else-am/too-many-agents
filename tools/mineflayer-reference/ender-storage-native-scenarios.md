# Body-owned ender storage persistence

Preauthored before the persistence correction. No native execution yet.

Source finding: `AgentHands.save/restore` persisted ordinary inventory, cursor,
crafting and XP but omitted the native `PlayerEnderChestContainer`. AgentHands is
not a saved world Player, so its private ender contents disappeared when the
controller was reconstructed. Ordinary `openContainer` already reaches the
native ender-chest menu; this is an item-retention defect, not a new storage API.

Use only the isolated development world and an inspected disposable ender-chest
fixture within reach. Preserve the body's original items/components and selection.
Do not use a human player's ender inventory as agent storage.

1. Open the native ender chest and record existing contents. Deposit one uniquely
   named paper using the existing native clicks; close. Independently inspect the
   body's `too_many_agents_hands.enderItems` NBT for the native slot, count and
   components. Ordinary inventory must lose exactly that one paper.
2. Save/disconnect, stop the exact test JVM, reopen the same packaged artifact and
   body. Open the same ender chest: the paper must retain count/components and
   native slot. Withdraw it and close; confirm the saved ender slot is empty and
   ordinary inventory recovered exactly one item, without duplication.
3. Optional wider checks, not implied by the first two: another agent/human sees
   its own storage; a second ender-chest block accesses the same body's contents;
   partially filled stacks, cancellation and dimension transfers retain data.

The correction must use native `createTag(registryAccess)` and
`fromTag(list, registryAccess)` on the existing container. No guest serialization,
global ender inventory, fabricated slots or migration from human data. An absent
saved list initializes empty storage for older bodies. The focused native
save/reopen check remains pending until explicitly run.

## Ready scripts — preparation only

`observe-container-openers.js` uses an inspected single chest (default
111,-60,64), with no other viewer, power or obstruction. It records typed lid
events, retains the same native window for 12 ticks, closes and observes another
10 ticks, then briefly reopens/closes to check counter reuse. Expected counts are
exactly 1/0/1/0; zero while that window remains open or 255 is a failure. No item
clicks occur; inventory, selection and empty cursor must remain unchanged. It has
not run. This directly targets the previously observed proxy-opener counter bug,
without repeating piston/note or other menu checks.

`observe-ender-storage.js` has explicit `phase` (`deposit`/`withdraw`), inspected
`chestAt` (default 112,-60,64), and `enderSlot` (default26) constants. Before the
deposit call, choose an actually empty native ender slot without moving original
contents. Supply exactly one paper from a separate test barrel outside the call,
with native custom_name `Ender retention fixture` and one lore line `Preserved
through restart`. Use harmless plain text components. Record the original
inventory/equipment/selection and barrel before setup. Do not overwrite items or
touch Dev's ender inventory. The fixture coordinates are assumptions to inspect,
not claims about the saved world.

The script reads actual Item.customName/customLore through ChatMessage.fromNotch
and compares complete native components/counts. It requires the native 27-slot
ender window and maps ordinary storage via inventoryStart/hotbarStart. Two real
PICKUP clicks move the unique paper into an empty known slot, preserving all other
ender and ordinary slots; there is no outside click or implicit drop. The deposit
result contains a receipt with exact source slot, component-bearing item and
original snapshots. Save that result verbatim and independently inspect the
body's `too_many_agents_hands.enderItems` NBT, including Slot/count/components.

Save/disconnect/stop, then reopen the **same artifact and body**. For the withdrawal
call, change only phase to `withdraw`, retain the inspected coordinate/ender slot,
and replace `receipt=null` with the saved deposit result's `result` object. Record
each exact source/hash. Do not regenerate expected components from the reopened
decoder. The original ordinary source slot must still be empty. The script checks
persisted paper and unrelated ender contents against the pre-restart receipt,
returns the paper through native clicks to its original inventory slot and checks
the full original inventory snapshot. Independently verify the native ender slot
is empty and paper count/components recovered exactly once. Finally return only
that fixture paper to its test barrel and restore original belongings/selection.
Both phase scripts remain unexecuted; a future exact artifact/lifecycle task is
required. Unknown action outcomes stop without replay.
