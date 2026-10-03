package toomanyagents.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import toomanyagents.KeyboardModifiers;

@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @WrapMethod(method = "keyPress")
    private void dispatchWithModifiers(long window, int key, int scanCode, int action, int modifiers, Operation<Void> original) {
        if (!Minecraft.ON_OSX) {
            original.call(window, key, scanCode, action, modifiers);
            return;
        }
        Integer previous = KeyboardModifiers.CURRENT.get();
        KeyboardModifiers.CURRENT.set(modifiers);
        try {
            original.call(window, key, scanCode, action, modifiers);
        } finally {
            if (previous == null) KeyboardModifiers.CURRENT.remove();
            else KeyboardModifiers.CURRENT.set(previous);
        }
    }
}
