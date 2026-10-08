package toomanyagents.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import toomanyagents.ScriptNavigation;
import toomanyagents.ScriptVehicleControls;

/** Unowned entities take the unchanged native movement path. */
@Mixin(Entity.class)
abstract class VehicleMovementMixin {
    @Inject(method = "onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;refreshDimensions()V"))
    private void beforeCamelPoseDimensions(EntityDataAccessor<?> key, CallbackInfo callback) {
        if ((Object) this instanceof Camel camel) ScriptNavigation.beforeGroundWrapperDimensions(camel);
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void ownedMovementBounds(MoverType type, Vec3 delta, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        if (!ScriptVehicleControls.allowMove(entity, delta) || !ScriptNavigation.allowMove(entity, delta)) callback.cancel();
    }

    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"), cancellable = true)
    private void selectedRouteCollisionSweep(MoverType type, Vec3 wanted, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        // Piston, stuck-speed and edge preprocessing may have changed wanted.
        if (!ScriptNavigation.allowMove(entity, wanted)) {
            entity.level().getProfiler().pop();
            callback.cancel();
        }
    }

    // The first lengthSqr is stuckSpeedMultiplier; the second is native collide's
    // result, before any position change. Only that Vec3 is live after the args.
    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;lengthSqr()D", ordinal = 1),
        cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD)
    private void selectedRouteDisplacement(MoverType type, Vec3 wanted, CallbackInfo callback, Vec3 clipped) {
        Entity entity = (Entity) (Object) this;
        if (!ScriptNavigation.allowClippedMove(entity, clipped)) {
            entity.level().getProfiler().pop(); // balance Entity.move's preceding "move" push
            callback.cancel();
        }
    }
}
