# Recent observation ports — prepared, not executed

The three scripts below are syntax-checked only. They do not establish native
conformance. Run against one explicitly selected packaged artifact after tester
availability is restored, using the existing isolated development world. Record
its revision, JAR hash, actual invocation times, raw result and independent native
evidence. Do not substitute an old installed plugin for the selected artifact.

The existing pending checks remain in [remaining-native-fixtures.md](remaining-native-fixtures.md)
and [observe-metadata.js](observe-metadata.js). These additions do not require
rerunning previously successful gameplay suites.

## Coordination and preservation

One coordinator owns the client lifecycle. Re-observe the body, surroundings,
inventory, equipment, selection, mode and weather before setup. Use only inspected
disposable fixture cells; adjust the script's constants before invocation if the
specified cells contain retained objects. Do not clear arbitrary regions.

Each look gate means a **new completed native look action**, after the previous
gate's ID. Record the preceding action ID before starting the script. A message
sent before the tool invocation is not listener readiness. The coordinator must
observe the new ID before its single corresponding fixture mutation. If readiness
or an action outcome is uncertain, stop without replay. Scripts use at most 1,200
ticks per gate; allow a 240-second deadline for multiple gates, not repeated calls.

Native commands are fixture preparation/observation, not an implementation of the
guest API. Keep human Dev away from orb pickup and chest viewing. After known
outcomes, restore only this fixture's changes, preserve belongings, save/disconnect
and stop only the verified test JVM. Failed scripts can leave fixture changes;
inspect rather than assuming rollback.

## Registry and XP-orb observation

Run [observe-registry-orb.js](observe-registry-orb.js) in the ordinary overworld
with empty cursor/menu and no existing observed XP orbs. It reads all three native
codec sections, dimension bounds, shared biome identity, chat formatting, and
independence of exported codec copies. It does not import modified registries or
test custom datapacks.

After its new completed look, summon one uniquely tagged native experience orb
on inspected floor at least nine blocks from both body and Dev, within observation
range. Use native `Value:7s,Count:3` and zero motion; `NoGravity:1b` may hold the
fixture in place. Resolve and record the actual UUID and independent native
Value/Count. The public `Entity.count` must be **7**, the XP value, not the merged
orb count 3. The listener checks hydrated type `orb`, shared Entity/Vec3 references
and unchanged inventory. The native attraction radius is eight blocks. Keep it outside that radius during the five followup
ticks. Remove that exact orb after recording the outcome. No XP pickup event,
reward or merging behavior is claimed.

## Accepted teleport callbacks

Run [observe-forced-move.js](observe-forced-move.js) with an awake, grounded,
unmounted body, no active controls and empty cursor/menu. Inspect its exact origin
and origin +2 X: both must be clear, loaded, on level dry floor, inside applicable
bounds, and away from vehicles or other forced movement.

At the first new completed look, teleport only the exact body UUID to origin +2 X.
Record native position before/after. At the second new completed look, teleport
it back to the recorded origin. Each accepted move should emit one zero-argument
`forcedMove` after the shared body/position objects hydrate. Ordinary looks and
stationary frames should not emit it. The script uses a 0.2-block position tolerance.

This checks two accepted direct teleports only. Rejected random teleports,
passengers, no-op rotations, cross-dimension replacement and multiple moves in
one frame remain outside this fixture.

## Note, piston and single-chest events

[observe-block-events.js](observe-block-events.js) currently expects the body near
(110.5,-60,64), a single chest at (111,-60,64), a note block at (115,-60,64) and an
upward-facing ordinary piston at (115,-60,67). Inspect those cells and nearby
power sources first. Preserve existing fixtures rather than overwriting them.

Prepare the note unpowered with air above and a known note/instrument from actual
native state. With stone below, explicitly set `instrument=basedrum` and inspect
the settled state; command-default harp may change on a vertical neighbor update.
The pinned Block property `note` is a numeric string, while the note event uses a
number; the ready script converts the property for comparison. Prepare the piston unextended with empty upward movement space.
Prepare the chest within native reach, unobstructed, unpaired, with no other
viewer. Record original fixture cells/NBT and choose separate inspected adjacent
power cells that cannot cross-power another fixture.

At successive new completed look gates: (1) place one adjacent redstone power
source for the note, (2) power the piston, (3) remove that piston power. Observe
native note state and actual piston extension/retraction independently. Do not
use a guessed event injection. The script then opens and closes its own chest.
It expects one note, extend/retract and lid counts 1/0, typed Blocks and the shared
registry instrument, with unchanged inventory and no replay over ten ticks.

After recording the outcome, remove note power and restore only inspected fixture
changes. Double-chest orientation, other containers, breaking progress, unloaded
blocks, directly addressed packets and overflow remain unverified by this check.
