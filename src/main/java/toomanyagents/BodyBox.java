package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** A block-inclusive box in one dimension that confines a body's position. */
record BodyBox(String dimension, BlockPos min, BlockPos max) {
    static BodyBox of(JsonObject row) {
        return new BodyBox(ProjectStore.text(row,"dimension"), pos(row.getAsJsonArray("min")), pos(row.getAsJsonArray("max")));
    }
    private static BlockPos pos(JsonArray xyz) { return new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()); }

    boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY() && pos.getY() <= max.getY()
            && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }
    boolean encloses(BodyBox other) { return dimension.equals(other.dimension) && contains(other.min) && contains(other.max); }

    /**
     * Whether a body whose feet are in this block is inside. Standing on the box's top layer counts,
     * so a box drawn over floor blocks holds bodies standing on that floor.
     */
    boolean holdsFeet(BlockPos feet) {
        return feet.getX() >= min.getX() && feet.getX() <= max.getX() && feet.getY() >= min.getY() && feet.getY() <= max.getY() + 1
            && feet.getZ() >= min.getZ() && feet.getZ() <= max.getZ();
    }
    boolean holds(Vec3 position) { return holdsFeet(BlockPos.containing(position)); }

    /** The nearest body position inside the box. */
    Vec3 clamp(Vec3 position) {
        return new Vec3(Math.clamp(position.x, min.getX(), max.getX() + 0.999),
            Math.clamp(position.y, min.getY(), max.getY() + 1.999),
            Math.clamp(position.z, min.getZ(), max.getZ() + 0.999));
    }
    BlockPos clamp(BlockPos feet) { return BlockPos.containing(clamp(Vec3.atCenterOf(feet))); }

    JsonObject json() {
        var result = new JsonObject();
        result.addProperty("dimension", dimension);
        var low = new JsonArray(); low.add(min.getX()); low.add(min.getY()); low.add(min.getZ());
        var high = new JsonArray(); high.add(max.getX()); high.add(max.getY()); high.add(max.getZ());
        result.add("min", low); result.add("max", high);
        return result;
    }
}
