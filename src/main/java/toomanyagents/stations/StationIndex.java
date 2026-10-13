package toomanyagents.stations;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
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
            var xyz = new JsonArray();
            xyz.add(pos.getX()); xyz.add(pos.getY()); xyz.add(pos.getZ());
            row.add("pos", xyz);
            return row;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

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
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(entry.dimension)));
        if (level != null && level.isLoaded(entry.pos) && level.getBlockEntity(entry.pos) instanceof StationBlockEntity block
            && block.station().id().equals(id)) block.adopt(updated.station);
        changed(server);
        return updated;
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
        return index;
    }
}
