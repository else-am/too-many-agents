# Creative API scenarios (before implementation)

Creative slot setters must roundtrip real Item component patches through the
native Slot codec, including removed components, nested inventories, Unicode NBT,
long values and inline registry holders. Reuse the 74 independently authored wire
fixtures as exact encoding expectations; add reverse registry remapping and
modified UTF-8 expectations before the encoder implementation. Reject malformed,
cyclic, oversized, unknown or inconsistent input before native inventory writes.

Native checks: in survival, set/clear rejects without mutation; creative mode
permits a specified inventory slot overwrite (including clear) and returns only
after authoritative inventory state. Preserve unrelated slots/cursor, support
native component-bearing Items and offhand, and leave actual current windows
coherent. The result slot cannot be set. Native feature flags, component/save
codecs, max counts and actual menu generation remain enforced. No optimistic
slots or silent item drops. An explicit creative overwrite is intentional.

clearInventory uses one queue and snapshots its intended nonempty slots, with
truthful partial completion on native failure. Calls made without awaiting must
not be silently drained like synchronous controls. Test native independent NBT
and persistence in the final combined check; source fixtures are not live proof.

Creative flight remains a separate pending native-body slice: familiar start/
stop/flyTo contracts must retain bounds, loaded space, cancellation and actual
body movement without copying the upstream player-physics mutation directly.

## Implemented and checked

The native slot setter and shared queue adapter compile/package successfully.
`node tools/mineflayer-reference/item-wire.mjs --encode` passed 74 literal wire
fixtures, reverse item/component registry mapping (including nested Slots),
modified UTF-8 and own prototype keys, malformed encode inputs, and the existing
61 decoder comparisons/13 malformed-wire rejections/QuickJS transport checks.
These bytes predate the encoder. No live Java creative-slot operation has run.

Deliberate corrections: result slot 0 rejects rather than resolving after the
server silently ignores it; native offhand slot 45 is accepted; waitTimeout=0
still waits for authoritative native state. Equality does not skip writes just
because upstream Item.equal ignores components. clearInventory is serialized
with other inventory operations and reports completedSlots after partial failure.
Native creative block-entity copying observes the body's region bounds.

## Flight contract (before implementation)

startFlying/stopFlying return void and participate in the existing ordered control
drain. flyTo awaits real body arrival and leaves flight enabled until stop/release.
Use native collision movement in bounded steps, never assign a guest position or
teleport through obstacles. Hover/control state belongs to the script lease;
release/world change/mode loss restores ordinary gravity without persisting a
NoGravity edit to the body. Manual jump/sneak move vertically while flying.

Focused native checks: reject survival activation before displacement; creative
flyTo reaches a clear elevated destination, ticks keep advancing and hover holds;
stopFlying permits falling/landing; script release also restores ordinary travel.
A solid wall, unloaded destination, world border and body region reject without
crossing them. Cancellation during flight leaves actual partial position and no
lingering flight lease. Do not run an exhaustive trajectory matrix. start/stop
controls during another active body action retain the existing busy fence.

Flight Java/plugin/package build passed. No native flight test has run. Hover is lease-local and preserves the body's stored gravity setting. Ground navigation requires stopping flight first.
