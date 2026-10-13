package toomanyagents;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;

/** Accessed on the server thread; only serialized snapshots leave that thread. */
final class WorldState {
    private static final Map<MinecraftServer,WorldState> OPEN = new WeakHashMap<>();
    private final Path folder, file, registryFile;
    private JsonObject data, registry;
    private String previousPath = "";
    private boolean needsDecision;

    /** The BB project whose folder is this save's workspace. A copy starts without one. */
    JsonObject project(String instanceId,String projectId) throws IOException {
        if (needsDecision) throw new IllegalStateException("Resolve the relocated world before creating its project.");
        if(instanceId.isBlank()) throw new IllegalArgumentException("A BB instance is required.");
        var projects=JsonState.obj(data,"bbProjects");
        projects.addProperty(instanceId,projectId); data.add("bbProjects",projects);
        JsonState.write(file,data);
        return snapshot();
    }

    /** The world this save was copied from, whose bodies it now owns. */
    String copiedFrom() { return JsonState.text(data,"copiedFrom"); }

    private WorldState(MinecraftServer server) throws IOException {
        folder = server.getWorldPath(LevelResource.ROOT).toRealPath();
        file = folder.resolve("too-many-agents/world.json");
        registryFile = FMLPaths.GAMEDIR.get().resolve("too-many-agents/worlds.json");
        registry = Files.exists(registryFile) ? JsonParser.parseString(Files.readString(registryFile)).getAsJsonObject() : new JsonObject();
        data = Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject()
            : JsonState.object("id",UUID.randomUUID().toString(),"paths",new JsonArray());
        // Drawn boxes and stations were replaced by station blocks; their old records are discarded.
        data.remove("bounds"); data.remove("stations");
        UUID.fromString(id());
        previousPath = registry.has(id()) ? registry.get(id()).getAsString() : "";
        if(previousPath.isBlank()) {
            var paths = JsonState.array(data,"paths");
            if(!paths.isEmpty()) previousPath = paths.get(paths.size()-1).getAsString();
        }
        if (!previousPath.isBlank() && !previousPath.equals(folder.toString())) {
            // A relocated save might be a moved original or a copy whose original is offline.
            // Require an explicit choice rather than attaching existing conversations by guesswork.
            needsDecision = true;
        } else register();
    }
    static synchronized WorldState get(MinecraftServer server) {
        return OPEN.computeIfAbsent(server,key -> {
            try { return new WorldState(key); }
            catch (IOException failure) { throw new IllegalStateException("Could not load world identity; saved files were preserved.",failure); }
        });
    }
    String id() { return JsonState.text(data,"id"); }
    String activeId() { return needsDecision ? "unresolved:" + folder : id(); }
    JsonObject snapshot() {
        var result = data.deepCopy();
        result.addProperty("directory",folder.toString());
        result.addProperty("name",folder.getFileName().toString());
        result.addProperty("needsDecision",needsDecision);
        result.addProperty("previousDirectory",previousPath);
        return result;
    }
    private void register() throws IOException {
        var paths = data.getAsJsonArray("paths");
        if (!paths.contains(new JsonPrimitive(folder.toString()))) paths.add(folder.toString());
        JsonState.write(file,data);
        registry.addProperty(id(),folder.toString());
        JsonState.write(registryFile,registry);
    }
    JsonObject resolve(String choice) throws IOException {
        if (!needsDecision) return snapshot();
        JsonObject copiedBodies = null;
        if (choice.equals("move")) {
            Path previousMarker = Path.of(previousPath).resolve("too-many-agents/world.json");
            boolean originalExists = Files.exists(previousMarker)
                ? id().equals(JsonState.text(JsonParser.parseString(Files.readString(previousMarker)).getAsJsonObject(),"id"))
                : Files.exists(Path.of(previousPath));
            if (originalExists) throw new IllegalArgumentException("The original world still exists. Choose a separate copy.");
        } else if (choice.equals("copy")) {
            String previousWorld = id(), newWorld = UUID.randomUUID().toString();
            // The copy keeps its bodies; each starts a new conversation while the original keeps its own.
            Path bodiesFile = folder.resolve("too-many-agents/bb-bodies.json");
            if (Files.exists(bodiesFile)) {
                var bodies = JsonParser.parseString(Files.readString(bodiesFile)).getAsJsonObject();
                for (var value : JsonState.array(bodies,"agents")) {
                    var agent = value.getAsJsonObject();
                    // The copy's world project is its own; it is created on first use.
                    String worldProject = JsonState.text(JsonState.obj(data,"bbProjects"),JsonState.text(agent,"bbInstanceId"));
                    if(worldProject.isBlank()) worldProject=JsonState.text(data,"worldProjectId");
                    if (!worldProject.isBlank() && JsonState.text(agent,"projectId").equals(worldProject)) agent.addProperty("projectId","minecraft");
                    var spawn = JsonState.obj(agent,"spawn");
                    if (!worldProject.isBlank() && JsonState.text(spawn,"projectId").equals(worldProject)) {
                        // The original world's environment stays with the original; the copy's project chooses its own.
                        spawn.addProperty("projectId","minecraft");
                        spawn.remove("environment");
                        agent.add("spawn",spawn);
                    }
                    // A start still pending in the original belongs to the original.
                    agent.addProperty("startNonce","");
                    if (JsonState.text(agent,"threadId").isBlank()) continue;
                    agent.addProperty("threadId","");
                    agent.add("spawn",JsonState.object("projectId",JsonState.text(agent,"projectId")));
                }
                bodies.addProperty("worldId",newWorld);
                copiedBodies = bodies;
            }
            data = JsonState.object("id",newWorld,"paths",new JsonArray(),"copiedFrom",previousWorld);
        } else throw new IllegalArgumentException("Choose move or copy for this relocated world.");
        register(); needsDecision = false;
        if (copiedBodies != null) JsonState.write(folder.resolve("too-many-agents/bb-bodies.json"),copiedBodies);
        return snapshot();
    }
}
