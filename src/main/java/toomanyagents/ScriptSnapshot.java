package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/** A bounded, loaded-only block view, copied on the server thread. */
final class ScriptSnapshot {
    private ScriptSnapshot() {}

    static JsonObject blocks(ServerLevel level, BlockPos center) {
        int minX = center.getX() - 16, minY = center.getY() - 8, minZ = center.getZ() - 16;
        var states = new JsonArray();
        var position = new BlockPos.MutableBlockPos();
        // x is the fastest-changing coordinate, then z, then y. -1 is unknown,
        // never air: scripts must not plan through an unloaded position.
        for (int y = minY; y <= minY + 16; y++) {
            for (int z = minZ; z <= minZ + 32; z++) {
                for (int x = minX; x <= minX + 32; x++) {
                    position.set(x, y, z);
                    if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()
                        || !level.hasChunkAt(position) || !level.getWorldBorder().isWithinBounds(position)) states.add(-1);
                    else states.add(Block.getId(level.getBlockState(position)));
                }
            }
        }
        var result = new JsonObject();
        result.add("min", Observations.coordinates(minX, minY, minZ));
        result.add("size", Observations.coordinates(33, 17, 33));
        result.add("states", states);
        return result;
    }
}
