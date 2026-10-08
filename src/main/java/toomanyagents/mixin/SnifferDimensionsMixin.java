package toomanyagents.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.ScriptNavigation;

/** Native state changes proceed after the captured route releases its old geometry. */
@Mixin(Sniffer.class)
abstract class SnifferDimensionsMixin {
    @Inject(method = "onSyncedDataUpdated", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/animal/sniffer/Sniffer;refreshDimensions()V"))
    private void beforeStateDimensions(EntityDataAccessor<?> key, CallbackInfo callback) {
        ScriptNavigation.beforeGroundWrapperDimensions((Sniffer) (Object) this);
    }
}
