package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Native item component patches, encoded with Minecraft's vanilla wire codec. */
final class ScriptItems {
    private final ServerLevel level;
    private final Map<ItemStack, String> encoded = new IdentityHashMap<>();
    private int bytesUsed;

    ScriptItems(ServerLevel level) { this.level = level; }

    String wire(ItemStack stack) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("script_item_requires_server_thread");
        if (stack.isEmpty()) return "AA==";
        if (encoded.containsKey(stack)) return encoded.get(stack);
        var raw = Unpooled.buffer();
        try {
            var buffer = new RegistryFriendlyByteBuf(raw, level.registryAccess(), ConnectionType.OTHER);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
            if (buffer.readableBytes() > 1024 * 1024) throw new IllegalStateException("script_item_exceeds_1_MiB");
            bytesUsed += buffer.readableBytes();
            if (bytesUsed > 2 * 1024 * 1024) throw new IllegalStateException("script_inventory_exceeds_2_MiB");
            var bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            var result = Base64.getEncoder().encodeToString(bytes);
            encoded.put(stack, result);
            return result;
        } finally { raw.release(); }
    }

    ItemStack read(String wire) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("script_item_requires_server_thread");
        if (wire == null || wire.length() > 1398104) throw new IllegalStateException("script_item_exceeds_1_MiB");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(wire); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("invalid_item_wire"); }
        if (bytes.length > 1024 * 1024 || !Base64.getEncoder().encodeToString(bytes).equals(wire))
            throw new IllegalStateException("invalid_item_wire");
        var raw = Unpooled.wrappedBuffer(bytes);
        try {
            var buffer = new RegistryFriendlyByteBuf(raw, level.registryAccess(), ConnectionType.OTHER);
            var stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            if (buffer.isReadable()) throw new IllegalStateException("trailing_item_wire");
            return stack;
        } catch (RuntimeException invalid) { throw new IllegalStateException("invalid_item_component_wire"); }
        finally { raw.release(); }
    }

    static JsonObject registries(ServerLevel level) {
        var result = new JsonObject();
        result.add("items", names(BuiltInRegistries.ITEM));
        result.add("components", names(BuiltInRegistries.DATA_COMPONENT_TYPE));
        var references = new JsonObject();
        var needed = Set.of("enchantment", "potion", "mob_effect", "attribute", "block", "sound_event",
            "instrument", "trim_material", "trim_pattern", "armor_material", "banner_pattern", "jukebox_song", "worldgen/biome");
        level.registryAccess().registries().forEach(entry -> {
            if (entry.key().location().getNamespace().equals("minecraft") && needed.contains(entry.key().location().getPath()))
                references.add(entry.key().location().toString(), names(entry.value()));
        });
        result.add("references", references);
        return result;
    }

    private static <T> JsonArray names(Registry<T> registry) {
        var result = new JsonArray();
        for (int id = 0; id < registry.size(); id++) {
            var value = registry.byId(id);
            result.add(value == null ? null : registry.getKey(value).toString());
        }
        return result;
    }
}
