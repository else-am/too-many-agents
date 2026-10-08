package toomanyagents.mixin;

import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.AgentHands;

/** FakePlayer drops these packets; observe only our own body recipient. */
@Mixin(targets = "net.neoforged.neoforge.common.util.FakePlayer$FakePlayerNetHandler", remap = false)
abstract class FakePlayerPresentationMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void observe(Packet<?> packet, CallbackInfo callback) { capture(packet); }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"))
    private void observe(Packet<?> packet, PacketSendListener listener, CallbackInfo callback) { capture(packet); }

    @Unique
    private void capture(Packet<?> packet) {
        if (((ServerGamePacketListenerImpl) (Object) this).player instanceof AgentHands hands)
            hands.observePresentation(packet);
    }
}
