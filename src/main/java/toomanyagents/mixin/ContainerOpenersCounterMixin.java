package toomanyagents.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import toomanyagents.ScriptContainerOpenersEvent;

@Mixin(ContainerOpenersCounter.class)
abstract class ContainerOpenersCounterMixin {
    @WrapOperation(method = "getPlayersWithContainerOpen", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<Player> includeHands(Level level, EntityTypeTest<Entity, Player> type,
                                     AABB bounds, Predicate<? super Player> predicate, Operation<List<Player>> original) {
        var players = original.call(level, type, bounds, predicate);
        return ScriptContainerOpenersEvent.includeHands(level, bounds, predicate, players);
    }
}
