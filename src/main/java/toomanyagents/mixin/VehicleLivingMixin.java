package toomanyagents.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import toomanyagents.ScriptVehicleControls;

/** Supply native ridden input at the mount's ordinary travel call. */
@Mixin(LivingEntity.class)
abstract class VehicleLivingMixin {
    @Redirect(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;travel(Lnet/minecraft/world/phys/Vec3;)V"))
    private void ownedRiderInput(LivingEntity mount, Vec3 input) {
        if (!ScriptVehicleControls.travel(mount, input)) mount.travel(input);
    }
}
