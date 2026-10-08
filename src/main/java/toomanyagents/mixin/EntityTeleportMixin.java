package toomanyagents.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import toomanyagents.ScriptForcedMoveEvent;

@Mixin(Entity.class)
abstract class EntityTeleportMixin {
    @WrapMethod(method = "changeDimension(Lnet/minecraft/world/level/portal/DimensionTransition;)Lnet/minecraft/world/entity/Entity;")
    private Entity observeSameLevelTransition(DimensionTransition transition, Operation<Entity> original) {
        var entity = (Entity) (Object) this;
        if (transition.newLevel() != entity.level()) return original.call(transition);
        // Preserve the actual nullable/replacement result, not a derived boolean.
        Entity[] result = new Entity[1];
        ScriptForcedMoveEvent.observe(entity, () -> {
            result[0] = original.call(transition);
            return result[0] == entity;
        });
        return result[0];
    }

    @WrapMethod(method = "teleportTo(DDD)V")
    private void observePosition(double x, double y, double z, Operation<Void> original) {
        ScriptForcedMoveEvent.observe((Entity) (Object) this, () -> {
            original.call(x, y, z);
            return true;
        });
    }

    @WrapMethod(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z")
    private boolean observeSameLevel(ServerLevel level, double x, double y, double z,
                                     Set<RelativeMovement> relative, float yaw, float pitch, Operation<Boolean> original) {
        var entity = (Entity) (Object) this;
        if (entity.level() != level) return original.call(level, x, y, z, relative, yaw, pitch);
        return ScriptForcedMoveEvent.observe(entity, () -> original.call(level, x, y, z, relative, yaw, pitch));
    }
}
