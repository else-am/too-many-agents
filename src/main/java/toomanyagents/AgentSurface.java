package toomanyagents;

import toomanyagents.agent.AgentHarness.ToolDefinition;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * The prompts and tools agents see, bundled from surface/. Agents always use the installed version.
 * Agents without Minecraft access get the general tools and coordination prompt only.
 */
final class AgentSurface {
    private AgentSurface() {}

    private static final String ROOT = "/too_many_agents/surface/";
    private static final Set<String> AGENT_TOOLS = Set.of(
        "agent_catalog", "agent_spawn", "agent_message", "agent_read", "agent_wait", "agent_stop", "agent_stations", "agent_archive");
    // operation is null for agent tools, which AgentService handles itself.
    private record Tool(ToolDefinition definition, boolean minecraft, GameAccess.Operation operation) {}
    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();
    private static final String AGENTS = read("agents.md"), MINECRAFT = read("minecraft.md"),
        MINECRAFT_CODEX = read("minecraft-codex.md"), MINECRAFT_CLAUDE = read("minecraft-claude.md"), TURN = read("turn.md");

    static {
        for (var entry : JsonParser.parseString(read("tools.json")).getAsJsonArray()) {
            var tool = entry.getAsJsonObject();
            String name = tool.get("name").getAsString();
            boolean agentTool = AGENT_TOOLS.contains(name);
            if (agentTool != name.startsWith("agent_")) throw new IllegalStateException("Unknown agent tool: " + name);
            var definition = new ToolDefinition(name, tool.get("description").getAsString(), tool.getAsJsonObject("inputSchema"));
            boolean minecraft = tool.has("minecraft") && tool.get("minecraft").getAsBoolean();
            var operation = agentTool ? null : GameAccess.Operation.valueOf(tool.get("operation").getAsString());
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

    static List<ToolDefinition> tools(boolean minecraft) {
        return TOOLS.values().stream().filter(tool -> minecraft || !tool.minecraft()).map(Tool::definition).toList();
    }

    static String instructions(boolean minecraft, String provider) {
        if (!minecraft) return AGENTS;
        return MINECRAFT + "\n\n" + AGENTS + "\n\n" + switch (provider) {
            case "codex" -> MINECRAFT_CODEX;
            case "claude" -> MINECRAFT_CLAUDE;
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }

    static String turnInstructions() { return TURN; }
    static String clientResource() { return ROOT + "minecraft.mjs"; }

    static JsonObject catalog(boolean minecraft) {
        var definitions = new JsonArray();
        for (var tool : tools(minecraft)) {
            var definition = new JsonObject();
            definition.addProperty("name", tool.name());
            definition.addProperty("description", tool.description());
            definition.add("inputSchema", tool.inputSchema());
            definitions.add(definition);
        }
        var result = new JsonObject();
        result.addProperty("protocol", 1);
        result.add("tools", definitions);
        return result;
    }

    static boolean isAgentTool(String tool) { return AGENT_TOOLS.contains(tool) && TOOLS.containsKey(tool); }

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
        if (operation == GameAccess.Operation.NOTIFY)
            toomanyagents.agent.model.SharedModel.validateJsonSchema(definition(tool).inputSchema(), arguments);
        // Both delivery paths share routing; only their queue lifetime differs.
        return scope == null ? game.call(body, session, operation, arguments) : game.callInTurn(scope, body, operation, arguments);
    }
}
