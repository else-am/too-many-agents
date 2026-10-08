package toomanyagents.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.ScriptVehicleControls;

/** Unowned entities take the unchanged native movement path. */
@Mixin(Entity.class)
abstract class VehicleMovementMixin {
    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void ownedMovementBounds(MoverType type, Vec3 delta, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        if (!ScriptVehicleControls.allowMove(entity, delta)) callback.cancel();
    }
}
