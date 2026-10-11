package toomanyagents.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.screens.Screens;

/** Stops Chromium as the game closes, after the world has saved and while the main thread can still finish it. */
@Mixin(Minecraft.class)
abstract class MinecraftCloseMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void stopChromium(CallbackInfo info) {
        Screens.shutdown();
    }
}
