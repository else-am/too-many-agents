package toomanyagents;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** Supplements one native container query with existing, unregistered hands. */
public final class ScriptContainerOpenersEvent extends Event {
    final ServerLevel level;
    final AABB bounds;
    final Predicate<? super Player> ownsContainer;
    final List<Player> players;

    private ScriptContainerOpenersEvent(ServerLevel level, AABB bounds,
                                        Predicate<? super Player> ownsContainer, List<Player> players) {
        this.level = level;
        this.bounds = bounds;
        this.ownsContainer = ownsContainer;
        this.players = new ArrayList<>(players);
    }

    public static List<Player> includeHands(Level level, AABB bounds,
                                            Predicate<? super Player> ownsContainer, List<Player> players) {
        if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()) return players;
        var event = new ScriptContainerOpenersEvent(serverLevel, bounds, ownsContainer, players);
        try {
            NeoForge.EVENT_BUS.post(event);
            return event.players;
        } catch (RuntimeException failure) {
            // An observer must not interrupt the native scheduled tick or alter
            // the original human-player query when it fails.
            com.mojang.logging.LogUtils.getLogger().warn("Container opener observation failed", failure);
            return players;
        }
    }
}
