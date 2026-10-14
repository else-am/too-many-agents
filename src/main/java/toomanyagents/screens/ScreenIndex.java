package toomanyagents.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
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
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Every placed screen, including unloaded chunks. Entity UUIDs identify screens; names are unique across the world.
 * Server thread only; readers on other threads use the published snapshot.
 */
final class ScreenIndex extends SavedData {
    private static final String FILE = "too_many_agents_screens";

    /** Where a placed screen is, as last seen on the server. */
    record Entry(UUID entity, Screen screen, String dimension, BlockPos pos, String facing, String up, int width, int height) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private static final String[] ADJECTIVES = {"slim", "quiet", "bright", "swift", "gentle", "calm", "bold", "clever",
        "little", "merry", "nimble", "patient", "silver", "golden", "snowy", "sunny"};
    private static final String[] ANIMALS = {"dolphin", "otter", "fox", "owl", "heron", "badger", "robin", "panda",
        "finch", "rabbit", "turtle", "moth", "wren", "lynx", "seal", "deer"};

    /**
     * A thread's hold on a screen, kept in memory only. It lasts while any of its calls runs and for 20 seconds after
     * the last one ends. Each call keeps the lease it took, so ending a call never touches a newer lease.
     */
    static final class Lease {
        static final long AFTER_MILLIS = 20_000;
        final String thread, holder;
        int running;
        long until;

        private Lease(String thread, String holder) {
            this.thread = thread;
            this.holder = holder;
        }

        int secondsLeft() { return running > 0 ? (int) (AFTER_MILLIS / 1000) : (int) Math.max(0, (until - System.currentTimeMillis() + 999) / 1000); }
    }
    private final Map<String, Lease> leases = new HashMap<>();

    static final class Use {
        int running;
        long until;
    }
    private final Map<String, Map<String, Use>> uses = new HashMap<>();
    private Set<String> activeThreads = Set.of();
    private record Published(MinecraftServer server, JsonObject snapshot, Map<UUID, Entry> entries, Set<UUID> awake) {}
    private static volatile Published published;

    static ScreenIndex get(MinecraftServer server) {
        var index = server.overworld().getDataStorage().computeIfAbsent(new Factory<>(ScreenIndex::new, ScreenIndex::load), FILE);
        if (published == null || published.server != server) index.publish(server);
        return index;
    }

    /** The latest snapshot for this server, safe to read from any thread. */
    static JsonObject snapshot(MinecraftServer server) {
        var current = published;
        return current != null && current.server == server ? current.snapshot.deepCopy() : null;
    }

    static Map<UUID, Entry> visibleRecords(MinecraftServer server) {
        var value = published;
        return value != null && value.server == server ? value.entries : Map.of();
    }

    static boolean awake(MinecraftServer server, UUID id) {
        var value = published;
        return value != null && value.server == server && value.awake.contains(id);
    }

    void active(Set<String> threads) { activeThreads = Set.copyOf(threads); }

    Use use(MinecraftServer server, String id, String thread) {
        var use = uses.computeIfAbsent(id, ignored -> new HashMap<>()).computeIfAbsent(thread, ignored -> new Use());
        use.running++;
        publish(server);
        return use;
    }

    void endedUse(MinecraftServer server, String id, String thread, Use use) {
        var owners = uses.get(id);
        if (owners == null || owners.get(thread) != use) return;
        if (--use.running == 0) use.until = System.currentTimeMillis() + Lease.AFTER_MILLIS;
        publish(server);
    }

