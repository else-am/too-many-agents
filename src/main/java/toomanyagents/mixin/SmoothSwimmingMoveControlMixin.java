package toomanyagents.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import toomanyagents.ScriptNavigation;

/** Preserve real navigation activity outside this exact leased controller tick. */
@Mixin(SmoothSwimmingMoveControl.class)
abstract class SmoothSwimmingMoveControlMixin extends MoveControl {
    protected SmoothSwimmingMoveControlMixin(Mob mob) { super(mob); }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;isDone()Z"))
    private boolean selectedRouteActivity(PathNavigation navigation) {
        return !ScriptNavigation.selectedTadpoleControl(mob, this, navigation) && navigation.isDone();
    }
}
