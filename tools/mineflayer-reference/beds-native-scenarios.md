# Native bed scenarios (before implementation)

Compare `isABed` and `parseBedMetadata` with pinned Mineflayer for every 1.21.1
bed state. Sleep/wake promises must settle after authoritative body state, with
`entitySleep`/`entityWake` and self `sleep`/`wake` events seeing updated fields.

Use a complete unoccupied bed at night within reach in the guarded world. Sleep
from its foot and wake: independently inspect actual body SleepingX/Y/Z, pose,
bed occupancy and native stand-up position. Repeat sleeping in the already
occupied bed must reject without moving another sleeper. Wrong block, stale
state, daytime, obstruction, distant bed and outside-body-box positions reject.
A bed removed while asleep must yield a real wake observation. Script release
retains intentional sleep, like mounting; world unload must not invent a wake.

Use native LivingEntity sleeping/standing and block occupancy on the actual Mob,
with native player interaction permissions and sleep checks adapted to exclude
that same body from its own hostile-mob search. Do not put the FakePlayer to bed.
The Mob is not added to the server player list or sleeping-player count; sleeping
does not establish a player respawn point or force the night to skip. Preserve
native unsafe-dimension bed interaction behavior; do not manufacture success.

Final narrow live validation is pending. No artificial pose-only result counts.
