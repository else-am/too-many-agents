# Combat and synchronous use controls

Contract before implementation: pinned Mineflayer 4.39.0 entities.js exposes
attack(target, swing=true), swingArm(arm='right', showHand=true), useOn(target)
as synchronous void calls. inventory.js exposes synchronous activateItem(offhand)
and deactivateItem(). None approaches a target. Native completion is drained
before returning the enclosing script; these are not promise-returning aliases.

Important checks, after integration:

- Select a sword, attack a reachable ordinary native body, equip another item.
  Return values are undefined; native mutations occur in invocation order.
  Inspect target health and damage attribution independently. Attacker is the
  actual body; damage, enchantments and retaliation use native mob combat.
- Swing either hand without attacking; attack with swing=false. Animation must
  not itself hurt anything. Missing, distant, obstructed or self targets cause
  no damage and surface a terminal control failure, without autoapproach.
- Repeated ordinary melee attempts obey the native MeleeAttackGoal 20-tick
  interval; an attempt in cooldown is a no-hit outcome, not delayed/replayed.
  Native body weapon durability follows mob combat, not player combat. These
  body adaptations must not be described as identical player damage/timing.
- Activate offhand shield then deactivate; inspect native active hand and final
  usingHeldItem state. Main-hand use retains the existing native item pipeline.
  A same-type active-hand equip change must not leave an old item in use.
- Finish immediately after a synchronous call: final native state must arrive
  before script completion. Unknown action outcomes poison the queue; later
  attacks/use/equip must not run. Known control failures surface at final drain.
- Pending scope: consumption completion/effects on native bodies, fishing,
  mounts, body-specific combat without an ATTACK_DAMAGE attribute, and combat
  events. This slice does not claim those contracts.

Entity placement (before implementation): placeEntity(reference, face) resolves
an observed Entity, and emits entityPlaced with that same object. Cover survival
armor stand/end crystal/spawn egg/boat placement, offhand options, occupied or
invalid destination and missing inventory. Native item use owns requirements,
consumption and spawn. Identify the entity created during this action, not an
older nearby match; return no success if it cannot be observed. No creative item
creation. Water boat placement must use native fluid ray tracing. Cancellation
or transport uncertainty never retries a spawn.
