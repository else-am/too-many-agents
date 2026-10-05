package toomanyagents;

import com.google.gson.JsonObject;
import java.util.List;

/** Minecraft settings saved separately from BB execution and conversation settings. */
public final class BodySettings {
    public static JsonObject copy(JsonObject settings) {
        var result = new JsonObject();
        if (settings == null) return result;
        for (String key : List.of("name", "body", "mode", "behaviors", "stationId", "minecraftAccess"))
            if (settings.has(key)) result.add(key, settings.get(key).deepCopy());
        return result;
    }

    public enum Mode {
        SURVIVAL("survival", "Survival", false, false),
        CREATIVE("creative", "Creative", true, false),
        CREATIVE_COMMANDS("creative_commands", "Creative + commands", true, true);

        public final String id, label;
        public final boolean creative, commands;

        Mode(String id, String label, boolean creative, boolean commands) {
            this.id = id; this.label = label; this.creative = creative; this.commands = commands;
        }
    }

    public static Mode mode(String id) {
        for (var mode : Mode.values()) if (mode.id.equals(id)) return mode;
        throw new IllegalArgumentException("invalid_agent_mode: " + id);
    }

    /** Roles reuse body settings without replacing identity or a world's station assignment. */
    public static JsonObject profile(JsonObject settings) {
        var result = copy(settings);
        for (String key : List.of("name", "stationId")) result.remove(key);
        return result;
    }

    private BodySettings() {}
}
