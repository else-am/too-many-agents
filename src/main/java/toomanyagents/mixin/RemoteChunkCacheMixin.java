package toomanyagents.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import toomanyagents.RemoteTerrain;

@Mixin(ClientChunkCache.class)
abstract class RemoteChunkCacheMixin {
    @Shadow @Final private ClientLevel level;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("RETURN"), cancellable = true)
    private void remoteChunk(int x, int z, ChunkStatus status, boolean required, CallbackInfoReturnable<LevelChunk> result) {
        if (result.getReturnValue() != null && !(result.getReturnValue() instanceof EmptyLevelChunk)) return;
        var chunk = RemoteTerrain.chunk(level, x, z);
        if (chunk != null) result.setReturnValue(chunk);
    }
}
