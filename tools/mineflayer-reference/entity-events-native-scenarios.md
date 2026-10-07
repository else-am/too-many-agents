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
