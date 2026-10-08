package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import java.util.Base64;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Actual synchronized values, without consuming vanilla's dirty tracking. */
final class ScriptMetadata {
    private static final int MAX_BYTES = 256 * 1024;
    private static final Map<EntityDataSerializer<?>, String> SERIALIZERS = Map.ofEntries(
        Map.entry(EntityDataSerializers.BYTE, "minecraft:byte"),
        Map.entry(EntityDataSerializers.INT, "minecraft:int"),
        Map.entry(EntityDataSerializers.LONG, "minecraft:long"),
        Map.entry(EntityDataSerializers.FLOAT, "minecraft:float"),
        Map.entry(EntityDataSerializers.STRING, "minecraft:string"),
        Map.entry(EntityDataSerializers.COMPONENT, "minecraft:component"),
        Map.entry(EntityDataSerializers.OPTIONAL_COMPONENT, "minecraft:optional_component"),
        Map.entry(EntityDataSerializers.ITEM_STACK, "minecraft:item_stack"),
        Map.entry(EntityDataSerializers.BOOLEAN, "minecraft:boolean"),
        Map.entry(EntityDataSerializers.ROTATIONS, "minecraft:rotations"),
        Map.entry(EntityDataSerializers.BLOCK_POS, "minecraft:block_pos"),
        Map.entry(EntityDataSerializers.OPTIONAL_BLOCK_POS, "minecraft:optional_block_pos"),
        Map.entry(EntityDataSerializers.DIRECTION, "minecraft:direction"),
        Map.entry(EntityDataSerializers.OPTIONAL_UUID, "minecraft:optional_uuid"),
        Map.entry(EntityDataSerializers.BLOCK_STATE, "minecraft:block_state"),
        Map.entry(EntityDataSerializers.OPTIONAL_BLOCK_STATE, "minecraft:optional_block_state"),
        Map.entry(EntityDataSerializers.COMPOUND_TAG, "minecraft:compound_tag"),
        Map.entry(EntityDataSerializers.PARTICLE, "minecraft:particle"),
        Map.entry(EntityDataSerializers.PARTICLES, "minecraft:particles"),
        Map.entry(EntityDataSerializers.VILLAGER_DATA, "minecraft:villager_data"),
        Map.entry(EntityDataSerializers.OPTIONAL_UNSIGNED_INT, "minecraft:optional_unsigned_int"),
        Map.entry(EntityDataSerializers.POSE, "minecraft:pose"),
        Map.entry(EntityDataSerializers.CAT_VARIANT, "minecraft:cat_variant"),
        Map.entry(EntityDataSerializers.WOLF_VARIANT, "minecraft:wolf_variant"),
        Map.entry(EntityDataSerializers.FROG_VARIANT, "minecraft:frog_variant"),
        Map.entry(EntityDataSerializers.OPTIONAL_GLOBAL_POS, "minecraft:optional_global_pos"),
        Map.entry(EntityDataSerializers.PAINTING_VARIANT, "minecraft:painting_variant"),
        Map.entry(EntityDataSerializers.SNIFFER_STATE, "minecraft:sniffer_state"),
        Map.entry(EntityDataSerializers.ARMADILLO_STATE, "minecraft:armadillo_state"),
        Map.entry(EntityDataSerializers.VECTOR3, "minecraft:vector3"),
        Map.entry(EntityDataSerializers.QUATERNION, "minecraft:quaternion")
    );

    private ScriptMetadata() {}

    static void write(Entity entity, JsonObject result, ScriptItems items) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("script_metadata_requires_server_thread");
        var entries = entity.getEntityData().itemsById;
        if (entries.length > 255) throw new IllegalStateException("script_metadata_entry_limit");
        var raw = Unpooled.buffer(256, MAX_BYTES);
        try {
            var buffer = new RegistryFriendlyByteBuf(raw, level.registryAccess(), ConnectionType.OTHER);
            var nonDefaults = new JsonArray();
            for (var entry : entries) {
                if (entry == null) continue;
                var value = entry.value();
                if (!SERIALIZERS.containsKey(value.serializer())) throw new IllegalStateException("script_metadata_unknown_serializer");
                if (!entry.isSetToDefault()) nonDefaults.add(value.id());
                value.write(buffer);
            }
            buffer.writeByte(255);
            items.accountMetadataBytes(buffer.readableBytes());
            var bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            result.addProperty("metadataWire", Base64.getEncoder().encodeToString(bytes));
            result.add("metadataNonDefaults", nonDefaults);
        } finally { raw.release(); }
    }

    static JsonArray serializers() {
        var result = new JsonArray();
        for (var entry : SERIALIZERS.entrySet()) {
            int id = EntityDataSerializers.getSerializedId(entry.getKey());
            if (id < 0 || id >= 65536) throw new IllegalStateException("script_metadata_invalid_serializer_id");
            while (result.size() <= id) result.add(com.google.gson.JsonNull.INSTANCE);
            if (!result.get(id).isJsonNull()) throw new IllegalStateException("script_metadata_duplicate_serializer_id");
            result.set(id, new com.google.gson.JsonPrimitive(entry.getValue()));
        }
        return result;
    }
}
