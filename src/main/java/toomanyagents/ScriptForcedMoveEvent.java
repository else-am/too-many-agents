package toomanyagents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** A transient native teleport attempt. Only accepted operations notify watchers. */
public final class ScriptForcedMoveEvent extends Event {
    private static final ThreadLocal<ArrayDeque<ScriptForcedMoveEvent>> ATTEMPTS = ThreadLocal.withInitial(ArrayDeque::new);
    public final Entity root;
    public final ServerLevel level;
    private final List<Observation> observations = new ArrayList<>();

    private record Observation(Entity body, Vec3 position, float yaw, float pitch,
                               BooleanSupplier active, Runnable accepted, Consumer<String> failed) {}

    private ScriptForcedMoveEvent(Entity root, ServerLevel level) {
        this.root = root;
        this.level = level;
    }

    // Register only active controlled bodies, not the complete passenger tree.
    void watch(Entity body, BooleanSupplier active, Runnable accepted, Consumer<String> failed) {
        if (observations.size() >= 64) throw new IllegalStateException("script_forced_move_observer_limit");
        observations.add(new Observation(body, body.position(), body.getYRot(), body.getXRot(), active, accepted, failed));
    }

    private boolean includes(Entity entity) {
        if (root == entity) return true;
        for (var observation : observations)
            if (observation.body == entity || entity.hasIndirectPassenger(observation.body)) return true;
        return false;
    }

    /** Invokes native code exactly once; its return and exceptions remain native. */
    public static boolean observe(Entity entity, BooleanSupplier operation) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread())
            return operation.getAsBoolean();
        var attempts = ATTEMPTS.get();
        // randomTeleport's trial/rollback and nested calls for its passengers
        // belong to the outer acceptance decision, not separate observations.
        for (var attempt : attempts) {
            boolean included = false;
            try { included = attempt.includes(entity); }
            catch (RuntimeException failed) { attempt.failAll(); attempt.observations.clear(); }
            if (included) return operation.getAsBoolean();
        }
        var attempt = new ScriptForcedMoveEvent(entity, level);
        attempts.addLast(attempt);
        try {
            try { NeoForge.EVENT_BUS.post(attempt); }
            catch (RuntimeException failed) {
                attempt.failAll();
                attempt.observations.clear();
            }
            boolean accepted = operation.getAsBoolean();
            if (accepted) attempt.finish();
            return accepted;
        } finally {
            attempts.removeLast();
            if (attempts.isEmpty()) ATTEMPTS.remove();
        }
    }

    private void finish() {
        for (var observation : observations) {
            try {
                var body = observation.body;
                if (body.isRemoved() || body.level() != level || !observation.active.getAsBoolean()) continue;
                if (!observation.position.equals(body.position()) || observation.yaw != body.getYRot() || observation.pitch != body.getXRot())
                    observation.accepted.run();
            } catch (RuntimeException failed) { fail(observation); }
        }
    }

    private void failAll() { for (var observation : observations) fail(observation); }

    private static void fail(Observation observation) {
        try { observation.failed.accept("script_forced_move_observation_failed"); }
        catch (RuntimeException cleanup) {
            com.mojang.logging.LogUtils.getLogger().warn("Forced-move observer cleanup failed", cleanup);
        }
    }
}
