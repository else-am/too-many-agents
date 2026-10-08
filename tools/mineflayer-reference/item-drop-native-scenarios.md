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
