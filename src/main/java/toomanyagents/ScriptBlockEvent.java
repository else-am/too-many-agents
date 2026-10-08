package toomanyagents;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.NoteBlock;
import net.neoforged.bus.api.Event;

/** Native block-action or breaking broadcast, without changing native delivery. */
public final class ScriptBlockEvent extends Event {
    public final ServerLevel level;
    public final BlockPos position;
    public final Block block;
    public final int action, parameter, breakerId, stage;

    private ScriptBlockEvent(ServerLevel level, BlockPos position, Block block,
                             int action, int parameter, int breakerId, int stage) {
        this.level = level;
        this.position = position.immutable();
        this.block = block;
        this.action = action;
        this.parameter = parameter;
        this.breakerId = breakerId;
        this.stage = stage;
    }

    public static ScriptBlockEvent action(ServerLevel level, BlockPos position, Block block, int action, int parameter) {
        // BlockEventPacket writes unsigned action/parameter bytes.
        return new ScriptBlockEvent(level, position, block, action & 255, parameter & 255, -1, 0);
    }

    public static ScriptBlockEvent breaking(ServerLevel level, int breakerId, BlockPos position, int stage) {
        // Mineflayer's pinned packet schema reads the native progress byte signed.
        return new ScriptBlockEvent(level, position, null, 0, 0, breakerId, (byte) stage);
    }

    JsonObject snapshot() {
        var result = JsonState.object("kind", block == null ? "break" : "action", "position",
            JsonState.object("x", position.getX(), "y", position.getY(), "z", position.getZ()));
        if (block == null) {
            result.addProperty("breakerId", breakerId);
            result.addProperty("stage", stage);
        } else {
            String name = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (name.length() > 256) throw new IllegalStateException("script_block_name_too_long");
            result.addProperty("blockId", BuiltInRegistries.BLOCK.getId(block));
            result.addProperty("blockName", name);
            result.addProperty("action", action);
            result.addProperty("parameter", parameter);
            var state = level.getBlockState(position);
            if (state.is(block) && block instanceof NoteBlock
                && state.hasProperty(NoteBlock.INSTRUMENT) && state.hasProperty(NoteBlock.NOTE)) {
                result.addProperty("instrument", state.getValue(NoteBlock.INSTRUMENT).getSerializedName());
                result.addProperty("note", state.getValue(NoteBlock.NOTE));
            }
        }
        return result;
    }
}
