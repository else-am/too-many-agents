package toomanyagents.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import toomanyagents.RemoteTerrain;

@Mixin(ClientLevel.class)
abstract class RemoteEntitiesMixin {
    @Inject(method = "entitiesForRendering", at = @At("RETURN"), cancellable = true)
    private void remoteEntities(CallbackInfoReturnable<Iterable<Entity>> result) {
        result.setReturnValue(RemoteTerrain.visibleEntities((ClientLevel) (Object) this, result.getReturnValue()));
    }
}
