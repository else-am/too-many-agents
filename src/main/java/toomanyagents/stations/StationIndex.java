package toomanyagents.stations;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Every placed station, including unloaded chunks, so listing, names and occupancy work anywhere. Occupancy can change
 * while a station's chunk is unloaded, so the index wins over the block's saved occupant when the chunk loads again.
 * Server thread only; readers on other threads use the published snapshot.
 */
public final class StationIndex extends SavedData {
    private static final String FILE = "too_many_agents_stations";
    private static final String[] ADJECTIVES = {"oak", "birch", "maple", "cedar", "amber", "slate", "copper", "ivory",
        "quiet", "sunny", "cozy", "tidy", "bright", "mossy", "misty", "golden"};
    private static final String[] PLACES = {"desk", "bench", "nook", "loft", "booth", "studio", "corner", "alcove",
        "table", "workshop", "den", "perch", "bay", "porch", "attic", "atelier"};

    /** Where a placed station is, as last seen on the server. */
    public record Entry(Station station, String dimension, BlockPos pos) {
        public JsonObject json() {
            var row = station.json();
            row.addProperty("dimension", dimension);
            row.add("pos", xyz(pos));
            // The area as a block-inclusive box, for outlines and screen linking.
            int r = station.radius();
            row.add("min", xyz(pos.offset(-r, 0, -r)));
            row.add("max", xyz(pos.offset(r, station.height() - 1, r)));
            return row;
        }