    /** The index owns navigation even while the entity is unloaded. */
    void edit(MinecraftServer server, String id, java.util.function.UnaryOperator<Screen> change) {
        var entry = entries.get(id);
        if (entry == null) throw new ScreenTools.ScreenError("not_found", "The screen was removed.");
        var record = change.apply(entry.screen);
        requireName(record.name(), id);
        entries.put(id, new Entry(entry.entity, record, entry.dimension, entry.pos, entry.facing, entry.up, entry.width, entry.height));
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(entry.dimension)));
        if (level != null && level.getEntity(entry.entity) instanceof ScreenEntity screen) screen.setRecord(record);
        changed(server);
    }

    void added(ScreenEntity screen) {
        var record = screen.record();
        String id = screen.getUUID().toString();
        var existing = entries.get(id);
        // The index also reserves names for unloaded entities, including names normalized from older saves.
        String name = existing != null ? existing.screen.name() : availableName(record.name(), screen.getUUID());
        screen.setRecord(new Screen(id, name, existing == null ? record.url() : existing.screen.url(),
            existing == null ? record.openedBy() : existing.screen.openedBy()));
        // Holders aren't saved, so a screen loaded again shows its lease from here.
        var lease = lease(screen.record().id());
        var entry = entries.get(screen.record().id());
        screen.setHolder(lease != null && entry != null && entry.entity.equals(screen.getUUID()) ? lease.holder : "");
    }

    Collection<Entry> entries() { return entries.values(); }

    static String validName(String name) {
        name = name.strip();
        if (name.isBlank() || name.codePointCount(0, name.length()) > ScreenInput.MAX_NAME
            || name.codePoints().anyMatch(Character::isISOControl))
            throw new ScreenTools.ScreenError("invalid_request", "A screen name needs 1 to " + ScreenInput.MAX_NAME + " characters, with no control characters.");
        return name;
    }

    String requireName(String name, String id) {
        name = validName(name);
        if (nameTaken(name, id)) throw new ScreenTools.ScreenError("invalid_request", "That screen name is already in use. Choose another name.");
        return name;
    }

    private boolean nameTaken(String name, String id) {
        return entries.values().stream().anyMatch(entry -> !entry.screen.id().equals(id) && entry.screen.name().equalsIgnoreCase(name));
    }

    /** Named items and older saves keep their name when possible. Blank items get a readable pair; collisions get a suffix. */
    private String availableName(String preferred, UUID entity) {
        String base;
        try { base = validName(preferred); }
        catch (ScreenTools.ScreenError ignored) {
            int hash = entity.hashCode();
            base = ADJECTIVES[Math.floorMod(hash, ADJECTIVES.length)] + "-" + ANIMALS[Math.floorMod(hash / ADJECTIVES.length, ANIMALS.length)];
        }
        String name = base;
        for (int suffix = 2; nameTaken(name, entity.toString()); suffix++) {
            String tail = "-" + suffix;
            int length = Math.min(base.codePointCount(0, base.length()), ScreenInput.MAX_NAME - tail.length());
            name = base.substring(0, base.offsetByCodePoints(0, length)) + tail;
        }
        return name;
    }

    void update(ScreenEntity screen) {
        var record = screen.record();
        if (record.id().isEmpty()) return;
        var existing = entries.get(record.id());
        // Another live screen owns this id; that screen keeps it.
        if (existing != null && !existing.entity.equals(screen.getUUID())) return;
        entries.put(record.id(), new Entry(screen.getUUID(), record, screen.level().dimension().location().toString(),
            screen.getPos(), screen.facing().getName(), screen.up().getName(), screen.width(), screen.height()));
        changed(screen.getServer());
    }

    void removed(ScreenEntity screen) {
        var existing = entries.get(screen.record().id());
        if (existing == null || !existing.entity.equals(screen.getUUID())) return;
        entries.remove(screen.record().id());
        leases.remove(screen.record().id());
        uses.remove(screen.record().id());
        changed(screen.getServer());
    }

    /** The screen's current lease, or null once it has ended. */
    Lease lease(String id) {
        var lease = leases.get(id);
        return lease != null && lease.running == 0 && System.currentTimeMillis() >= lease.until ? null : lease;
    }

    /** Starts a call under this thread's lease, taking it if the screen is free. The caller checks it is free. */
    Lease take(MinecraftServer server, String id, String thread, String holder) {
        var lease = lease(id);
        if (lease == null || !lease.thread.equals(thread)) {
            lease = new Lease(thread, holder);
            leases.put(id, lease);
            showHolder(server, id, holder);
        }
        lease.running++;
        publish(server);
        return lease;
    }

    /** A call under this lease has ended, however it ended. */
    void ended(MinecraftServer server, String id, Lease lease) {
        if (leases.get(id) != lease) return;
        lease.running--;
        if (lease.running == 0) lease.until = System.currentTimeMillis() + Lease.AFTER_MILLIS;
        publish(server);
    }

    /** Ends a lease early: the page was closed, or its holder stopped. */
    void release(MinecraftServer server, String id) {
        if (leases.remove(id) == null) return;
        showHolder(server, id, "");
        publish(server);
    }

    /** Ends every lease a thread holds, or every lease when thread is null. */
    void releaseThread(MinecraftServer server, String thread) {
        uses.values().forEach(owners -> { if (thread == null) owners.clear(); else owners.remove(thread); });
        for (var id : leases.entrySet().stream().filter(e -> thread == null || e.getValue().thread.equals(thread)).map(Map.Entry::getKey).toList())
            release(server, id);
        publish(server);
    }

    void releaseUse(MinecraftServer server, String id) { uses.remove(id); publish(server); }

    /** Drops leases whose time is up. */
    void expire(MinecraftServer server) {
        long now = System.currentTimeMillis();
        uses.values().forEach(owners -> {
            owners.forEach((thread, use) -> { if (activeThreads.contains(thread)) use.until = now + Lease.AFTER_MILLIS; });
            owners.values().removeIf(use -> use.running == 0 && use.until <= now);
        });
        uses.values().removeIf(Map::isEmpty);
        for (var id : leases.keySet().stream().filter(id -> lease(id) == null).toList()) release(server, id);
        publish(server);
    }

    private void showHolder(MinecraftServer server, String id, String holder) {
        var entry = entries.get(id);
        if (entry == null) return;
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(entry.dimension)));
        if (level != null && level.getEntity(entry.entity) instanceof ScreenEntity screen) screen.setHolder(holder);
    }

    private void changed(MinecraftServer server) {
        setDirty();
        publish(server);
    }

    private void publish(MinecraftServer server) {
        var result = new JsonObject();
        var screens = new JsonArray();
        for (var entry : entries.values()) {
            var row = new JsonObject();
            row.addProperty("id", entry.screen.id());
            row.addProperty("name", entry.screen.name());
            row.addProperty("url", entry.screen.url());
            row.addProperty("openedBy", entry.screen.openedBy());
            row.addProperty("entity", entry.entity.toString());
            row.addProperty("dimension", entry.dimension);
            row.addProperty("pos", entry.pos.toShortString());
            row.addProperty("facing", entry.facing);
            row.addProperty("up", entry.up);
            row.addProperty("size", entry.width + "x" + entry.height);
            var lease = lease(entry.screen.id());
            if (lease != null) {
                var held = new JsonObject();
                held.addProperty("thread", lease.thread);
                held.addProperty("holder", lease.holder);
                held.addProperty("running", lease.running);
                held.addProperty("secondsLeft", lease.secondsLeft());
                row.add("lease", held);
            }
            screens.add(row);
        }
        result.add("screens", screens);
        var records = new HashMap<UUID, Entry>();
        var awake = new java.util.HashSet<UUID>();
        entries.values().forEach(entry -> {
            records.put(entry.entity, entry);
            if (uses.containsKey(entry.screen.id())) awake.add(entry.entity);
        });
        published = new Published(server, result, Map.copyOf(records), Set.copyOf(awake));
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var entry : entries.values()) {
            var row = new CompoundTag();
            row.putUUID("entity", entry.entity);
            Screen.CODEC.encodeStart(NbtOps.INSTANCE, entry.screen).ifSuccess(value -> row.put("screen", value));
            row.putString("dimension", entry.dimension);
            row.put("pos", NbtUtils.writeBlockPos(entry.pos));
            row.putString("facing", entry.facing);
            row.putString("up", entry.up);
            row.putInt("width", entry.width);
            row.putInt("height", entry.height);
            list.add(row);
        }
        tag.put("screens", list);
        return tag;
    }

    private static ScreenIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        var index = new ScreenIndex();
        for (var item : tag.getList("screens", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) item;
            var screen = Screen.CODEC.parse(NbtOps.INSTANCE, row.get("screen")).result().orElse(null);
            var pos = NbtUtils.readBlockPos(row, "pos").orElse(null);
            if (screen == null || pos == null || !row.hasUUID("entity")) continue;
            UUID entity = row.getUUID("entity");
            var normalized = new Screen(entity.toString(), index.availableName(screen.name(), entity), screen.url(), screen.openedBy());
            if (!normalized.equals(screen)) index.setDirty();
            index.entries.put(normalized.id(), new Entry(entity, normalized, row.getString("dimension"), pos,
                row.getString("facing"), row.contains("up") ? row.getString("up") : "up", row.getInt("width"), row.getInt("height")));
        }
        return index;
    }
}
