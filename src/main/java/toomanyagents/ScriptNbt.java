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
