package toomanyagents.agent;

import com.google.gson.JsonObject;
import java.util.List;
import com.google.gson.Gson;

/** The permission choice shared by providers and their child agents. */
public final class AgentPermissions {
    private static final Gson JSON = new Gson();
    private AgentPermissions() {}
    private static String string(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : "";
    }
    private static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) result.add((String)pairs[i], JSON.toJsonTree(pairs[i + 1]));
        return result;
    }

    public static JsonObject normalize(JsonObject settings) {
        if (settings != null && settings.has("permissionMode") && (!settings.get("permissionMode").isJsonPrimitive()
                || !settings.getAsJsonPrimitive("permissionMode").isString()))
            throw new IllegalArgumentException("permissionMode must be a string.");
        String mode = settings == null ? "" : string(settings, "permissionMode");
        if (mode.isEmpty()) mode = "accept-edits";
        if (!List.of("accept-edits", "auto", "full").contains(mode))
            throw new IllegalArgumentException("Unsupported permission mode: " + mode);
        return object("permissionMode", mode);
    }

    public static JsonObject field() {
        return object("key", "permissionMode", "label", "Permission mode", "default", "accept-edits",
            "description", "Accept Edits: work in the workspace; ask for extra access. Approve for me: automatically review extra access. Full Access: no sandbox or approval prompts.",
            "options", List.of(object("id", "accept-edits", "label", "Accept Edits"),
                object("id", "auto", "label", "Approve for me"), object("id", "full", "label", "Full Access")));
    }
}
