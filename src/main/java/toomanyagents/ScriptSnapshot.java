package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;

/** A bounded, loaded-only block view, copied on the server thread. */
final class ScriptSnapshot {
    private JsonObject previousBlocks;

    JsonObject frame(JsonObject snapshot) {
        var blocks = snapshot.getAsJsonObject("blocks");
        if (previousBlocks != null && previousBlocks.get("min").equals(blocks.get("min"))
            && previousBlocks.get("size").equals(blocks.get("size"))) {
            var changes = new JsonArray();
            var states = blocks.getAsJsonArray("states");
            var biomes = blocks.getAsJsonArray("biomes");
            var light = blocks.getAsJsonArray("light");
            var oldStates = previousBlocks.getAsJsonArray("states");
            var oldBiomes = previousBlocks.getAsJsonArray("biomes");
            var oldLight = previousBlocks.getAsJsonArray("light");
            for (int i = 0; i < states.size(); i++) {
                if (states.get(i).equals(oldStates.get(i)) && biomes.get(i).equals(oldBiomes.get(i))
                    && light.get(i).equals(oldLight.get(i))) continue;
                changes.add(i); changes.add(states.get(i)); changes.add(biomes.get(i)); changes.add(light.get(i));
            }
            if (changes.size() < states.size())
                snapshot.add("blocks", JsonState.object("changes", changes, "entities", blocks.get("entities")));
        }
        previousBlocks = blocks;
        return snapshot;
    }

    static JsonObject blocks(ServerLevel level, BlockPos center) {
        int minX = center.getX() - 16, minY = center.getY() - 8, minZ = center.getZ() - 16;
        var states = new JsonArray();
        var biomes = new JsonArray();
        var light = new JsonArray();
        var entities = new JsonObject();
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var position = new BlockPos.MutableBlockPos();
        // x is the fastest-changing coordinate, then z, then y. -1 is unknown,
        // never air: scripts must not plan through an unloaded position.
        for (int y = minY; y <= minY + 16; y++) {
            for (int z = minZ; z <= minZ + 32; z++) {
                for (int x = minX; x <= minX + 32; x++) {
                    position.set(x, y, z);
                    if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()
                        || !level.hasChunkAt(position) || !level.getWorldBorder().isWithinBounds(position)) {
                        states.add(-1); biomes.add(-1); light.add(0);
                    } else {
                        int index = states.size();
                        var state = level.getBlockState(position);
                        states.add(Block.getId(state));
                        biomes.add(biomeRegistry.getId(level.getBiome(position).value()));
                        light.add(level.getBrightness(LightLayer.BLOCK, position)
                            | level.getBrightness(LightLayer.SKY, position) << 4);
                        if (state.hasBlockEntity()) {
                            var entity = level.getBlockEntity(position);
                            if (entity != null) {
                                // Match the client-visible chunk data, not private container contents.
                                var tag = entity.getUpdateTag(level.registryAccess());
                                if (!tag.isEmpty()) entities.add(Integer.toString(index), ScriptNbt.typed(tag));
                            }
                        }
                    }
                }
            }
        }
        var result = new JsonObject();
        result.add("min", Observations.coordinates(minX, minY, minZ));
        result.add("size", Observations.coordinates(33, 17, 33));
        result.add("states", states);
        result.add("biomes", biomes);
        result.add("light", light);
        result.add("entities", entities);
        return result;
    }
}
