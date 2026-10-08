package toomanyagents.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Drowned;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import toomanyagents.ScriptNavigation;

/** Preserve navigation state; adapt only this exact leased controller activity query. */
@Mixin(Drowned.DrownedMoveControl.class)
abstract class DrownedMoveControlMixin extends MoveControl {
    protected DrownedMoveControlMixin(Mob mob) { super(mob); }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;isDone()Z"))
    private boolean selectedRouteActivity(PathNavigation navigation) {
        return !ScriptNavigation.selectedDrownedSwimControl(mob, this, navigation) && navigation.isDone();
    }
}
