# Native entity event scenarios (before implementation)

Capture accepted LivingDamageEvent.Post and actual item-pickup completion, not
attempts/canceled damage or inventory guesses. Emit entityHurt(victim, source)
and playerCollect(collector, collected) with stable typed Entity references after
complete state/inventory hydration. The hands proxy maps to its visible body.
Capture item metadata before pickup removes the entity, including pickups wholly
between snapshots. Preserve the original item when the remaining stack is empty.
Only active scripts in the same dimension and observed radius receive events.
Bound per-script event records/bytes and fail explicitly on overflow/serialization
failure; never let an observation failure break the native damage/pickup callback.
Clear queued events on script/world release; no cross-script replay.

Focused fixture: nearby cow takes one accepted hit from the actual body; callback
sees lower health and the same victim/attacker objects. A canceled/immune hit does
not fabricate a damage event. Pick up a named component-bearing item and verify
playerCollect identity/data plus independent native inventory/removed entity.
Repeated state frames must not re-emit events. A source outside observation range
remains undefined. Native events beyond damage/collection remain pending.

Implementation Java/plugin/package build passed. Native fixtures remain unrun.
The queue permits 64 events / 1 MiB serialized event data per script between
frames. Event-only entities remain observable for the event frame and are removed
on the next frame if absent; current native observations override older event-time
state. Player item pickup includes the visible body proxy, and its XP/arrow take callback; other Mob
pickup paths still need native sources. No broad event-conformance claim.

Before the final build, also include the body's native take callback for XP orbs
and arrows (item stacks already use the post-pickup event). Avoid duplicate item
notifications. Validate that the callback still maps to the visible collector;
other mobs' take implementations remain a separate source-coverage task.

## Death event extension (before implementation)

Capture LivingDeathEvent and defer its cancellation check until snapshot delivery,
after every native listener has finished. One accepted nearby death must emit
entityDead with the existing victim Entity after authoritative state hydration;
canceled death must emit nothing. Observe loot/removal independently. A kill and
removal entirely between frames must still retain the event's typed victim.
Ordinary later frames and script release must not replay the event. This is a
nearby entity event, not a fabricated death/respawn cycle for protected bodies.

Native aad8063 packaged check passed once with Codex gpt-6.1-sol low.
One empty-hand body attack delivered entityHurt with the same cow/body objects
and hydrated Health 20 -> 17. A new completed look gated the exact cow kill;
entityDead retained the same victim with Health 0, alive false, and undefined
cause. A second completed look gated one named paper pickup: playerCollect saw
the actual body, the event-frame Entity, original shared Item/count 1/name
component, and inventory paper count 1 -> 2 already hydrated. Five later ticks
did not replay the selected events. Independent native NBT confirmed hurt/death
health and the named paper in body inventory; the item entity was removed.
All original inventory stacks/components, elytra and shield were preserved; native
cow loot additionally supplied three beef. The script used 3 completed requests,
28 updates and 9 bridge operations (1446 ms script, 1923 ms tool). Canceled/immune
damage, canceled death, other-mob pickup and overflow cases were not exercised.

## XP orb observation (before correction)

A native ExperienceOrb exposes Mineflayer's type `orb` and count equal to the
native getValue()/spawn packet XP value. Observe an orb with Value7 and merged
Count3 outside pickup range: count must be7, not3 or21; entitySpawn must receive
that already-hydrated same Entity. Independently inspect native orb NBT. Other
entities must not acquire a count from generic observation. Collection/XP credit
remain a separate scenario, not established by an observation or compilation.
