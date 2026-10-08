# Nearby native collection (before implementation)

Extend `playerCollect` to native LivingEntity take notifications, including nearby
mobs and player XP/arrow pickup. Keep the existing player ItemEntity post-pickup
source: it preserves the original stack after native inventory insertion. The
body's forwarded ItemEntity take must not duplicate that event.

Capture the item at `onItemPickup` before a Fox or inventory carrier empties its
stack, and use it only for the same entity's take in the same native tick. Piglin
take occurs before extraction and can use the still-present stack. A preparation
callback alone must emit nothing. Zero-count native take is still an event: the
pinned native Fox empties its one-item stack before calling take. Observe native
notifications; do not fabricate pickups from proximity or inventory differences.

Focused future packaged check: a nearby adult, pickup-enabled zombie equips one
named item; the listener receives the same typed collector, original typed Item
with components, and hydrated equipment. Independently inspect native equipment
and removed drop. A Fox pickup checks metadata after its stack split. Own-body
item pickup must still emit exactly once. XP/arrow notifications need a separate
focused check when a suitable fixture is available. Later frames must not replay.

No new controls, ownership permissions or world mutations are added by this
observer. Existing same-world/radius/lease bounds and authoritative event hydration
apply. Species overriding take without the native notification remain an explicit
source-coverage limit. Compilation alone is not native validation.
