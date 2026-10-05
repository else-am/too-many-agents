package toomanyagents;

import com.google.gson.JsonObject;
import java.util.List;

/** Minecraft settings saved separately from BB execution and conversation settings. */
public final class BodySettings {
    public static JsonObject copy(JsonObject settings) {
        var result = new JsonObject();
        if (settings == null) return result;
        for (String key : List.of("name", "body", "mode", "cheats", "following", "followReturn", "color", "behaviors", "stationId", "minecraftAccess"))
            if (settings.has(key)) result.add(key, settings.get(key).deepCopy());
        if (result.get("behaviors") instanceof JsonObject behaviors) migrateBehaviors(behaviors);
        return result;
    }

    /** Keep existing choices when loading the old four-state behavior settings. */
    static void migrateBehaviors(JsonObject behaviors) {
        if (!behaviors.has("wants_you")) {
            var previous = behaviors.has("needs_input") ? behaviors.get("needs_input") : behaviors.get("done");
            if (previous != null) behaviors.add("wants_you", previous);
        }
        behaviors.remove("needs_input");
        behaviors.remove("done");
    }

    /** Profiles reuse body behavior without replacing identity or a world's station assignment. */
    public static JsonObject profile(JsonObject settings) {
        var result = copy(settings);
        for (String key : List.of("name", "color", "stationId")) result.remove(key);
        return result;
    }

    private BodySettings() {}
}
