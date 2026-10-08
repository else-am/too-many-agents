# One prepared native horse jump — 62bde2f (UNRUN)

Preparation only: no game, provider, build or test execution was performed.
`observe-horse-jump.js` needs only the fresh horse's exact UUID substituted;
run it once with `minecraft_run` timeoutMs45000 after coordinator authorization.
This is one ordinary stationary charge/release/landing scenario, not a horse
variant, effect, charge-duration or cancellation matrix.

## Source contract

`ScriptVehicleControls.java:133–174` samples held jump during the owning mount's
ordinary travel call: initial press resets charge, subsequent held ticks charge,
and release calls `onPlayerJump(floor(charge*100))` then `handleStartJump` for
positive strength. It then calls native `travelRidden` once with AgentHands as
input only. The actual body remains the passenger. This follows generated
`LocalPlayer.aiStep:806–832`; `PlayerRideableJumping:3–14` supplies the interface
and default zero cooldown. The positive control wait drains the queued native
input before counting ticks (`bot.mjs`, waitForTicks).

Crucially `AgentActions.java:689–699` does not destroy an existing mounted
controller when the last held key (jump) becomes false; `applyControls:880–884`
updates its jump flag, permitting the next native travel to process release.
`ScriptVehicleControls.close:84–95` clears pending horse jump and owned inputs
on cancellation instead of releasing a charged jump. Do not call clearControlStates
between the charge and release: this fixture must exercise normal release.

Generated `AbstractHorse.onPlayerJump:940–954` requires the saddle and sets the
native pending scale. `canJump:958–959` checks saddle. `handleStartJump:963–966`
owns standing/sound, and `tickRidden:768–785` consumes pending scale while grounded.
`executeRidersJump:813–824` uses native jump power, vertical velocity and jump hook;
extra horizontal impulse requires forward input, which this script never sets.
`getRiddenInput:794–805` therefore supplies zero horizontal input. Normal-AI mount
control remains authoritative through `Entity.isControlledByLocalInstance` and
`Mob.getControllingPassenger`; the proxy is never inserted as a rider.

No concrete implementation gap in this ordinary hold/release path was established
by source inspection. Steering passes alone do not establish jump execution.
Public `horse.onGround`, `position`, `velocity`, `health` are native observations
(`Observations.entity:30–52`); the mounted body's onGround is not the jump oracle.

## Fixture and readiness (coordinator only)

- Use a **fresh** healthy adult `minecraft:horse`, normal AI (`NoAI:0b`), tamed,
  saddled in its actual native inventory (`Tame`, `SaddleItem`), no passengers,
  no effects, panic, leash, recent damage or altered movement/jump attributes.
  Independently record UUID, Health, saddle/tame/AI state before execution. Do not
  reuse a drifting horse from a historical steering attempt.
- Prepare a dry level solid floor and an open-top holding pen with a 3x3 interior
  and solid walls high enough to contain ordinary pre-mount wandering. At least
  eight clear vertical blocks above the interior floor allow the horse and rider
  to jump; no overhangs, slabs, fluids, nearby entities or loose items. Provide
  a safe exterior floor/apron for normal dismount. Use the authorized fixture
  area inside loaded world/border/body-box limits, with room above for the rider.
- Place the body on that safe apron adjacent to the horse, grounded and unmounted,
  empty main hand, no open menu/cursor or held controls. Preserve its existing
  inventory/equipment and selected slot; any coordinator preparation/restoration
  happens outside this script and is independently recorded. The closed pen
  limits normal-AI drift; no gate opening or mid-script teleport is needed for
  this stationary jump. Keep the horse within actual interaction reach and no
  more than 2.5 blocks center-to-center at script entry. Arrange the body on the
  apron, not in a wall. If the ordinary mount cannot reach or the native fixture
  moves out of reach, retain failure; do not autoapproach or replay mount.
- Readiness is the script's initial state guards, then acknowledged actual sole
  rider identity and grounded support. Native AI/tame/saddle facts must come from
  setup observations, not invented guest properties. Listener registration
  precedes controls. Twelve charged ticks, one jump=false release and at most70
  landing-wait ticks give bounded execution; no precise height/timing prediction.
  A normal horse may vary in jump attributes. Rise >0.35, observed airborne
  descent, two grounded frames and landing within0.35 of the flat floor establish
  meaningful jumping without incidental coordinate parity.

## Evidence and cleanup

Retain the full script result or first error/recentActions/logs. It records
bounded per-tick horse y/vertical velocity/onGround and actual passenger UUIDs,
checks that holding alone did not launch it, then releases controls/zero input
and dismounts after landing. Health, all inventory slots, cursor/menu and selected
slot must match. No error-handler mutation or automatic retry is present; unknown
outcome remains terminal and scoped cleanup owns release.

Independently capture native exact-horse Passengers during the charge/jump if
practical (only the actual body UUID, no Player proxy), and final empty Passengers,
healthy horse/body, grounded support, retained saddle/tame/NoAI0 and unchanged
inventory. Inspect completed control actions and confirmed body release. Do not
infer native success merely from a jump animation or a successful control request.
This script's normal success explicitly clears inputs/dismounts; a failed call
must be diagnosed from its retained state before any separately authorized cleanup.
No speed, velocity, jump scale, teleport or health manipulation occurs in the script.
