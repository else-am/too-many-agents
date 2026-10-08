# Dropped-item and deprecated tick events

Preauthored contract for the existing metadata observation path:

- Observe a newly spawned native ItemEntity with a named/component-bearing stack.
  `entitySpawn`, `entityUpdate`, then `itemDrop` receive the same shared Entity;
  `getDroppedItem()` already returns the actual typed Item and components.
- Unchanged frames, position changes and unrelated metadata changes do not
  replay `itemDrop`. A changed nonempty native Item stack does emit it again.
- A collected entity that appears only in an event frame retains the captured
  pre-pickup stack. Current observations win if the entity still exists; an
  empty stack does not report a new drop. Inventory must already be hydrated
  before the existing `playerCollect` callback.
- Firework and item-display metadata do not report loose item drops. Pinned
  Mineflayer's metadata handler emits `itemDrop` for any item_stack serializer;
  this deliberately limits the event to actual nonempty dropped-item entities.
- Deprecated `physicTick` follows `physicsTick` once for each observed new tick,
  as in pinned physics.js. It does not start another physics simulation.

Sources: pinned Mineflayer `lib/plugins/entities.js` entity_metadata handler,
`parseMetadata`; `lib/plugins/physics.js:85-86`; `index.d.ts` BotEvents.
The adapter samples native snapshots, not every network packet. No unchanged
packet replay or intermediate unobserved transition is promised.

Native verification remains unrun. Use one named paper fixture outside pickup
range, created only after a NEW completed look readiness action. Read its UUID,
Item count/components independently. After a second readiness action, change
only that exact fixture's stack count once and verify the second event. Preserve
the user's items, do not repeat an unknown action, and remove only this fixture.

## Ready observer — prepared, unrun

`observe-item-drop.js` covers the nonempty spawn/change contract and paired
physicsTick/physicTick ordering. Syntax validation is not native evidence.
Use the actual body at a supported safe origin and an inspected loaded drop cell
at least4 blocks away, inside the observation area. No matching named paper may
exist initially. Keep nearby collectors away; give this one native ItemEntity
PickupDelay32767 and no moving water/fire hazards. Normal gravity remains on.
Record original cells and all original inventory/components before setup.

After the first NEW completed native look action, summon one paper with count1
and the exact string custom name `Observed drop fixture`. Record its native UUID,
Item/components and position. After the second NEW completed look (the observer
has already verified the first event and ten unchanged ticks), change only that
exact ItemEntity's Item.count to2 once. Retain command result/native NBT and the
new count. This is a deliberate fixture mutation, not agent inventory creation.
Do not infer readiness from a pre-invocation message or fixed delay.

The script requires shared Entity/metadata identity, typed Item hydration and
spawn→update→drop order, then update→drop for the changed stack. It observes ten
unchanged ticks after each phase without replay and verifies the deprecated tick
alias follows physicsTick exactly once per observed tick. Independent native
checks must confirm exact UUID, nonempty count2 and unchanged body inventory.
After known script termination remove only that fixture and restore owned cells;
never retry an unknown command. Native execution, empty-stack/collection and
other serializer negative cases remain separately unverified.
