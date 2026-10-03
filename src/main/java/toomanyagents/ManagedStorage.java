package toomanyagents;

import java.nio.file.Path;
import java.util.UUID;

/** Persistent managed working files; runtime state stays in the Minecraft instance. */
public final class ManagedStorage {
    public static Path root() {
        String override=System.getProperty("too_many_agents.dataDir","");
        if(override.isBlank()) override=System.getenv().getOrDefault("TOO_MANY_AGENTS_DATA_DIR","");
        return (override.isBlank()?Path.of(System.getProperty("user.home"),".too-many-agents"):Path.of(override)).toAbsolutePath().normalize();
    }
    static Path world(String worldId) {
        // Saved world identity is a UUID, not a user-provided path component.
        return root().resolve("worlds").resolve(UUID.fromString(worldId).toString());
    }
    private ManagedStorage() {}
}
