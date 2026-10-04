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
    JsonObject project(String projectId) throws IOException {
        if (needsDecision) throw new IllegalStateException("Resolve the relocated world before creating its project.");
        data.addProperty("worldProjectId",projectId);
        // Boxes and stations made before the project existed used its placeholder ID.
        for (String list : List.of("bounds","stations")) for (var item : data.getAsJsonArray(list)) {
            var row = item.getAsJsonObject();
            if (JsonState.text(row,"projectId").equals("minecraft")) row.addProperty("projectId",projectId);
        }
        JsonState.write(file,data);
        return snapshot();
    }

    /** The world this save was copied from, whose bodies it now owns. */
    String copiedFrom() { return JsonState.text(data,"copiedFrom"); }

    private WorldState(MinecraftServer server) throws IOException {
        folder = server.getWorldPath(LevelResource.ROOT).toRealPath();
        file = folder.resolve("too-many-agents/world.json");
        registryFile = FMLPaths.GAMEDIR.get().resolve("too-many-agents/worlds-v1.json");
        registry = Files.exists(registryFile) ? JsonParser.parseString(Files.readString(registryFile)).getAsJsonObject() : new JsonObject();
        data = Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject()
            : JsonState.object("id",UUID.randomUUID().toString(),"bounds",new JsonArray(),"paths",new JsonArray());
        UUID.fromString(id());
        if (!data.has("stations")) data.add("stations",new JsonArray());
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
        // Editing a project box never moves or deletes stations; flag the ones left outside it.
        for (var item : result.getAsJsonArray("stations")) {
            var station = item.getAsJsonObject();
            var project = projectBox(JsonState.text(station,"projectId"));
            station.addProperty("outsideProject", project != null && !project.encloses(BodyBox.of(station)));
        }
        return result;
    }
    private void register() throws IOException {
        releaseOrphanStations();
        var paths = data.getAsJsonArray("paths");
        if (!paths.contains(new JsonPrimitive(folder.toString()))) paths.add(folder.toString());
        JsonState.write(file,data);
        registry.addProperty(id(),folder.toString());
        JsonState.write(registryFile,registry);
    }
    /** A disconnected spawn may reserve a station before it ever saves a body. */
    private void releaseOrphanStations() throws IOException {
        Path bodiesFile = folder.resolve("too-many-agents/bb-bodies-v1.json");
        var active = new HashSet<String>();
        if (Files.exists(bodiesFile)) {
            var bodies = JsonParser.parseString(Files.readString(bodiesFile)).getAsJsonObject();
            // Preserve assignments if the identity itself still needs recovery.
            if (!id().equals(JsonState.text(bodies,"worldId"))) return;
            for (var value : JsonState.array(bodies,"agents")) {
                var agent = value.getAsJsonObject();
                if (!JsonState.flag(agent,"bodyRemoved")) active.add(JsonState.text(agent,"id"));
            }
        }
        for (var value : data.getAsJsonArray("stations")) {
            var station = value.getAsJsonObject();
            if (!active.contains(JsonState.text(station,"agentId"))) station.addProperty("agentId","");
        }
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
            Path bodiesFile = folder.resolve("too-many-agents/bb-bodies-v1.json");
            if (Files.exists(bodiesFile)) {
                var bodies = JsonParser.parseString(Files.readString(bodiesFile)).getAsJsonObject();
                for (var value : JsonState.array(bodies,"agents")) {
                    var agent = value.getAsJsonObject();
                    // The copy's world project is its own; it is created on first use.
                    String worldProject = JsonState.text(data,"worldProjectId");
                    if (!worldProject.isBlank() && JsonState.text(agent,"projectId").equals(worldProject)) agent.addProperty("projectId","minecraft");
                    if (!worldProject.isBlank() && JsonState.text(JsonState.obj(agent,"spawn"),"projectId").equals(worldProject)) JsonState.obj(agent,"spawn").addProperty("projectId","minecraft");
                    if (JsonState.text(agent,"threadId").isBlank()) continue;
                    agent.addProperty("threadId","");
                    agent.add("spawn",JsonState.object("projectId",JsonState.text(agent,"projectId")));
                }
                bodies.addProperty("worldId",newWorld);
                copiedBodies = bodies;
            }
            data = JsonState.object("id",newWorld,"bounds",new JsonArray(),"paths",new JsonArray(),"stations",new JsonArray(),"copiedFrom",previousWorld);
        } else throw new IllegalArgumentException("Choose move or copy for this relocated world.");
        register(); needsDecision = false;
        if (copiedBodies != null) JsonState.write(folder.resolve("too-many-agents/bb-bodies-v1.json"),copiedBodies);
        return snapshot();
    }
    JsonObject bounds(JsonObject request, String dimension) throws IOException {
        if (needsDecision) throw new IllegalStateException("Resolve the relocated world before editing bounds.");
        String projectId = JsonState.text(request,"projectId");
        if (projectId.isBlank()) throw new IllegalArgumentException("Choose a project.");
        String operation = JsonState.text(request,"operation");
        var bounds = data.getAsJsonArray("bounds");
        JsonObject row = null;
        if (!operation.equals("bounds-clear")) {
            if (!operation.equals("bounds-set")) throw new IllegalArgumentException("Unknown bounds operation.");
            var corners = corners(request);
            String requestedDimension=JsonState.text(request,"dimension");
            row=JsonState.object("projectId",projectId,"worldId",id(),"dimension",requestedDimension.isBlank()?dimension:requestedDimension,"min",corners[0],"max",corners[1]);
        }
        for (int i=bounds.size()-1;i>=0;i--) if (JsonState.text(bounds.get(i).getAsJsonObject(),"projectId").equals(projectId)) bounds.remove(i);
        if (row!=null) bounds.add(row);
        JsonState.write(file,data);
        return row==null?snapshot():row.deepCopy();
    }

    /** An agent's box: its station, otherwise its project's box. Null when the body is unconfined in this dimension. */
    BodyBox box(String agentId, String projectId, String dimension) {
        for (var item : data.getAsJsonArray("stations")) {
            var station = item.getAsJsonObject();
            if (!agentId.isBlank() && JsonState.text(station,"agentId").equals(agentId)) {
                var box = BodyBox.of(station);
                return box.dimension().equals(dimension) ? box : null;
            }
        }
        var project = projectBox(projectId);
        return project != null && project.dimension().equals(dimension) ? project : null;
    }
    private BodyBox projectBox(String projectId) {
        for (var item : data.getAsJsonArray("bounds"))
            if (JsonState.text(item.getAsJsonObject(),"projectId").equals(projectId)) return BodyBox.of(item.getAsJsonObject());
        return null;
    }

    /** Station edits. AgentService checks that projects and agents exist before calling this. */
    JsonObject stations(JsonObject request, String dimension) throws IOException {
        if (needsDecision) throw new IllegalStateException("Resolve the relocated world before editing stations.");
        String operation = JsonState.text(request,"operation");
        var stations = data.getAsJsonArray("stations");
        JsonObject result;
        switch (operation) {
            case "station-create" -> {
                String projectId = JsonState.text(request,"projectId");
                if (projectId.isBlank()) throw new IllegalArgumentException("Choose a project.");
                var corners = corners(request);
                String requested = JsonState.text(request,"dimension");
                result = JsonState.object("id",UUID.randomUUID().toString(),"projectId",projectId,"label",label(request),
                    "dimension",requested.isBlank()?dimension:requested,"min",corners[0],"max",corners[1],"agentId","");
                requireInsideProject(result);
                stations.add(result);
            }
            case "station-update" -> {
                result = station(JsonState.text(request,"stationId"));
                var updated = result.deepCopy();
                if (request.has("label")) updated.addProperty("label",label(request));
                if (request.has("min") || request.has("max")) {
                    var corners = corners(request);
                    updated.add("min",corners[0]); updated.add("max",corners[1]);
                    if (request.has("dimension")) updated.addProperty("dimension",JsonState.text(request,"dimension"));
                }
                requireInsideProject(updated);
                updated.entrySet().forEach(entry -> result.add(entry.getKey(),entry.getValue()));
            }
            case "station-delete" -> {
                result = station(JsonState.text(request,"stationId"));
                if (!JsonState.text(result,"agentId").isBlank()) throw new IllegalArgumentException("Unassign the station's agent before deleting it.");
                stations.remove(result);
            }
            case "station-assign" -> {
                // One agent per station and one station per agent. A blank agent unassigns.
                result = station(JsonState.text(request,"stationId"));
                String agentId = JsonState.text(request,"agentId");
                String occupant = JsonState.text(result,"agentId");
                if (!agentId.isBlank()) {
                    if (!occupant.isBlank() && !occupant.equals(agentId)) throw new IllegalArgumentException("That station is occupied.");
                    if (!JsonState.text(request,"agentProjectId").equals(JsonState.text(result,"projectId"))) throw new IllegalArgumentException("The agent belongs to a different project.");
                    release(agentId);
                }
                result.addProperty("agentId",agentId);
            }
            case "station-release" -> {
                release(JsonState.text(request,"agentId"));
                result = snapshot();
            }
            default -> throw new IllegalArgumentException("Unknown station operation.");
        }
        JsonState.write(file,data);
        return result.deepCopy();
    }
    private JsonObject station(String id) {
        for (var item : data.getAsJsonArray("stations")) if (JsonState.text(item.getAsJsonObject(),"id").equals(id)) return item.getAsJsonObject();
        throw new IllegalArgumentException("Unknown station.");
    }
    private void release(String agentId) {
        if (agentId.isBlank()) return;
        for (var item : data.getAsJsonArray("stations"))
            if (JsonState.text(item.getAsJsonObject(),"agentId").equals(agentId)) item.getAsJsonObject().addProperty("agentId","");
    }
    private void requireInsideProject(JsonObject station) {
        var project = projectBox(JsonState.text(station,"projectId"));
        if (project != null && !project.encloses(BodyBox.of(station))) throw new IllegalArgumentException("A station must lie inside its project's box.");
    }
    private static String label(JsonObject request) {
        String label = JsonState.text(request,"label").strip();
        if (label.isEmpty() || label.length() > 40 || label.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("A station label must contain 1–40 characters.");
        return label;
    }
    private static JsonArray[] corners(JsonObject request) {
        var min = request.getAsJsonArray("min"); var max = request.getAsJsonArray("max");
        if (min==null || max==null || min.size()!=3 || max.size()!=3) throw new IllegalArgumentException("Choose two block corners.");
        var low = new JsonArray(); var high = new JsonArray();
        for (int i=0;i<3;i++) {
            double a=min.get(i).getAsDouble(), b=max.get(i).getAsDouble();
            if (!Double.isFinite(a) || !Double.isFinite(b) || a!=Math.rint(a) || b!=Math.rint(b) || Math.abs(a)>30000000 || Math.abs(b)>30000000) throw new IllegalArgumentException("Corners must be valid block coordinates.");
            low.add((int)Math.min(a,b)); high.add((int)Math.max(a,b));
        }
        return new JsonArray[]{low,high};
    }
}
