package toomanyagents;

import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.Event;

/** A server-emitted entity animation or status, independent of player recipients. */
public final class ScriptEntitySignalEvent extends Event {
    public final Entity entity;
    public final int code;
    public final boolean animation;

    public ScriptEntitySignalEvent(Entity entity, int code, boolean animation) {
        this.entity = entity;
        this.code = code;
        this.animation = animation;
    }
}
