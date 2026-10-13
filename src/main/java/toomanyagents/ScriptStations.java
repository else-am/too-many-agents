package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import toomanyagents.stations.ProjectBlockEntity;
import toomanyagents.stations.StationBlockEntity;
import toomanyagents.stations.StationIndex;

/** Loaded station/project observations and edits in the body's nearby world. Server thread only. */
final class ScriptStations {
    private static final int RADIUS = 32;

    static JsonObject snapshot(Mob mob) {
        var level = (ServerLevel) mob.level();
        var stations = new JsonArray();
        var projects = new JsonArray();
        var center = mob.blockPosition();
        for (int x = (center.getX() - RADIUS) >> 4; x <= (center.getX() + RADIUS) >> 4; x++) {
            for (int z = (center.getZ() - RADIUS) >> 4; z <= (center.getZ() + RADIUS) >> 4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var block : chunk.getBlockEntities().values()) {
                    if (!nearby(mob, block.getBlockPos())) continue;
                    if (block instanceof StationBlockEntity station) stations.add(stationRow(station));
                    else if (block instanceof ProjectBlockEntity project) projects.add(projectRow(project));
                }
            }
        }
        return JsonState.object("stations", stations, "projectBlocks", projects);
    }

    static JsonObject configure(Mob mob, String operation, JsonObject args) {
        var level = (ServerLevel) mob.level();
        var target = args.get("target");
        BlockPos pos;
        if (operation.equals("configureStation") && target != null && target.isJsonPrimitive() && target.getAsJsonPrimitive().isString()) {
            var entry = StationIndex.get(level.getServer()).find(target.getAsString());
            if (entry == null || !entry.dimension().equals(level.dimension().location().toString()))
                throw new IllegalArgumentException("station_not_nearby");
            pos = entry.pos();
        } else pos = position(target);
        if (!nearby(mob, pos) || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos))
            throw new IllegalArgumentException("station_target_must_be_loaded_within_32_blocks");
        if (!(args.get("settings") instanceof JsonObject settings)) throw new IllegalArgumentException("station_settings_must_be_object");
        var block = level.getBlockEntity(pos);
        if (operation.equals("configureStation")) {
            if (!(block instanceof StationBlockEntity station)) throw new IllegalArgumentException("station_block_required");
            station.configure(settings);
            return stationRow(station);
        }
        if (!(block instanceof ProjectBlockEntity project)) throw new IllegalArgumentException("project_block_required");
        for (var key : settings.keySet()) if (!key.equals("projectId")) throw new IllegalArgumentException("Unknown project block setting: " + key);
        if (settings.has("projectId")) {
            var id = settings.get("projectId");
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("projectId must be a string");
            project.setProject(id.getAsString());
        }
        return projectRow(project);
    }

    private static boolean nearby(Mob mob, BlockPos pos) { return mob.position().distanceToSqr(Vec3.atCenterOf(pos)) <= RADIUS * RADIUS; }
    private static BlockPos position(JsonElement value) {
        if (!(value instanceof JsonObject point)) throw new IllegalArgumentException("block_position_required");
        int[] xyz = new int[3];
        int i = 0;
        for (String axis : new String[]{"x", "y", "z"}) {
            var number = point.get(axis);
            if (number == null || !number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()
                || !Double.isFinite(number.getAsDouble()) || number.getAsDouble() != Math.rint(number.getAsDouble())
                || Math.abs(number.getAsDouble()) > 30_000_000) throw new IllegalArgumentException("integral_block_position_required");
            xyz[i++] = number.getAsInt();
        }
        return new BlockPos(xyz[0], xyz[1], xyz[2]);
    }
    private static JsonObject stationRow(StationBlockEntity block) {
        return located(block.station().json(), block.getBlockPos(), (ServerLevel) block.getLevel());
    }
    private static JsonObject projectRow(ProjectBlockEntity block) {
        return located(JsonState.object("projectId", block.projectId(), "color", block.color()), block.getBlockPos(), (ServerLevel) block.getLevel());
    }
    private static JsonObject located(JsonObject row, BlockPos pos, ServerLevel level) {
        row.add("pos", Observations.coordinates(pos.getX(), pos.getY(), pos.getZ()));
        row.addProperty("dimension", level.dimension().location().toString());
        return row;
    }
    private ScriptStations() {}
}
