package toomanyagents;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/** Last known chunks let a dormant body wake without scanning terrain or creating a replacement. Server thread only. */
final class AgentLocations extends SavedData {
    record Location(String agent, String world, String dimension, ChunkPos chunk) {
        BlockPos center() { return chunk.getMiddleBlockPosition(0); }
    }
    final Map<UUID, Location> entries = new HashMap<>();

    static AgentLocations get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(AgentLocations::new, AgentLocations::load), "too_many_agents_locations");
    }

    void observe(Mob mob) {
        var data = mob.getPersistentData();
        var location = new Location(data.getString("too_many_agents_agent"), data.getString("too_many_agents_world"),
            mob.level().dimension().location().toString(), mob.chunkPosition());
        if (!location.equals(entries.put(mob.getUUID(), location))) setDirty();
    }

    void remove(UUID id) { if (entries.remove(id) != null) setDirty(); }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var rows = new ListTag();
        entries.forEach((id, location) -> {
            var row = new CompoundTag();
            row.putUUID("id", id);
            row.putString("agent", location.agent);
            row.putString("world", location.world);
            row.putString("dimension", location.dimension);
            row.putLong("chunk", location.chunk.toLong());
            rows.add(row);
        });
        tag.put("bodies", rows);
        return tag;
    }

    private static AgentLocations load(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new AgentLocations();
        for (var value : tag.getList("bodies", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) value;
            if (row.hasUUID("id")) result.entries.put(row.getUUID("id"), new Location(row.getString("agent"),
                row.getString("world"), row.getString("dimension"), new ChunkPos(row.getLong("chunk"))));
        }
        return result;
    }
}
