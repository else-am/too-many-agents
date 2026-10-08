# Vehicle control scenarios (before implementation)

Use an existing boat in a guarded open-water fixture. Mount the actual body in
its controlling seat. moveVehicle(0,1) must return undefined and produce forward
native motion; turn input must change heading, and moveVehicle(0,0) must remove
paddle/input acceleration while allowing ordinary coasting. Compare meaningful
movement, not player-client coordinates. Dismount and script cancellation must
release this body's inputs. Another controlling passenger must not be overridden.

Check clear/manual controls while mounted and reject unsupported mounts truthfully.
Keep body-box, loaded-region and world-border constraints; do not teleport the
vehicle. Verify native vehicle/body positions and rider IDs independently.

The original adapter covers Boat and subclasses, including chest boats. Preserve
these scenarios while extending the non-boat controls below.

# Native vehicle control contracts (preauthored)

Source oracle: generated Minecraft 1.21.1 / NeoForge 21.1.251,
`LivingEntity.aiStep/travelRidden`, `Mob.getControllingPassenger`,
`AbstractHorse.tickRidden/getRiddenInput/getRiddenSpeed/onPlayerJump`, their
Camel/Pig/Strider overrides, `LocalPlayer.aiStep` riding charge,
`AbstractMinecart.moveAlongTrack/applyNaturalSlowdown`, and pinned Mineflayer
`entities.js moveVehicle` (steer input, not vehicle coordinates).

- Saddled tame horse, donkey/mule and native inherited horse variants: actual
  body stays first passenger/controller; forward/strafe/reverse use native
  rider input and attributes. Observe native step/collision and exactly one
  vehicle travel per normal tick; no extra Post-tick vehicle travel.
- Horse hold/release jump charges through the native riding scale/cooldown;
  releasing the script while charging cancels the pending input without a
  phantom jump. Camel sitting/standing, sprint and dash remain its overrides.
- Unsaddled/untamed/baby mounts, non-controlling rider tags, another first
  passenger, removed/changed mount, and native unsteerable llama: explicit
  rejection, no mounting, taming, saddling, inventory creation or takeover.
- Saddled pig/strider require the correct existing stick in either real hand.
  They use native always-forward input, native boost state, strider warmth and
  fluid travel. Back/strafe do not invent horse-style abilities. Losing the
  stick/saddle releases input. This adapter preserves an existing boost factor;
  triggering boost through a proxy-held item needs separate native verification.
- Ordinary rideable minecart: actual passenger preserved; derive the rider's
  motion input with native input/friction helpers. Existing native rail code
  owns its low-speed push threshold, braking, powered rails, slope, curves,
  cap and drag. Test start, coast, reverse at low speed and powered/activator
  rails. No lateral cart steering, commanded velocity or second rail tick.
- Every native movement checks the current script lease, actual controller,
  world/loading/border/body-box sweep. Changed mount/lease/action error clears
  only owned input. Native coast/vertical momentum survive release; a denied
  owned movement is cancelled before it runs, without snapping positions.
- Sneak uses native dismount. Stop/manual input release, explicit dismount,
  permission loss, timeout and world departure remove helper ownership, held
  inputs and jump charge. A new rider's controls are never cleared.
- Preserve the existing tested boat path, including its native paddle control.

These are source-derived scenarios for root's native checks. Standalone Java
compilation is not a native/mixin trajectory or packaged conformance test.

## Boundary and native-input interpretation

An invalid lease/eligibility check cancels the current owned travel/rail/move
call only when the same body remains its rider. Bounds failures cancel the
owned call and clear input/ownership while reporting the script stream failure;
no exception escapes that physics callback. A replacement rider is untouched.
Subsequent unowned native momentum, gravity and AI remain native, so release is
not permanent containment. Check both the denied tick and later native coast.
Rail preflight conservatively covers cell alignment and slope adjustments
(which use native setPos internally); tight boxes may reject control early.

LivingEntity.aiStep reaches ordinary travel even with Mob NoAI: isEffectiveAi
only gates serverAiStep there. The adapter enables effective travel for that
one native ridden call and restores NoAI in finally; it adds no tick. Test both
ordinary and NoAI saddled mounts and verify NoAI remains unchanged afterward.

The minecart input view uses the body’s native attribute, input normalization,
yaw, friction and grounded/airborne acceleration to feed vanilla’s Player-only
low-speed push read. It does not reproduce a network player’s accumulated
velocity, assign cart velocity, or change the real passenger. This is native
body input compatibility, not player trajectory parity. Horse flight likewise
retains the real Mob controller’s native airborne acceleration. Native boost
activation, species trajectories and mixin runtime behavior remain live checks.

## Native mounted item context (before integration)

The interaction proxy's read-only getVehicle/getControlledVehicle queries should
delegate to its visible body. FoodOnAStickItem.use checks both isPassenger and
getControlledVehicle before native boost and native item damage/conversion; the
proxy itself must never be inserted into the passenger list. Retain Minecraft's
actual controller predicate, including no boost when it reports no controller.
Verify one boost/item-cost on an ordinary eligible saddled pig or strider and
unchanged real passenger identity. Wrong item/seat and an already active boost
must retain native outcomes. NoAI movement adaptation does not itself grant item
boost eligibility. Other item paths and constructor-time null body use retain
ordinary proxy behavior. Build/source checks are not a native boost pass.
