package toomanyagents.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.ScriptNavigation;

/** End only an owned reviewed route before native baby metadata refreshes dimensions. */
@Mixin(AgeableMob.class)
abstract class ScriptAgeDimensionsMixin {
    @Inject(method = "onSyncedDataUpdated", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/AgeableMob;refreshDimensions()V"))
    private void beforeAgeDimensions(EntityDataAccessor<?> key, CallbackInfo callback) {
        if ((Object) this instanceof Bee bee) ScriptNavigation.beforeBeeAgeDimensions(bee);
        else if ((Object) this instanceof Panda panda) ScriptNavigation.beforeGroundWrapperDimensions(panda);
        else if ((Object) this instanceof Camel camel) ScriptNavigation.beforeGroundWrapperDimensions(camel);
        else if ((Object) this instanceof Sniffer sniffer) ScriptNavigation.beforeGroundWrapperDimensions(sniffer);
        else if ((Object) this instanceof Turtle turtle) ScriptNavigation.beforeTurtleDimensions(turtle);
        else if ((Object) this instanceof Axolotl axolotl) ScriptNavigation.beforeAxolotlAgeDimensions(axolotl);
    }
}
