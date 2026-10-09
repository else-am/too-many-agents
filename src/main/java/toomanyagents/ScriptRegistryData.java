package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.level.ServerLevel;

/** The native synchronized registry elements, in actual numeric ID order. */
final class ScriptRegistryData {
    private static final int MAX_ENTRIES = 16_384;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_NODES = 262_144;
    private static final int MAX_DEPTH = 64;

    private ScriptRegistryData() {}

    /** Called once at script begin, on the owning server thread. */
    static JsonArray snapshot(ServerLevel level) {
        var result = new JsonArray();
        var budget = new Budget();
        var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (var key : java.util.List.of(Registries.BIOME, Registries.CHAT_TYPE)) {
            var descriptors = RegistryDataLoader.SYNCHRONIZED_REGISTRIES.stream()
                .filter(data -> data.key().equals(key)).toList();
            if (descriptors.size() != 1)
                throw new IllegalStateException("script_registry_codec_missing_or_ambiguous: " + key.location());
            result.add(encode(level, descriptors.getFirst(), ops, budget));
        }
        return result;
    }

    private static <T> JsonObject encode(ServerLevel level, RegistryDataLoader.RegistryData<T> data,
                                         DynamicOps<Tag> ops, Budget budget) {
        var registry = level.registryAccess().registryOrThrow(data.key());
        if (registry.size() > MAX_ENTRIES - budget.entries)
            throw new IllegalStateException("script_registry_entry_limit");
        budget.entries += registry.size();
        var entries = new JsonArray();
        for (int id = 0; id < registry.size(); id++) {
            var value = registry.byId(id);
            if (value == null || registry.getId(value) != id || registry.getKey(value) == null)
                throw new IllegalStateException("script_registry_id_hole: " + data.key().location() + "/" + id);
            var key = registry.getKey(value);
            var tag = data.elementCodec().encodeStart(ops, value).getOrThrow(error ->
                new IllegalStateException("script_registry_encode_failed: " + key + ": " + error));
            if (!(tag instanceof CompoundTag))
                throw new IllegalStateException("script_registry_expected_compound: " + key);
            budget.visit(tag, 0);
            try {
                NbtIo.writeAnyTag(tag, budget.output);
            } catch (IOException error) {
                throw new IllegalStateException("script_registry_nbt_encode_failed: " + key, error);
            }
            var entry = new JsonObject();
            entry.addProperty("key", key.toString());
            entry.add("value", ScriptNbt.typed(tag));
            entries.add(entry);
        }
        var result = new JsonObject();
        result.addProperty("id", data.key().location().toString());
        result.add("entries", entries);
        return result;
    }

    private static final class Budget extends OutputStream {
        private int entries, bytes, nodes;
        private final DataOutputStream output = new DataOutputStream(this);

        private void visit(Tag tag, int depth) {
            if (depth > MAX_DEPTH) throw new IllegalStateException("script_registry_nbt_depth_limit");
            countNodes(1);
            if (tag instanceof CompoundTag compound) {
                for (var key : compound.getAllKeys()) visit(compound.get(key), depth + 1);
            } else if (tag instanceof ListTag list) {
                for (var child : list) visit(child, depth + 1);
            } else if (tag instanceof ByteArrayTag array) countNodes(array.size());
            else if (tag instanceof IntArrayTag array) countNodes(array.size());
            else if (tag instanceof LongArrayTag array) countNodes(array.size());
        }

        private void countNodes(int count) {
            if (count > MAX_NODES - nodes) throw new IllegalStateException("script_registry_nbt_node_limit");
            nodes += count;
        }

        @Override public void write(int value) { countBytes(1); }
        @Override public void write(byte[] value, int offset, int length) { countBytes(length); }

        private void countBytes(int count) {
            if (count > MAX_BYTES - bytes) throw new IllegalStateException("script_registry_nbt_byte_limit");
            bytes += count;
        }
    }
}
