package toomanyagents.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.ScriptNavigation;

/** End only an owned Bee route before native baby metadata refreshes dimensions. */
@Mixin(AgeableMob.class)
abstract class BeeAgeDimensionsMixin {
    @Inject(method = "onSyncedDataUpdated", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/AgeableMob;refreshDimensions()V"))
    private void beforeAgeDimensions(EntityDataAccessor<?> key, CallbackInfo callback) {
        if ((Object) this instanceof Bee bee) ScriptNavigation.beforeBeeAgeDimensions(bee);
    }
}
