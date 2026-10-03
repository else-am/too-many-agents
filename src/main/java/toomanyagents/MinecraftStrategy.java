package toomanyagents;

import toomanyagents.agent.AgentHarness.ToolDefinition;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** The bundled strategy's agent-facing contract. World execution remains in GameAccess. */
final class MinecraftStrategy {
    private static final Set<String> AGENT_TOOLS = Set.of(
        "agent_catalog", "agent_spawn", "agent_message", "agent_read", "agent_wait", "agent_stop", "agent_stations", "agent_archive");
    static final MinecraftStrategy CURRENT = new MinecraftStrategy("current", 14);
    static final MinecraftStrategy WORK = new MinecraftStrategy("work", 7);
    private final String root;
    private final JsonObject manifest;
    private final String hash, instructions, codexInstructions, claudeInstructions, turnInstructions;
    private final List<ToolDefinition> tools;
    private final Map<String, GameAccess.Operation> operations;
    private final Set<String> agentTools;
    private final GameAccess.Perception perception;

    private MinecraftStrategy(String directory, int revision) {
        root = "/too_many_agents/strategies/" + directory + "/";
        try {
            // Hash the complete package, including the optional JS client, to detect edits to a pinned revision.
            var digest = MessageDigest.getInstance("SHA-256");
            var files = new HashMap<String, String>();
            for (String name : List.of("manifest.json", "instructions.md", "codex.md", "claude.md", "turn.md", "tools.json", "minecraft.mjs")) {
                try (var input = MinecraftStrategy.class.getResourceAsStream(root + name)) {
                    if (input == null) throw new IOException("Missing strategy resource: " + name);
                    byte[] bytes = input.readAllBytes();
                    digest.update(name.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(bytes);
                    digest.update((byte) 0);
                    files.put(name, new String(bytes, StandardCharsets.UTF_8));
                }
            }
            hash = HexFormat.of().formatHex(digest.digest());
            manifest = JsonParser.parseString(files.get("manifest.json")).getAsJsonObject();
            if (!manifest.get("id").getAsString().equals(directory.startsWith("work") ? "work" : "current") || manifest.get("revision").getAsInt() != revision
                || !manifest.get("delivery").getAsString().equals("harness-tools"))
                throw new IllegalArgumentException("Unsupported bundled strategy implementation");
            instructions = files.get("instructions.md").strip();
            codexInstructions = files.get("codex.md").strip();
            claudeInstructions = files.get("claude.md").strip();
            turnInstructions = files.get("turn.md").strip();
            var view = manifest.getAsJsonObject("perception");
            var pov = view.getAsJsonObject("pov");
            perception = new GameAccess.Perception(view.get("observeRadius").getAsDouble(), view.get("entityLimit").getAsInt(),
                view.get("lookDistance").getAsDouble(), new PovCapture.Settings(pov.get("width").getAsInt(),
                pov.get("height").getAsInt(), pov.get("verticalFov").getAsFloat()));
            var definitions = new ArrayList<ToolDefinition>();
            var routes = new LinkedHashMap<String, GameAccess.Operation>();
            var agentRoutes = new HashSet<String>();
            var names = new HashSet<String>();
            for (var entry : JsonParser.parseString(files.get("tools.json")).getAsJsonArray()) {
                var tool = entry.getAsJsonObject();
                String name = tool.get("name").getAsString();
                if (!names.add(name)) throw new IllegalArgumentException("Duplicate strategy tool: " + name);
                String operation = tool.get("operation").getAsString();
                if (AGENT_TOOLS.contains(name)) {
                    if (!name.equals(operation)) throw new IllegalArgumentException("Invalid agent tool route: " + name);
                    agentRoutes.add(name);
                } else {
                    if (name.startsWith("agent_")) throw new IllegalArgumentException("Unknown agent tool: " + name);
                    routes.put(name, GameAccess.Operation.valueOf(operation));
                }
                definitions.add(new ToolDefinition(name, tool.get("description").getAsString(), tool.getAsJsonObject("inputSchema")));
            }
            tools = List.copyOf(definitions);
            operations = Map.copyOf(routes);
            agentTools = Set.copyOf(agentRoutes);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not load current strategy", e);
        }
    }

    static MinecraftStrategy resolve(JsonElement reference) {
        if (CURRENT.reference().equals(reference)) return CURRENT;
        if (WORK.reference().equals(reference)) return WORK;
        throw new IllegalStateException("strategy_unavailable_or_changed: This agent's saved strategy does not match an installed package. Restore its package; the conversation has not been replaced.");
    }

    JsonObject reference() {
        var result = new JsonObject();
        result.add("id", manifest.get("id"));
        result.add("revision", manifest.get("revision"));
        result.addProperty("contentHash", hash);
        return result;
    }

    JsonObject description() {
        var result = reference();
        result.add("name", manifest.get("name"));
        result.add("delivery", manifest.get("delivery"));
        result.add("perception", manifest.get("perception").deepCopy());
        return result;
    }

    List<ToolDefinition> tools() { return tools; }
    String instructions(String provider) {
        return instructions + "\n\n" + switch (provider) {
            case "codex" -> codexInstructions;
            case "claude" -> claudeInstructions;
            default -> throw new IllegalArgumentException("Unknown strategy provider: " + provider);
        };
    }
    String turnInstructions() { return turnInstructions; }
    GameAccess.Perception perception() { return perception; }
    String clientResource() { return root + "minecraft.mjs"; }

    JsonObject catalog() {
        var definitions = new JsonArray();
        for (var tool : tools) {
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

    boolean isAgentTool(String tool) { return agentTools.contains(tool); }

    GameAccess.Operation operation(String tool) {
        var operation = operations.get(tool);
        if (operation == null) throw new IllegalArgumentException("unknown_strategy_tool: " + tool);
        return operation;
    }

    CompletableFuture<JsonObject> call(GameAccess game, GameAccess.Body body, String session,
                                      GameAccess.ToolScope scope, String tool, JsonObject arguments) {
        var operation = operation(tool);
        if (operation == GameAccess.Operation.NOTIFY)
            toomanyagents.agent.model.SharedModel.validateJsonSchema(tools.stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow().inputSchema(), arguments);
        // Both delivery paths use this package's routing and perception; only their queue lifetime differs.
        return scope == null ? game.call(body, session, operation, arguments, perception)
            : game.callInTurn(scope, body, operation, arguments, perception);
    }
}
