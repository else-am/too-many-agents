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

Direct controls (before implementation): setControlState/getControlState,
clearControlStates, and controlState property access preserve synchronous
signatures. One bounded control queue preserves invocation order with equipment
and actions. Test forward/back/strafe relative to actual yaw, jump and release,
sprint/sneak flags, collisions and body-box confinement. Native mob physics owns
acceleration/jumping; no player-trajectory equality or player-only ledge hold is
promised. End/cancel/world departure clears held inputs. Do not silently blend
manual controls with a Pathfinder route: require releasing held inputs before
starting navigation, and report a known busy failure if a route is already active.

Consumption (before implementation): use actual LivingEntity eating/drinking
and shield use on the visible body, with native finish events establishing
completion. A potion must apply its effect to that body; milk must clear its
actual effects and return a bucket; food consumes without inventing a player
hunger field on mobs. Creative bodies retain the original stack. Interrupted
consumption rejects, and script cleanup stops use without releasing a projectile.
Inventory must adopt the native resulting hand stack before the promise resolves.

Mounting (before implementation): native entity interaction must put the visible
body, not its invisible hands, on the vehicle. mount/dismount return void; vehicle
and mount/dismount events come from observed native relationships. Check a boat,
occupied/rejected ride, dismount and reopening; riding must not also run standalone
body travel. Vehicle steering remains separate pending work.

## Sign text (before implementation)

Open an unwaxed native sign with activateBlock, then updateSign synchronously and
await a tick/control drain. Check front/back text and blank lines through native
block-entity NBT. Reject waxed signs, another editor, stale observed block state,
out-of-reach signs, more than four lines and lines over 45 characters. Preserve
sign color/glow and existing back/front text on the untouched side. Editing
requires the native sign editor acquired by interaction; it is not a remote setter.
