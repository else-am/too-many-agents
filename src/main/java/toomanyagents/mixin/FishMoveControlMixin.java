package toomanyagents.mixin;

import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import toomanyagents.ScriptNavigation;

/** Only this leased selected route's controller tick substitutes native path activity. */
@Mixin(AbstractFish.FishMoveControl.class)
abstract class FishMoveControlMixin {
    @Shadow @Final private AbstractFish fish;

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;isDone()Z"))
    private boolean selectedRouteActivity(PathNavigation navigation) {
        return !ScriptNavigation.selectedFishControl(fish) && navigation.isDone();
    }
}
