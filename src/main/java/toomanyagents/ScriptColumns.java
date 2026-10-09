package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;

/** Complete loaded columns, separate from the fast local action snapshot. */
final class ScriptColumns {
    private JsonObject cached;
    private ServerLevel previousLevel;
    private ChunkPos previousCenter;
    private long previousTick, previousAction;

    JsonObject snapshot(ServerLevel level, Mob body, long completedAction) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("columns_require_server_thread");
        var center = body.chunkPosition();
        long tick = level.getGameTime();
        if (cached != null && previousLevel == level && center.equals(previousCenter)
            && completedAction == previousAction && tick >= previousTick && tick - previousTick < 5) return cached;
        var rows = new JsonObject();
        var nbtBudget = new ScriptNbt.Budget(262_144, 4 * 1024 * 1024);
        int bytes = 0;
        for (int z = center.z - 2; z <= center.z + 2; z++) {
            for (int x = center.x - 2; x <= center.x + 2; x++) {
                // getChunkNow never starts a load/generation operation.
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null || !chunk.isLightCorrect() || !level.getWorldBorder().isWithinBounds(chunk.getPos())) continue;
                var row = column(level, chunk, nbtBudget);
                bytes += row.toString().getBytes(StandardCharsets.UTF_8).length;
                if (bytes > 6 * 1024 * 1024) throw new IllegalStateException("column_snapshot_limit");
                String key = x + "," + z;
                var old = cached == null || previousLevel != level ? null : cached.getAsJsonObject("columns").get(key);
                rows.add(key, row.equals(old) ? old : row);
            }
        }
        cached = JsonState.object("columns", rows, "minY", level.getMinBuildHeight(), "worldHeight", level.getHeight(), "dimension", level.dimension().location().toString());
        previousLevel = level; previousCenter = center; previousTick = tick; previousAction = completedAction;
        return cached;
    }

    private static JsonObject column(ServerLevel level, LevelChunk chunk, ScriptNbt.Budget nbtBudget) {
        int size = 0;
        for (var section : chunk.getSections()) size = Math.addExact(size, section.getSerializedSize());
        if (size > 2 * 1024 * 1024) throw new IllegalStateException("column_wire_limit");
        var buffer = new FriendlyByteBuf(Unpooled.buffer(size, size));
        byte[] wire;
        try {
            for (var section : chunk.getSections()) section.write(buffer);
            wire = new byte[buffer.readableBytes()]; buffer.readBytes(wire);
        } finally { buffer.release(); }
        var pos = chunk.getPos();
        var row = JsonState.object("x", pos.x, "z", pos.z, "minY", level.getMinBuildHeight(),
            "worldHeight", level.getHeight(), "data", Base64.getEncoder().encodeToString(wire));
        var sky = new JsonArray(); var block = new JsonArray();
        for (int y = level.getMinSection() - 1; y <= level.getMaxSection(); y++) {
            sky.add(light(level, pos, y, LightLayer.SKY));
            block.add(light(level, pos, y, LightLayer.BLOCK));
        }
        row.add("skyLight", sky); row.add("blockLight", block);
        var entities = new JsonArray();
        int bytes = 0;
        for (var entity : chunk.getBlockEntities().values()) {
            var tag = entity.getUpdateTag(level.registryAccess());
            if (tag.isEmpty()) continue;
            if (entities.size() >= 4096) throw new IllegalStateException("column_block_entity_limit");
            var p = entity.getBlockPos();
            var record = JsonState.object("x", p.getX() & 15, "y", p.getY(), "z", p.getZ() & 15,
                "nbt", ScriptNbt.typed(tag, nbtBudget));
            bytes += record.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 1024 * 1024) throw new IllegalStateException("column_block_entity_bytes");
            entities.add(record);
        }
        row.add("blockEntities", entities);
        return row;
    }

    private static JsonElement light(ServerLevel level, ChunkPos chunk, int sectionY, LightLayer kind) {
        if (kind == LightLayer.SKY && !level.dimensionType().hasSkyLight()) return new JsonPrimitive(0);
        var listener = level.getLightEngine().getLayerListener(kind);
        var layer = listener.getDataLayerData(SectionPos.of(chunk.x, sectionY, chunk.z));
        if (layer != null && layer.isDefinitelyHomogenous()) return new JsonPrimitive(layer.get(0, 0, 0));
        byte[] bytes;
        if (layer != null) bytes = layer.getData();
        else if (kind == LightLayer.BLOCK) return new JsonPrimitive(0);
        else {
            // Missing sky sections inherit the next layer's bottom X/Z plane.
            // Query native light for that plane instead of assuming bright air.
            bytes = new byte[2048];
            var p = new BlockPos.MutableBlockPos();
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x += 2) {
                int a = listener.getLightValue(p.set(chunk.getMinBlockX() + x, sectionY * 16, chunk.getMinBlockZ() + z));
                int b = listener.getLightValue(p.set(chunk.getMinBlockX() + x + 1, sectionY * 16, chunk.getMinBlockZ() + z));
                for (int y = 0; y < 16; y++) bytes[(y * 256 + z * 16 + x) / 2] = (byte)(a | b << 4);
            }
        }
        int value = bytes[0] & 15, packed = value | value << 4;
        boolean uniform = true;
        for (byte b : bytes) if ((b & 255) != packed) { uniform = false; break; }
        return uniform ? new JsonPrimitive(value) : new JsonPrimitive(Base64.getEncoder().encodeToString(bytes));
    }
}
