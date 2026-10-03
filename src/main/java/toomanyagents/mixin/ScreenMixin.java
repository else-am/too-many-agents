package toomanyagents.mixin;

import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import toomanyagents.KeyboardModifiers;

@Mixin(Screen.class)
abstract class ScreenMixin {
    @Inject(method = "hasControlDown", at = @At("HEAD"), cancellable = true)
    private static void commandFromKeystroke(CallbackInfoReturnable<Boolean> result) {
        Integer modifiers = KeyboardModifiers.CURRENT.get();
        // On macOS this method means Command. A missed release must not change later keystrokes.
        if (modifiers != null) result.setReturnValue((modifiers & GLFW.GLFW_MOD_SUPER) != 0);
    }
}
