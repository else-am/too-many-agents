package toomanyagents.mixin;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.ScriptEntitySignalEvent;
import toomanyagents.ScriptParticleEvent;

@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @Inject(method = "broadcastEntityEvent", at = @At("RETURN"))
    private void observeStatus(Entity entity, byte code, CallbackInfo callback) {
        NeoForge.EVENT_BUS.post(new ScriptEntitySignalEvent(entity, code, false));
    }

    @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I", at = @At("RETURN"))
    private void observeBroadcast(ParticleOptions options, double x, double y, double z, int count,
                                  double dx, double dy, double dz, double speed, CallbackInfoReturnable<Integer> result) {
        NeoForge.EVENT_BUS.post(new ScriptParticleEvent((ServerLevel) (Object) this, null, options,
            false, x, y, z, count, dx, dy, dz, speed));
    }

    @Inject(method = "sendParticles(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/particles/ParticleOptions;ZDDDIDDDD)Z", at = @At("RETURN"))
    private void observeTargeted(ServerPlayer recipient, ParticleOptions options, boolean longDistance,
                                 double x, double y, double z, int count, double dx, double dy, double dz,
                                 double speed, CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValue()) NeoForge.EVENT_BUS.post(new ScriptParticleEvent((ServerLevel) (Object) this,
            recipient, options, longDistance, x, y, z, count, dx, dy, dz, speed));
    }
}
