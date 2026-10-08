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

## Prepared narrow Fox/body check (not run)

`observe-mob-collection.js` uses an adult Fox named `Collection fox`, grounded in
a dry enclosure at least four blocks from the controlled body and inside its
observation radius. Resolve its exact native UUID before invocation. Set
CanPickUpLoot true, Age 0, NoAI false and empty HandItems; exclude nearby food,
other loose items and threatening entities. Preserve existing fixtures/items.
Normal AI is required for native pickup. Verify the Fox is awake and the body
inventory has room, with no menu/cursor. Do not force pickup or fabricate take.

The listener precedes each new forced look. Observe the new completed native
look ID before spawning exactly one PickupDelay 0 paper at the Fox's current
native position, named `Fox collection paper` with a plain custom_name component.
The pinned Fox calls onItemPickup, splits its one-item stack into MAINHAND, then
calls take with count zero before discarding the drop. This fixture checks the
same-tick original Item capture, stable typed collector/event-frame drop,
hydrated equipment and unchanged body inventory. Paper is not edible; the
enclosure must exclude other items that could make the Fox legitimately swap it.
If held paper is absent, inspect native HandItems before claiming observer failure.

After five no-replay ticks, a second distinct completed look authorizes one
`Body collection paper` at the body's current native position. Verify exactly
one body callback, the original typed Item/count/name, and authoritative named
inventory gain already visible during that callback. This is a focused repeat
because generic native take wiring now suppresses the forwarded ItemEntity
duplicate while retaining its existing Post source. Both gates allow 1200 ticks;
use a sufficiently long runner deadline and never resend an unknown mutation.

Independently record native Fox HandItems, both removed tagged drops, body item
components/count and actual native body name versus returned bot.username. Keep
all unrelated belongings intact. Record action IDs, phase times and counts.
No hurt/death, XP/arrow, other-species or overflow checks are included. This file
records preparation only; compilation/source inspection is not a native pass.

Native packaged 67ebb2c check passed once with Codex gpt-6.1-sol low. The Fox
callback retained the original shared named paper Item/count 1 and hydrated
equipment after its zero-count take; body inventory stayed unchanged. Own-body
pickup emitted exactly once with named count 0 -> 1 already hydrated. Both
selected callbacks retained typed identities and did not replay over five ticks.
Native HandItems, removed tagged drops and body inventory confirmed the results.
bot.username was ScriptProbe, matching the native body's name. The script used
2 completed requests, 34 updates and 8 bridge operations (1755 ms script,
2266 ms tool). No XP/arrow, other-species or overflow cases ran.
