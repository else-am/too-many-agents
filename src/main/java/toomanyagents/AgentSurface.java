package toomanyagents;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Physical tool dispatch from the same schema bundled by the BB plugin. */
final class AgentSurface {
    private AgentSurface() {}
    record ToolDefinition(String name, String description, JsonObject inputSchema) {}

    private static final String ROOT = "/too_many_agents/surface/";
    private record Tool(ToolDefinition definition, boolean minecraft, GameAccess.Operation operation) {}
    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();
    static {
        for (var entry : JsonParser.parseString(read("tools.json")).getAsJsonArray()) {
            var tool = entry.getAsJsonObject();
            String name = tool.get("name").getAsString();
            var definition = new ToolDefinition(name, tool.get("description").getAsString(), tool.getAsJsonObject("inputSchema"));
            boolean minecraft = tool.has("minecraft") && tool.get("minecraft").getAsBoolean();
            var operation = GameAccess.Operation.valueOf(tool.get("operation").getAsString());
            if (TOOLS.put(name, new Tool(definition, minecraft, operation)) != null) throw new IllegalStateException("Duplicate tool: " + name);
        }
    }

    private static String read(String name) {
        try (var input = AgentSurface.class.getResourceAsStream(ROOT + name)) {
            if (input == null) throw new IOException("Missing agent surface file: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("Could not load the agent surface", e);
        }
    }

    static ToolDefinition definition(String tool) {
        var found = TOOLS.get(tool);
        if (found == null) throw new IllegalArgumentException("unknown_tool: " + tool);
        return found.definition();
    }

    /** The world operation behind a tool this agent may use. */
    static GameAccess.Operation operation(boolean minecraft, String tool) {
        var found = TOOLS.get(tool);
        if (found == null || found.operation() == null || found.minecraft() && !minecraft) throw new IllegalArgumentException("unknown_tool: " + tool);
        return found.operation();
    }

    static CompletableFuture<JsonObject> call(boolean minecraft, GameAccess game, GameAccess.Body body, String session,
                                             GameAccess.ToolScope scope, String tool, JsonObject arguments) {
        var operation = operation(minecraft, tool);
        // Both delivery paths share routing; only their queue lifetime differs.
        return scope == null ? game.call(body, session, operation, arguments) : game.callInTurn(scope, body, operation, arguments);
    }
}
