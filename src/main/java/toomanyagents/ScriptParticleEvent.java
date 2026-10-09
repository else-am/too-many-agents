package toomanyagents;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;

/** Native emission observed after sendParticles, without altering delivery. */
public final class ScriptParticleEvent extends Event {
    public final ServerLevel level;
    public final ServerPlayer recipient;
    public final ParticleOptions options;
    public final Vec3 position, offset;
    public final int count;
    public final float speed;
    public final boolean longDistance;

    public ScriptParticleEvent(ServerLevel level, ServerPlayer recipient, ParticleOptions options,
                               boolean longDistance, double x, double y, double z, int count,
                               double dx, double dy, double dz, double speed) {
        this.level = level;
        this.recipient = recipient;
        this.options = options;
        this.longDistance = longDistance;
        this.position = new Vec3(x, y, z);
        this.offset = new Vec3((float) dx, (float) dy, (float) dz);
        this.count = count;
        this.speed = (float) speed;
    }
}
