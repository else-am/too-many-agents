package toomanyagents.stations;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Development checks on real station and project blocks, run as the local player on the server thread. Placing and
 * breaking go through the player's own game mode; read reports raw block, item and index state. Callers guard the world.
 */
public final class StationDevelopment {
    private StationDevelopment() {}

    public static JsonObject run(ServerPlayer player, JsonObject request) {
        var level = player.serverLevel();
        var pos = request.has("pos") ? pos(request.getAsJsonArray("pos")) : null;
        var result = new JsonObject();
        switch (text(request, "operation")) {
            case "give" -> {
                int slot = hotbar(request);
                var stack = switch (text(request, "kind")) {
                    case "station" -> request.has("settings")
                        ? StationItem.stack(Station.BLANK.configure(request.getAsJsonObject("settings"))) : new ItemStack(Stations.STATION_ITEM.get());
                    case "project" -> new ItemStack(Stations.PROJECT_ITEM.get());
                    default -> throw new IllegalArgumentException("kind must be station or project");
                };
                player.getInventory().setItem(slot, stack);
            }
            case "place" -> {
                int slot = hotbar(request);
                player.getInventory().selected = slot;
                player.connection.send(new ClientboundSetCarriedItemPacket(slot));
                var hit = new BlockHitResult(Vec3.atCenterOf(require(pos)), Direction.UP, pos, false);
                result.addProperty("placed", player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit).consumesAction());
            }
            case "break" -> result.addProperty("broken", player.gameMode.destroyBlock(require(pos)));
            case "configure" -> station(player, pos).configure(request.getAsJsonObject("settings"));
            case "configure_project" -> {
                if (!(level.getBlockEntity(require(pos)) instanceof ProjectBlockEntity block)) throw new IllegalArgumentException("No project block there");
                block.setProject(text(request, "projectId"));
            }
            case "occupy" -> StationIndex.get(level.getServer()).setOccupant(level.getServer(), station(player, pos).station().id(), text(request, "agentId"));
            case "read" -> {}
            default -> throw new IllegalArgumentException("operation must be give, place, break, configure, configure_project, occupy or read");
        }
        read(player, pos, result);
        return result;
    }

    private static void read(ServerPlayer player, BlockPos pos, JsonObject result) {
        var level = player.serverLevel();
        var registries = level.registryAccess();
        if (pos != null) {
            result.addProperty("block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
            var block = level.getBlockEntity(pos);
            result.add("blockEntity", block == null ? JsonNull.INSTANCE
                : NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, block.saveWithFullMetadata(registries)));
        }
        var stations = new JsonArray();
        StationIndex.get(level.getServer()).entries().forEach(entry -> stations.add(entry.json()));
        var index = new JsonObject();
        index.add("stations", stations);
        result.add("index", index);
        var inventory = new JsonArray();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!ours(stack)) continue;
            var row = item(stack, player);
            row.addProperty("slot", slot);
            inventory.add(row);
        }
        result.add("inventory", inventory);
        var dropped = new JsonArray();
        var center = pos == null ? player.position() : Vec3.atCenterOf(pos);
        for (var entity : level.getEntitiesOfClass(ItemEntity.class, AABB.ofSize(center, 16, 16, 16), entity -> ours(entity.getItem()))) {
            var row = item(entity.getItem(), player);
            row.addProperty("x", entity.getX());
            row.addProperty("y", entity.getY());
            row.addProperty("z", entity.getZ());
            dropped.add(row);
        }
        result.add("dropped", dropped);
    }

    private static boolean ours(ItemStack stack) { return stack.is(Stations.STATION_ITEM.get()) || stack.is(Stations.PROJECT_ITEM.get()); }

    /** An item exactly as stored: its id, count and component changes. */
    private static JsonObject item(ItemStack stack, ServerPlayer player) {
        var row = new JsonObject();
        row.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        row.addProperty("count", stack.getCount());
        row.add("components", DataComponentPatch.CODEC.encodeStart(player.registryAccess().createSerializationContext(JsonOps.INSTANCE),
            stack.getComponentsPatch()).getOrThrow());
        return row;
    }

    private static StationBlockEntity station(ServerPlayer player, BlockPos pos) {
        if (!(player.serverLevel().getBlockEntity(require(pos)) instanceof StationBlockEntity block)) throw new IllegalArgumentException("No station there");
        return block;
    }

    private static int hotbar(JsonObject request) {
        int slot = request.has("slot") ? request.get("slot").getAsInt() : -1;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("slot must be a hotbar slot, 0 to 8");
        return slot;
    }

    private static BlockPos require(BlockPos pos) {
        if (pos == null) throw new IllegalArgumentException("pos is required");
        return pos;
    }

    private static BlockPos pos(JsonArray xyz) { return new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()); }

    private static String text(JsonObject request, String key) {
        return request.has(key) && !request.get(key).isJsonNull() ? request.get(key).getAsString() : "";
    }
}
