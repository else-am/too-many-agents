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

    private WorldState(MinecraftServer server) throws IOException {
        folder = server.getWorldPath(LevelResource.ROOT).toRealPath();
        file = folder.resolve("too-many-agents/world.json");
        registryFile = FMLPaths.GAMEDIR.get().resolve("too-many-agents/worlds-v1.json");
        registry = Files.exists(registryFile) ? JsonParser.parseString(Files.readString(registryFile)).getAsJsonObject() : new JsonObject();
        data = Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject()
            : ProjectStore.object("id",UUID.randomUUID().toString(),"bounds",new JsonArray(),"paths",new JsonArray());
        UUID.fromString(id());
        if (!data.has("stations")) data.add("stations",new JsonArray());
        previousPath = registry.has(id()) ? registry.get(id()).getAsString() : "";
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
    String id() { return ProjectStore.text(data,"id"); }
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
            var project = projectBox(ProjectStore.text(station,"projectId"));
            station.addProperty("outsideProject", project != null && !project.encloses(BodyBox.of(station)));
        }
        return result;
    }
    private void register() throws IOException {
        var paths = data.getAsJsonArray("paths");
        if (!paths.contains(new JsonPrimitive(folder.toString()))) paths.add(folder.toString());
        ProjectStore.write(file,data);
        registry.addProperty(id(),folder.toString());
        ProjectStore.write(registryFile,registry);
    }
    JsonObject resolve(String choice) throws IOException {
        if (!needsDecision) return snapshot();
        if (choice.equals("move")) {
            Path previousMarker = Path.of(previousPath).resolve("too-many-agents/world.json");
            boolean originalExists = Files.exists(previousMarker)
                ? id().equals(ProjectStore.text(JsonParser.parseString(Files.readString(previousMarker)).getAsJsonObject(),"id"))
                : Files.exists(Path.of(previousPath));
            if (originalExists) throw new IllegalArgumentException("The original world still exists. Choose a separate copy.");
        } else if (choice.equals("copy")) {
            data = ProjectStore.object("id",UUID.randomUUID().toString(),"bounds",new JsonArray(),"paths",new JsonArray(),"stations",new JsonArray());
        } else throw new IllegalArgumentException("Choose move or copy for this relocated world.");
        register(); needsDecision = false; return snapshot();
    }
    JsonObject bounds(JsonObject request, String dimension) throws IOException {
        if (needsDecision) throw new IllegalStateException("Resolve the relocated world before editing bounds.");
        String projectId = ProjectStore.text(request,"projectId");
        if (projectId.isBlank()) throw new IllegalArgumentException("Choose a project.");
        String operation = ProjectStore.text(request,"operation");
        var bounds = data.getAsJsonArray("bounds");
        JsonObject row = null;
        if (!operation.equals("bounds-clear")) {
            if (!operation.equals("bounds-set")) throw new IllegalArgumentException("Unknown bounds operation.");
            var corners = corners(request);
            String requestedDimension=ProjectStore.text(request,"dimension");
            row=ProjectStore.object("projectId",projectId,"worldId",id(),"dimension",requestedDimension.isBlank()?dimension:requestedDimension,"min",corners[0],"max",corners[1]);
        }
        for (int i=bounds.size()-1;i>=0;i--) if (ProjectStore.text(bounds.get(i).getAsJsonObject(),"projectId").equals(projectId)) bounds.remove(i);
        if (row!=null) bounds.add(row);
        ProjectStore.write(file,data);
        return row==null?snapshot():row.deepCopy();
    }

    /** An agent's box: its station, otherwise its project's box. Null when the body is unconfined in this dimension. */
    BodyBox box(String agentId, String projectId, String dimension) {
        for (var item : data.getAsJsonArray("stations")) {
            var station = item.getAsJsonObject();
            if (!agentId.isBlank() && ProjectStore.text(station,"agentId").equals(agentId)) {
                var box = BodyBox.of(station);
                return box.dimension().equals(dimension) ? box : null;
            }
        }
        var project = projectBox(projectId);
        return project != null && project.dimension().equals(dimension) ? project : null;
    }
    private BodyBox projectBox(String projectId) {
        for (var item : data.getAsJsonArray("bounds"))
            if (ProjectStore.text(item.getAsJsonObject(),"projectId").equals(projectId)) return BodyBox.of(item.getAsJsonObject());
        return null;
    }

    /** Station edits. AgentService checks that projects and agents exist before calling this. */
    JsonObject stations(JsonObject request, String dimension) throws IOException {
        if (needsDecision) throw new IllegalStateException("Resolve the relocated world before editing stations.");
        String operation = ProjectStore.text(request,"operation");
        var stations = data.getAsJsonArray("stations");
        JsonObject result;
        switch (operation) {
            case "station-create" -> {
                String projectId = ProjectStore.text(request,"projectId");
                if (projectId.isBlank()) throw new IllegalArgumentException("Choose a project.");
                var corners = corners(request);
                String requested = ProjectStore.text(request,"dimension");
                result = ProjectStore.object("id",UUID.randomUUID().toString(),"projectId",projectId,"label",label(request),
                    "dimension",requested.isBlank()?dimension:requested,"min",corners[0],"max",corners[1],"agentId","");
                requireInsideProject(result);
                stations.add(result);
            }
            case "station-update" -> {
                result = station(ProjectStore.text(request,"stationId"));
                var updated = result.deepCopy();
                if (request.has("label")) updated.addProperty("label",label(request));
                if (request.has("min") || request.has("max")) {
                    var corners = corners(request);
                    updated.add("min",corners[0]); updated.add("max",corners[1]);
                    if (request.has("dimension")) updated.addProperty("dimension",ProjectStore.text(request,"dimension"));
                }
                requireInsideProject(updated);
                updated.entrySet().forEach(entry -> result.add(entry.getKey(),entry.getValue()));
            }
            case "station-delete" -> {
                result = station(ProjectStore.text(request,"stationId"));
                if (!ProjectStore.text(result,"agentId").isBlank()) throw new IllegalArgumentException("Unassign the station's agent before deleting it.");
                stations.remove(result);
            }
            case "station-assign" -> {
                // One agent per station and one station per agent. A blank agent unassigns.
                result = station(ProjectStore.text(request,"stationId"));
                String agentId = ProjectStore.text(request,"agentId");
                String occupant = ProjectStore.text(result,"agentId");
                if (!agentId.isBlank()) {
                    if (!occupant.isBlank() && !occupant.equals(agentId)) throw new IllegalArgumentException("That station is occupied.");
                    if (!ProjectStore.text(request,"agentProjectId").equals(ProjectStore.text(result,"projectId"))) throw new IllegalArgumentException("The agent belongs to a different project.");
                    release(agentId);
                }
                result.addProperty("agentId",agentId);
            }
            case "station-release" -> {
                release(ProjectStore.text(request,"agentId"));
                result = snapshot();
            }
            default -> throw new IllegalArgumentException("Unknown station operation.");
        }
        ProjectStore.write(file,data);
        return result.deepCopy();
    }
    private JsonObject station(String id) {
        for (var item : data.getAsJsonArray("stations")) if (ProjectStore.text(item.getAsJsonObject(),"id").equals(id)) return item.getAsJsonObject();
        throw new IllegalArgumentException("Unknown station.");
    }
    private void release(String agentId) {
        if (agentId.isBlank()) return;
        for (var item : data.getAsJsonArray("stations"))
            if (ProjectStore.text(item.getAsJsonObject(),"agentId").equals(agentId)) item.getAsJsonObject().addProperty("agentId","");
    }
    private void requireInsideProject(JsonObject station) {
        var project = projectBox(ProjectStore.text(station,"projectId"));
        if (project != null && !project.encloses(BodyBox.of(station))) throw new IllegalArgumentException("A station must lie inside its project's box.");
    }
    private static String label(JsonObject request) {
        String label = ProjectStore.text(request,"label").strip();
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
