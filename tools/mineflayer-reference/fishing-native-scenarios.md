# Native fishing scenarios (written before implementation)

Use the guarded world, a real fishing rod and a loaded open-water pool. Aim from
shore, call fish(), and independently observe the bobber, native bite, generated
loot/XP and rod durability. Completion means native retrieval, not guaranteed
inventory pickup or a particular randomized fish. Do not advance a fake bite timer
or create loot for the adapter. Compare against the pinned fishing.js public
async/void contract, accepting native bite state instead of particle heuristics.

Missing/wrong rod must reject before casting. A replacement fish() cancels its
predecessor and waits for scoped cancellation before another cast. Lost replies
abort without replay. Script cancellation, timeout, world departure and rod loss
must remove the owned bobber without reeling for loot. Other bodies' bobbers stay
untouched. A caught entity is not a fish bite and must not be silently reeled.

Native-source boundary: FishingHook requires a Player owner. AgentHands already
supplies the body-owned server interaction proxy and inventory. The server hook
can use this owner, but vanilla client reconstruction rejects an owner absent from
the client player list. BodyFishingHook supplies a render-only owner adapter and excludes the visible
body from projectile collisions. Verify visible bobber/line and no client owner
reconstruction errors; implementation alone does not prove that behavior.

Live checks remain unrun until recorded against an actual packaged build.
