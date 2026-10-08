package toomanyagents.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.animal.Pufferfish;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.ScriptNavigation;

/** End only the captured route before native puff geometry can reposition its body. */
@Mixin(Pufferfish.class)
abstract class PufferfishSizeMixin {
    @Inject(method = "onSyncedDataUpdated", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/animal/Pufferfish;refreshDimensions()V"))
    private void beforePuffDimensions(EntityDataAccessor<?> key, CallbackInfo callback) {
        ScriptNavigation.beforePufferfishSizeChange((Pufferfish) (Object) this);
    }
}
