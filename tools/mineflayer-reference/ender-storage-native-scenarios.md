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
