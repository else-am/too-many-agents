package toomanyagents.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.screens.ScreenInput;

/** In typing mode, keys and text go to the screen instead of moving the player. */
@Mixin(KeyboardHandler.class)
abstract class ScreenTypingMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void typeOnScreen(long window, int key, int scanCode, int action, int modifiers, CallbackInfo info) {
        if (ScreenInput.keyPress(key, scanCode, action, modifiers)) info.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void typeTextOnScreen(long window, int codePoint, int modifiers, CallbackInfo info) {
        if (ScreenInput.charTyped(codePoint, modifiers)) info.cancel();
    }
}
