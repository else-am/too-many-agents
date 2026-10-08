package toomanyagents.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.ScriptVehicleControls;

@Mixin(AbstractMinecart.class)
abstract class VehicleMinecartMixin {
    @Inject(method = "moveAlongTrack", at = @At("HEAD"), cancellable = true)
    private void ownedRailBounds(BlockPos rail, BlockState state, CallbackInfo callback) {
        if (!ScriptVehicleControls.allowRailTick((AbstractMinecart) (Object) this)) callback.cancel();
    }

    @Redirect(method = "moveAlongTrack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/AbstractMinecart;getFirstPassenger()Lnet/minecraft/world/entity/Entity;"))
    private Entity ownedPushInput(AbstractMinecart cart) {
        return ScriptVehicleControls.minecartInput(cart, cart.getFirstPassenger());
    }
}
