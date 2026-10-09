package toomanyagents.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import toomanyagents.ScriptForcedMoveEvent;

@Mixin(LivingEntity.class)
abstract class LivingEntityTeleportMixin {
    @WrapMethod(method = "randomTeleport(DDDZ)Z")
    private boolean observeAcceptedRandomTeleport(double x, double y, double z, boolean particles, Operation<Boolean> original) {
        return ScriptForcedMoveEvent.observe((LivingEntity) (Object) this, () -> original.call(x, y, z, particles));
    }
}
