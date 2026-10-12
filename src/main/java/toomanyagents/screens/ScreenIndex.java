package toomanyagents.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Every screen placed in a world, including those in unloaded chunks, and the counter that names them s1, s2…
 * Ids are never reused, so an old reference can't land on a new screen. Server thread only; readers on other threads
 * use the published snapshot.
 */
final class ScreenIndex extends SavedData {
    private static final String FILE = "too_many_agents_screens";

    /** Where a placed screen is, as last seen on the server. */
    record Entry(UUID entity, Screen screen, String dimension, BlockPos pos, String facing, int width, int height) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long next = 1;

    private record Published(MinecraftServer server, JsonObject snapshot) {}
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

    void added(ScreenEntity screen) {
        var record = screen.record();
        var existing = entries.get(record.id());
        if (record.id().isEmpty() || existing != null && !existing.entity.equals(screen.getUUID())) {
            while (entries.containsKey("s" + next)) next++;
            screen.setRecord(record.withId("s" + next++));
        } else {
            // An id from an item or an older save still moves the counter past it.
            if (record.id().matches("s[1-9]\\d{0,17}")) next = Math.max(next, Long.parseLong(record.id().substring(1)) + 1);
            update(screen);
        }
    }

    void update(ScreenEntity screen) {
        var record = screen.record();
        if (record.id().isEmpty()) return;
        var existing = entries.get(record.id());
        // Another live screen owns this id; that screen keeps it.
        if (existing != null && !existing.entity.equals(screen.getUUID())) return;
        entries.put(record.id(), new Entry(screen.getUUID(), record, screen.level().dimension().location().toString(),
            screen.getPos(), screen.facing().getName(), screen.width(), screen.height()));
        changed(screen.getServer());
    }

    void removed(ScreenEntity screen) {
        var existing = entries.get(screen.record().id());
        if (existing == null || !existing.entity.equals(screen.getUUID())) return;
        entries.remove(screen.record().id());
        changed(screen.getServer());
    }

    private void changed(MinecraftServer server) {
        setDirty();
        publish(server);
    }

    private void publish(MinecraftServer server) {
        var result = new JsonObject();
        result.addProperty("next", "s" + next);
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
            row.addProperty("size", entry.width + "x" + entry.height);
            screens.add(row);
        }
        result.add("screens", screens);
        published = new Published(server, result);
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("next", next);
        var list = new ListTag();
        for (var entry : entries.values()) {
            var row = new CompoundTag();
            row.putUUID("entity", entry.entity);
            Screen.CODEC.encodeStart(NbtOps.INSTANCE, entry.screen).ifSuccess(value -> row.put("screen", value));
            row.putString("dimension", entry.dimension);
            row.put("pos", NbtUtils.writeBlockPos(entry.pos));
            row.putString("facing", entry.facing);
            row.putInt("width", entry.width);
            row.putInt("height", entry.height);
            list.add(row);
        }
        tag.put("screens", list);
        return tag;
    }

    private static ScreenIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        var index = new ScreenIndex();
        index.next = Math.max(1, tag.getLong("next"));
        for (var item : tag.getList("screens", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) item;
            var screen = Screen.CODEC.parse(NbtOps.INSTANCE, row.get("screen")).result().orElse(null);
            var pos = NbtUtils.readBlockPos(row, "pos").orElse(null);
            if (screen == null || screen.id().isEmpty() || pos == null || !row.hasUUID("entity")) continue;
            index.entries.put(screen.id(), new Entry(row.getUUID("entity"), screen, row.getString("dimension"), pos,
                row.getString("facing"), row.getInt("width"), row.getInt("height")));
        }
        return index;
    }
}
