package toomanyagents.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import toomanyagents.ScriptNavigation;

/** Native idle stays idle; only the exact leased active preparation borrows activity. */
@Mixin(Guardian.GuardianMoveControl.class)
abstract class GuardianMoveControlMixin extends MoveControl {
    protected GuardianMoveControlMixin(Mob mob) { super(mob); }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;isDone()Z"))
    private boolean selectedRouteActivity(PathNavigation navigation) {
        return !ScriptNavigation.selectedGuardianControl((Guardian) mob, this, navigation) && navigation.isDone();
    }
}
