package toomanyagents;

import com.google.gson.*;
import net.minecraft.nbt.*;

/** Minecraft NBT as prismarine-nbt's typed JSON, without losing 64-bit integers. */
final class ScriptNbt {
    private static final String[] TYPES = {
        "end", "byte", "short", "int", "long", "float", "double",
        "byteArray", "string", "list", "compound", "intArray", "longArray"
    };

    private ScriptNbt() {}

    /** Shared by all block-entity projections in one local view or column refresh. */
    static final class Budget {
        private int nodes, bytes;

        Budget(int nodes, int bytes) {
            if (nodes < 1 || bytes < 1) throw new IllegalArgumentException("Invalid NBT budget");
            this.nodes = nodes;
            this.bytes = bytes;
        }

        private void visit(int depth) {
            if (depth > 64) throw new IllegalStateException("block_entity_nbt_depth_limit");
            if (--nodes < 0) throw new IllegalStateException("block_entity_nbt_node_limit");
        }

        private void charge(int count) {
            if (count > bytes) throw new IllegalStateException("block_entity_nbt_byte_limit");
            bytes -= count;
        }

        // Count compact UTF-8 JSON, allowing Gson's HTML-safe escaping too.
        private void string(String text) {
            charge(2);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\\' || c == '\b' || c == '\f' || c == '\n' || c == '\r' || c == '\t') charge(2);
                else if (c < 32 || c == '<' || c == '>' || c == '&' || c == '=' || c == '\'' || c == '\u2028' || c == '\u2029') charge(6);
                else if (c < 128) charge(1);
                else if (c < 2048) charge(2);
                else if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    charge(4); i++;
                } else charge(3); // Conservative for an unpaired surrogate.
            }
        }

        private void longNumber(long number) {
            charge(3 + Integer.toString((int) (number >> 32)).length() + Integer.toString((int) number).length());
        }

        private void measure(Tag tag, int depth, boolean typed) {
            visit(depth);
            if (typed) charge(20 + TYPES[tag.getId()].length()); // {"type":"…","value":…}
            if (tag instanceof CompoundTag compound) {
                charge(2);
                boolean first = true;
                for (var key : compound.getAllKeys()) {
                    charge(first ? 1 : 2); first = false; // Colon, and comma after the first key.
                    string(key);
                    measure(compound.get(key), depth + 1, true);
                }
            } else if (tag instanceof ListTag list) {
                charge(22 + TYPES[list.getElementType()].length()); // Typed element list and array brackets.
                boolean first = true;
                for (var entry : list) {
                    if (!first) charge(1);
                    first = false;
                    measure(entry, depth + 1, false);
                }
            } else if (tag instanceof LongTag number) longNumber(number.getAsLong());
            else if (tag instanceof NumericTag number) charge(number.getAsNumber().toString().length());
            else if (tag instanceof StringTag text) string(text.getAsString());
            else if (tag instanceof EndTag) charge(4);
            else {
                charge(2);
                int index = 0;
                if (tag instanceof ByteArrayTag array) for (byte number : array.getAsByteArray()) {
                    visit(depth + 1);
                    charge((index++ == 0 ? 0 : 1) + Integer.toString(number).length());
                }
                else if (tag instanceof IntArrayTag array) for (int number : array.getAsIntArray()) {
                    visit(depth + 1);
                    charge((index++ == 0 ? 0 : 1) + Integer.toString(number).length());
                }
                else if (tag instanceof LongArrayTag array) for (long number : array.getAsLongArray()) {
                    visit(depth + 1);
                    if (index++ != 0) charge(1);
                    longNumber(number);
                }
                else throw new IllegalArgumentException("Unsupported NBT tag " + tag.getId());
            }
        }
    }

    static JsonObject typed(Tag tag, Budget budget) {
        // Native tags stay on the owning server thread between preflight and projection.
        // No JSON descendant or expanded string/array is allocated until the tag fits.
        budget.measure(tag, 1, true);
        return typed(tag);
    }

    static JsonObject typed(Tag tag) {
        var result = new JsonObject();
        result.addProperty("type", TYPES[tag.getId()]);
        result.add("value", value(tag));
        return result;
    }

    private static JsonElement value(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            var result = new JsonObject();
            for (var key : compound.getAllKeys()) result.add(key, typed(compound.get(key)));
            return result;
        }
        if (tag instanceof ListTag list) {
            var result = new JsonObject();
            result.addProperty("type", TYPES[list.getElementType()]);
            var values = new JsonArray();
            for (var entry : list) values.add(value(entry));
            result.add("value", values);
            return result;
        }
        if (tag instanceof LongTag number) return longValue(number.getAsLong());
        if (tag instanceof NumericTag number) return new JsonPrimitive(number.getAsNumber());
        if (tag instanceof StringTag string) return new JsonPrimitive(string.getAsString());
        var values = new JsonArray();
        if (tag instanceof ByteArrayTag array) for (var number : array.getAsByteArray()) values.add(number);
        else if (tag instanceof IntArrayTag array) for (var number : array.getAsIntArray()) values.add(number);
        else if (tag instanceof LongArrayTag array) for (var number : array.getAsLongArray()) values.add(longValue(number));
        else if (tag instanceof EndTag) return JsonNull.INSTANCE;
        else throw new IllegalArgumentException("Unsupported NBT tag " + tag.getId());
        return values;
    }

    private static JsonArray longValue(long value) {
        var result = new JsonArray();
        result.add((int) (value >> 32));
        result.add((int) value);
        return result;
    }
}
