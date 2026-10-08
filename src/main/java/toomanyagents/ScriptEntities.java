package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Component;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Adds native values to an already bounded observation; never discovers entities. */
final class ScriptEntities {
    private static final EquipmentSlot[] EQUIPMENT = {
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET,
        EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private ScriptEntities() {}

    static JsonArray players(ServerLevel level) {
        requireServerThread(level);
        var result = new JsonArray();
        for (var player : level.getServer().getPlayerList().getPlayers()) {
            var entry = new JsonObject();
            entry.addProperty("uuid", player.getStringUUID());
            entry.addProperty("username", player.getGameProfile().getName());
            entry.addProperty("gamemode", player.gameMode.getGameModeForPlayer().getId());
            entry.addProperty("ping", player.connection.latency());
            entry.addProperty("listed", true);
            Component name = player.getTabListDisplayName();
            if (name == null) name = Component.literal(player.getGameProfile().getName());
            entry.add("displayName", ComponentSerialization.CODEC.encodeStart(
                level.registryAccess().createSerializationContext(JsonOps.INSTANCE), name).getOrThrow());
            for (var property : player.getGameProfile().getProperties().get("textures")) {
                if (property.value().length() > 16384) continue;
                try {
                    var textures = JsonParser.parseString(new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("textures");
                    if (textures == null || !textures.has("SKIN")) continue;
                    var skin = textures.getAsJsonObject("SKIN");
                    if (!skin.has("url")) continue;
                    var data = new JsonObject();
                    data.add("url", skin.get("url"));
                    if (skin.has("metadata")) data.add("model", skin.getAsJsonObject("metadata").get("model"));
                    if (textures.has("CAPE")) data.add("capeUrl", textures.getAsJsonObject("CAPE").get("url"));
                    entry.add("skinData", data);
                } catch (RuntimeException malformed) {
                    // Optional profile textures must not stop native observations.
                }
            }
            result.add(entry);
        }
        return result;
    }

    /** The caller shares one item encoder across inventory and all observed entities. */
    static void enrich(Entity entity, JsonObject result, ScriptItems items) {
        if (!(entity.level() instanceof ServerLevel level))
            throw new IllegalStateException("script_entity_requires_server_level");
        requireServerThread(level);

        ScriptMetadata.write(entity, result, items);
        var name = entity.getCustomName();
        if (name == null) result.remove("customName");
        else {
            var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            var tag = ComponentSerialization.CODEC.encodeStart(ops, name)
                .getOrThrow(error -> new IllegalStateException("script_entity_name_encode_failed: " + error));
            result.add("customName", ScriptNbt.typed(tag));
        }

        if (entity instanceof ItemEntity dropped) result.add("droppedItem", stack(dropped.getItem(), items));
        else result.remove("droppedItem");

        var equipment = new JsonArray();
        for (var slot : EQUIPMENT) {
            var item = entity instanceof LivingEntity living ? living.getItemBySlot(slot) : ItemStack.EMPTY;
            equipment.add(stack(item, items));
        }
        result.add("equipment", equipment);

        var vehicle = entity.getVehicle();
        if (vehicle == null) result.add("vehicle", JsonNull.INSTANCE);
        else result.addProperty("vehicle", vehicle.getId());
        var passengers = new JsonArray();
        for (var passenger : entity.getPassengers()) passengers.add(passenger.getId());
        result.add("passengers", passengers);
    }

    /** Numeric native chat-type IDs, in the shape consumed by ChatMessage.fromNetwork. */
    static JsonObject chatFormatting(ServerLevel level) {
        requireServerThread(level);
        var result = new JsonObject();
        var types = level.registryAccess().registryOrThrow(Registries.CHAT_TYPE);
        for (var type : types) {
            var decoration = type.chat();
            var format = new JsonObject();
            format.addProperty("formatString", decoration.translationKey());
            var parameters = new JsonArray();
            for (var parameter : decoration.parameters()) parameters.add(parameter.getSerializedName());
            format.add("parameters", parameters);
            result.add(Integer.toString(types.getId(type)), format);
        }
        return result;
    }

    private static JsonObject stack(ItemStack item, ScriptItems items) {
        var result = new JsonObject();
        result.addProperty("wire", items.wire(item));
        return result;
    }

    private static void requireServerThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("script_entity_requires_server_thread");
    }
}
