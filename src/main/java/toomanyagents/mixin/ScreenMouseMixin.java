package toomanyagents.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.screens.ScreenInput;

/** Keep native cursor capture and route its relative movement to the focused browser. */
@Mixin(MouseHandler.class)
abstract class ScreenMouseMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void moveOnScreen(double time, CallbackInfo info) {
        if (ScreenInput.mouseMove(accumulatedDX, accumulatedDY)) info.cancel();
    }
}