        private static JsonArray xyz(BlockPos pos) {
            var xyz = new JsonArray();
            xyz.add(pos.getX()); xyz.add(pos.getY()); xyz.add(pos.getZ());
            return xyz;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** Removed BB projects. Blocks that still name one, loaded later, become blank. Project ids are never reused. */
    private final Set<String> removedProjects = new HashSet<>();
    /** Projects a station or project block may name now, with their colors: BB's projects except world projects. */
    private Map<String, String> projects = Map.of();
    private final Set<ProjectBlockEntity> loadedProjectBlocks = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean orphansReleased;

    private record Published(MinecraftServer server, JsonObject snapshot) {}
    private static volatile Published published;

    public static StationIndex get(MinecraftServer server) {
        var index = server.overworld().getDataStorage().computeIfAbsent(new Factory<>(StationIndex::new, StationIndex::load), FILE);
        if (published == null || published.server != server) index.publish(server);
        return index;
    }

    /** The latest snapshot for this server, safe to read from any thread. */
    public static JsonObject snapshot(MinecraftServer server) {
        var current = published;
        return current != null && current.server == server ? current.snapshot.deepCopy() : null;
    }

    public Collection<Entry> entries() { return entries.values(); }

    /** A station by UUID, or by name ignoring capitalization. */
    public Entry find(String idOrName) {
        var entry = entries.get(idOrName);
        if (entry != null) return entry;
        return entries.values().stream().filter(row -> row.station.name().equalsIgnoreCase(idOrName.strip())).findFirst().orElse(null);
    }

    /**
     * A station block joined the level: placed, loaded with its chunk, or made by a command. A block the index
     * already knows at this spot keeps its id, name and the index's occupant. Anything else is a new station with a
     * fresh id, unless its saved id is free (the index lost it). A copy of a known station never takes its occupant.
     */
    void added(StationBlockEntity block) {
        var station = block.station();
        if (removedProjects.contains(station.projectId())) station = station.withProject("");
        String dimension = block.dimension();
        var existing = station.id().isEmpty() ? null : entries.get(station.id());
        Station normalized;
        if (existing != null && existing.dimension.equals(dimension) && existing.pos.equals(block.getBlockPos())) {
            normalized = station.withName(existing.station.name()).withIdentity(station.id(), existing.station.agentId());
        } else {
            boolean keep = !station.id().isEmpty() && existing == null;
            String id = keep ? station.id() : UUID.randomUUID().toString();
            normalized = station.withIdentity(id, keep ? station.agentId() : "");
            normalized = normalized.withName(availableName(normalized.name(), id));
        }
        block.adopt(normalized);
        entries.put(normalized.id(), new Entry(normalized, dimension, block.getBlockPos()));
        changed(block.server());
    }

    /** The block's record changed. */
    void update(StationBlockEntity block) {
        var station = block.station();
        var existing = entries.get(station.id());
        if (station.id().isEmpty() || existing != null && !existing.pos.equals(block.getBlockPos())) return;
        entries.put(station.id(), new Entry(station, block.dimension(), block.getBlockPos()));
        changed(block.server());
    }

    /** The block is gone: broken, replaced, or removed by a command. Unloading keeps it listed. */
    void removed(StationBlockEntity block) {
        var existing = entries.get(block.station().id());
        if (existing == null || !existing.pos.equals(block.getBlockPos()) || !existing.dimension.equals(block.dimension())) return;
        entries.remove(block.station().id());
        changed(block.server());
    }

    /** Forgets stations in a loaded chunk whose block is gone, e.g. removed by an external world edit. */
    void chunkLoaded(ServerLevel level, ChunkPos chunk) {
        String dimension = level.dimension().location().toString();
        var gone = entries.values().stream().filter(entry -> entry.dimension.equals(dimension) && new ChunkPos(entry.pos).equals(chunk)
            && level.isLoaded(entry.pos) && !level.getBlockState(entry.pos).is(Stations.STATION.get())).toList();
        if (gone.isEmpty()) return;
        gone.forEach(entry -> entries.remove(entry.station.id()));
        changed(level.getServer());
    }

    /**
     * Sets or clears a station's occupant, loaded or not. One station per agent and one agent per station.
     * Returns the updated entry.
     */
    public Entry setOccupant(MinecraftServer server, String id, String agentId) {
        var entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown station.");
        if (!agentId.isEmpty()) {
            if (entry.station.occupied() && !entry.station.agentId().equals(agentId))
                throw new IllegalArgumentException("That station is occupied.");
            if (entries.values().stream().anyMatch(row -> row != entry && row.station.agentId().equals(agentId)))
                throw new IllegalArgumentException("That agent already has a station.");
        }
        var updated = new Entry(entry.station.withIdentity(id, agentId), entry.dimension, entry.pos);
        entries.put(id, updated);
        var level = level(server, entry);
        if (level != null && level.isLoaded(entry.pos) && level.getBlockEntity(entry.pos) instanceof StationBlockEntity block
            && block.station().id().equals(id)) block.adopt(updated.station);
        changed(server);
        return updated;
    }

    /** The station an agent occupies, or null. */
    public Entry occupiedBy(String agentId) {
        if (agentId.isEmpty()) return null;
        return entries.values().stream().filter(entry -> entry.station.agentId().equals(agentId)).findFirst().orElse(null);
    }

    /**
     * Gives a free station in the agent's project to the agent, whose body will spawn there. The station's chunk
     * must be loaded, so its block and area are real.
     */
    public Entry claim(MinecraftServer server, String idOrName, String agentId, String projectId) {
        var entry = find(idOrName);
        if (entry == null) throw new IllegalArgumentException("Unknown station: " + idOrName + ".");
        if (entry.station.projectId().isEmpty()) throw new IllegalArgumentException("Station " + entry.station.name() + " has no project yet.");
        if (!entry.station.projectId().equals(projectId))
            throw new IllegalArgumentException("Station " + entry.station.name() + " belongs to another project.");
        var level = level(server, entry);
        if (level == null || !level.isLoaded(entry.pos) || !(level.getBlockEntity(entry.pos) instanceof StationBlockEntity block)
            || !block.station().id().equals(entry.station.id()))
            throw new IllegalArgumentException("Station " + entry.station.name() + " is in an unloaded area. Go near it first.");
        return setOccupant(server, entry.station.id(), agentId);
    }

    /** Frees the agent's station, if it has one. */
    public void release(MinecraftServer server, String agentId) {
        var entry = occupiedBy(agentId);
        if (entry != null) setOccupant(server, entry.station.id(), "");
    }

    /**
     * Once per world session, after the world's agent records load: frees stations whose occupant has no body, e.g.
     * a spawn interrupted before its body was saved. Later occupants are only ever set with a body.
     */
    public void releaseOrphans(MinecraftServer server, Set<String> bodies) {
        if (orphansReleased) return;
        orphansReleased = true;
        for (var entry : java.util.List.copyOf(entries.values()))
            if (entry.station.occupied() && !bodies.contains(entry.station.agentId())) setOccupant(server, entry.station.id(), "");
    }

    /** The projects stations and project blocks may name, from BB's project list. Empty while BB is disconnected. */
    public void projects(Map<String, String> colors) {
        var allowed = new java.util.HashMap<>(colors);
        allowed.keySet().removeAll(removedProjects);
        if (allowed.equals(projects)) return;
        projects = Map.copyOf(allowed);
        for (var block : java.util.List.copyOf(loadedProjectBlocks)) if (!block.isRemoved()) recolor(block);
    }

    /** A project block takes its project's current BB color, once BB has listed it. */
    private void recolor(ProjectBlockEntity block) {
        if (projects.containsKey(block.projectId())) block.recolor(color(block.projectId()));
    }

    /** Blank, or a project a station or project block may name. Never a world project. */
    String requireProject(String projectId) {
        if (projectId.isEmpty() || projects.containsKey(projectId)) return projectId;
        throw new IllegalArgumentException(projects.isEmpty() ? "BB is not connected, so the project can't be checked."
            : "Choose one of BB's projects. A world's own project can't be used here.");
    }

    String color(String projectId) { return projects.getOrDefault(projectId, ""); }

    /**
     * A BB project was removed. Its stations stay, with no project; its project blocks become blank, now or when
     * they next load.
     */
    public void projectRemoved(MinecraftServer server, String projectId) {
        if (projectId.isEmpty() || !removedProjects.add(projectId)) return;
        var allowed = new java.util.HashMap<>(projects);
        allowed.remove(projectId);
        projects = Map.copyOf(allowed);
        for (var entry : java.util.List.copyOf(entries.values())) {
            if (!entry.station.projectId().equals(projectId)) continue;
            var updated = new Entry(entry.station.withProject(""), entry.dimension, entry.pos);
            entries.put(entry.station.id(), updated);
            var level = level(server, entry);
            if (level != null && level.isLoaded(entry.pos) && level.getBlockEntity(entry.pos) instanceof StationBlockEntity block
                && block.station().id().equals(entry.station.id())) block.adopt(updated.station);
        }
        for (var block : java.util.List.copyOf(loadedProjectBlocks))
            if (!block.isRemoved() && block.projectId().equals(projectId)) block.clear();
        changed(server);
    }

    /** A project block joined the level. One naming a removed project becomes blank. */
    void projectBlockLoaded(ProjectBlockEntity block) {
        loadedProjectBlocks.add(block);
        if (removedProjects.contains(block.projectId())) block.clear();
        else recolor(block);
    }

    void projectBlockUnloaded(ProjectBlockEntity block) { loadedProjectBlocks.remove(block); }

    private static ServerLevel level(MinecraftServer server, Entry entry) {
        var dimension = ResourceLocation.tryParse(entry.dimension);
        return dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    /** A valid name no other station uses, ignoring capitalization. */
    String requireName(String name, String id) {
        name = Station.validName(name);
        if (nameTaken(name, id)) throw new IllegalArgumentException("That station name is already in use. Choose another name.");
        return name;
    }

    private boolean nameTaken(String name, String id) {
        return entries.values().stream().anyMatch(entry -> !entry.station.id().equals(id) && entry.station.name().equalsIgnoreCase(name));
    }

    /** Placed items keep their name when possible. Blank ones get a readable pair; collisions get a suffix. */
    private String availableName(String preferred, String id) {
        String base;
        try { base = Station.validName(preferred); }
        catch (IllegalArgumentException ignored) {
            int hash = id.hashCode();
            base = ADJECTIVES[Math.floorMod(hash, ADJECTIVES.length)] + "-" + PLACES[Math.floorMod(hash / ADJECTIVES.length, PLACES.length)];
        }
        String name = base;
        for (int suffix = 2; nameTaken(name, id); suffix++) {
            String tail = "-" + suffix;
            int length = Math.min(base.codePointCount(0, base.length()), Station.MAX_NAME - tail.length());
            name = base.substring(0, base.offsetByCodePoints(0, length)) + tail;
        }
        return name;
    }

    private void changed(MinecraftServer server) {
        setDirty();
        publish(server);
    }

    private void publish(MinecraftServer server) {
        var stations = new JsonArray();
        entries.values().forEach(entry -> stations.add(entry.json()));
        var result = new JsonObject();
        result.add("stations", stations);
        published = new Published(server, result);
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var entry : entries.values()) {
            var row = new CompoundTag();
            Station.CODEC.encodeStart(NbtOps.INSTANCE, entry.station).ifSuccess(value -> row.put("station", value));
            row.putString("dimension", entry.dimension);
            row.put("pos", NbtUtils.writeBlockPos(entry.pos));
            list.add(row);
        }
        tag.put("stations", list);
        var removed = new ListTag();
        removedProjects.forEach(id -> removed.add(StringTag.valueOf(id)));
        tag.put("removedProjects", removed);
        return tag;
    }

    private static StationIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        var index = new StationIndex();
        for (var item : tag.getList("stations", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) item;
            var station = Station.CODEC.parse(NbtOps.INSTANCE, row.get("station")).result().orElse(null);
            var pos = NbtUtils.readBlockPos(row, "pos").orElse(null);
            if (station == null || pos == null || station.id().isEmpty()) continue;
            index.entries.put(station.id(), new Entry(station, row.getString("dimension"), pos));
        }
        for (var id : tag.getList("removedProjects", Tag.TAG_STRING)) index.removedProjects.add(id.getAsString());
        return index;
    }
}
