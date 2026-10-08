# Native elytra rockets (contract before implementation)

FireworkRocketItem.use checks Player.isFallFlying, but our body is a Mob and its
interaction proxy is not gliding. Adapt that narrow use branch by constructing
Minecraft's existing FireworkRocketEntity(level, stack, actualLivingBody), after
ordinary item-enable/cooldown/right-click-event checks. Native rocket tick owns
acceleration, lifetime, collision and explosion; don't simulate a player boost.

- With an actual airborne gliding body and usable elytra, main/offhand rocket use
  creates one native rocket attached to that body and consumes one real item in
  survival (retains it in creative). Held components and unrelated slots survive.
  Denied spawn/use or no gliding must not consume an item or report usedFirework.
- Hydrate entityElytraFlew on the false→true transition, usedFirework(native id)
  once per observed attached rocket, and fireworkRocketDuration from actual native
  remaining ticks. This deliberately replaces upstream's independently random
  estimate. Multiple rockets report time until the last active rocket expires;
  native physics still applies each actual rocket independently.
- Native boost changes body velocity; no proxy boost or second synthetic impulse.
  Duration counts down and becomes zero on landing/expired boost; unchanged frames
  don't replay usedFirework. Script cleanup ends script-owned gliding and inputs;
  it does not refund or pretend to erase an already fired physical projectile.
- Focused live check remains pending with the earlier elytra/creative-flight slice.
  Use a guarded clear flight volume, actual inventory and native rocket attachment
  evidence, then landing/cleanup. Do not infer native success from compilation.
