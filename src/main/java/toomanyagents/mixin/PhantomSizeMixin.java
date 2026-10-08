package toomanyagents.mixin;

import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.ScriptNavigation;

/** End the captured route before native size refresh can reposition directly. */
@Mixin(Phantom.class)
abstract class PhantomSizeMixin {
    @Inject(method = "updatePhantomSizeInfo", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/monster/Phantom;refreshDimensions()V"))
    private void beforeSizeRefresh(CallbackInfo callback) {
        ScriptNavigation.beforePhantomSizeChange((Phantom) (Object) this);
    }
}
