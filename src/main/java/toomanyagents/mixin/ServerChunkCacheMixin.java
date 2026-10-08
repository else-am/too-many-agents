package toomanyagents.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.ScriptEntitySignalEvent;

@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
    @Inject(method = {"broadcast", "broadcastAndSend"}, at = @At("RETURN"))
    private void observeAnimation(Entity source, Packet<?> packet, CallbackInfo callback) {
        if (!(packet instanceof ClientboundAnimatePacket animation)) return;
        var entity = animation.getId() == source.getId() ? source : source.level().getEntity(animation.getId());
        if (entity != null) NeoForge.EVENT_BUS.post(new ScriptEntitySignalEvent(entity, animation.getAction(), true));
    }
}
