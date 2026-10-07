# Native elytra start (before implementation)

Public Mineflayer `elytraFly()` is async and starts gliding; it does not promise
arrival or automatic flight planning. Native Player.tryToStartFallFlying rejects
ground/water/already-flying/levitation and checks the actual chest stack's
canElytraFly. LivingEntity already owns glide physics, wing durability, game
events and ending flight on landing/equipment loss. Start the visible body flag,
not the unregistered interaction proxy's flight. Expose its real elytraFlying,
isInWater/isInLava observations.

Use the existing native Post-tick travel for ordinary inherited living physics;
do not simulate player ballistics or change body dimensions. Reject overridden
travel profiles explicitly pending their own integration. Preserve velocity
through look/inventory/other action completion while the script is gliding.
Reject ground-path navigation and creative hover while gliding. Script release
or world cleanup clears only flight started by this script, then ordinary gravity
and existing boundary safeguards continue. Never persist NoGravity or flight.

Focused native fixture, when installation is available:

- Equip an actual usable elytra, begin airborne over an isolated clear runway,
  call elytraFly, then observe genuine forward/descent motion and flight flag.
  Promise resolves after authoritative state; same-script look changes direction
  without zeroing momentum. No exact coordinate/ballistic match required.
- After at least 20 native flight ticks, independently check wing durability and
  components. Land: native flag clears and inventory mirrors remaining wings.
- Ground/water/levitation/broken or absent wings/already-flying/mounted cases reject
  without launching, creating equipment, or stopping another valid action.
- Release/cancel mid-air: owned flag and controls clear, velocity is not replaced
  by a teleport, body falls normally and no script control survives its lease.
  Stale world/body requests reject; do not replay unknown starts.

Firework boosting, special species travel, flight boundary/lifecycle combinations
and full native verification remain separate pending work. No public glider route
planner is invented.
