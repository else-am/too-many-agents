package toomanyagents.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import toomanyagents.ScriptNavigation;

/** Substitute input only during the original leased route's explicit native tick. */
@Mixin(Phantom.PhantomMoveControl.class)
abstract class PhantomMoveControlMixin extends MoveControl {
    protected PhantomMoveControlMixin(Mob mob) { super(mob); }

    @Redirect(method = "tick", at = @At(value = "FIELD",
        target = "Lnet/minecraft/world/entity/monster/Phantom;moveTargetPoint:Lnet/minecraft/world/phys/Vec3;"), require = 3, allow = 3)
    private Vec3 selectedTargetPoint(Phantom body) {
        return ScriptNavigation.selectedPhantomPoint(body, this);
    }
}
