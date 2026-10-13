package toomanyagents.stations;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;

/**
 * What a station is. Placed, it has all of this; an item keeps everything but id and agentId. Blank execution fields
 * and body inherit BB's and the game's defaults. radius and height are always in range here; configure() rejects
 * out-of-range requests instead of clamping them.
 */
public record Station(String id, String name, String projectId, String providerId, String model, String reasoningLevel,
    String serviceTier, String permissionMode, String instructions, JsonObject behaviors, int radius, int height,
    String body, String agentId) {
    public static final int MAX_NAME = 32, MAX_RADIUS = 49, MAX_HEIGHT = 99, MAX_INSTRUCTIONS = 64_000;
    private static final int MAX_FIELD = 200;
    static final Station BLANK = new Station("", "", "", "", "", "", "", "", "", new JsonObject(), 1, 3, "", "");

    public Station {
        behaviors = behaviors == null ? new JsonObject() : behaviors.deepCopy();
        try { validBehaviors(behaviors); }
        catch (IllegalArgumentException obsolete) { behaviors = new JsonObject(); }
        radius = Math.clamp(radius, 0, MAX_RADIUS);
        height = Math.clamp(height, 1, MAX_HEIGHT);
    }

    @Override public JsonObject behaviors() { return behaviors.deepCopy(); }

    private static final Codec<JsonObject> BEHAVIORS = ExtraCodecs.JSON.xmap(
        json -> json.isJsonObject() ? json.getAsJsonObject() : new JsonObject(), json -> json);

    static final Codec<Station> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.optionalFieldOf("id", "").forGetter(Station::id),
        Codec.STRING.optionalFieldOf("name", "").forGetter(Station::name),
        Codec.STRING.optionalFieldOf("projectId", "").forGetter(Station::projectId),
        Codec.STRING.optionalFieldOf("providerId", "").forGetter(Station::providerId),
        Codec.STRING.optionalFieldOf("model", "").forGetter(Station::model),
        Codec.STRING.optionalFieldOf("reasoningLevel", "").forGetter(Station::reasoningLevel),
        Codec.STRING.optionalFieldOf("serviceTier", "").forGetter(Station::serviceTier),
        Codec.STRING.optionalFieldOf("permissionMode", "").forGetter(Station::permissionMode),
        Codec.STRING.optionalFieldOf("instructions", "").forGetter(Station::instructions),
        BEHAVIORS.optionalFieldOf("behaviors", new JsonObject()).forGetter(Station::behaviors),
        Codec.INT.optionalFieldOf("radius", BLANK.radius).forGetter(Station::radius),
        Codec.INT.optionalFieldOf("height", BLANK.height).forGetter(Station::height),
        Codec.STRING.optionalFieldOf("body", "").forGetter(Station::body),
        Codec.STRING.optionalFieldOf("agentId", "").forGetter(Station::agentId)
    ).apply(instance, Station::new));

    static final StreamCodec<ByteBuf, Station> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

    /** What an item carries: never an id or an occupant. */
    Station settings() { return withIdentity("", ""); }

    Station withIdentity(String id, String agentId) {
        return new Station(id, name, projectId, providerId, model, reasoningLevel, serviceTier, permissionMode, instructions,
            behaviors, radius, height, body, agentId);
    }

    Station withProject(String projectId) {
        return new Station(id, name, projectId, providerId, model, reasoningLevel, serviceTier, permissionMode, instructions,
            behaviors, radius, height, body, agentId);
    }

    Station withName(String name) {
        return new Station(id, name, projectId, providerId, model, reasoningLevel, serviceTier, permissionMode, instructions,
            behaviors, radius, height, body, agentId);
    }

    public boolean occupied() { return !agentId.isEmpty(); }

    /**
     * This station with a request's fields applied. Every field is optional. Names are only checked for shape here;
     * the index checks they are unique. A world project is rejected by the caller, which knows the world's projects.
     */
    Station configure(JsonObject request) {
        for (var key : request.keySet())
            if (!SETTINGS.contains(key)) throw new IllegalArgumentException("Unknown station setting: " + key + ".");
        String projectId = text(request, "projectId", this.projectId, MAX_FIELD);
        if (occupied() && !projectId.equals(this.projectId))
            throw new IllegalArgumentException("An occupied station's project can't change.");
        var behaviors = request.has("behaviors") ? validBehaviors(request.get("behaviors")) : this.behaviors;
        String body = text(request, "body", this.body, MAX_FIELD);
        if (!body.isEmpty()) {
            var type = ResourceLocation.tryParse(body);
            if (type == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(type))
                throw new IllegalArgumentException("Unknown body type: " + body + ".");
        }
        return new Station(id, request.has("name") ? validName(text(request, "name", "", MAX_FIELD)) : name, projectId,
            text(request, "providerId", providerId, MAX_FIELD), text(request, "model", model, MAX_FIELD),
            text(request, "reasoningLevel", reasoningLevel, MAX_FIELD), text(request, "serviceTier", serviceTier, MAX_FIELD),
            text(request, "permissionMode", permissionMode, MAX_FIELD),
            text(request, "instructions", instructions, MAX_INSTRUCTIONS), behaviors,
            number(request, "radius", radius, 0, MAX_RADIUS), number(request, "height", height, 1, MAX_HEIGHT), body, agentId);
    }

    private static final Set<String> SETTINGS = Set.of("name", "projectId", "providerId", "model", "reasoningLevel",
        "serviceTier", "permissionMode", "instructions", "behaviors", "radius", "height", "body");

    static String validName(String name) {
        name = name.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > MAX_NAME || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("A station name needs 1 to " + MAX_NAME + " characters, with no control characters.");
        return name;
    }

    /** Per status, one of stand, wander, follow, jump, spin, or look at the player. */
    static JsonObject validBehaviors(JsonElement value) {
        if (!value.isJsonObject()) throw new IllegalArgumentException("Behaviors must be an object keyed by status.");
        for (var entry : value.getAsJsonObject().entrySet()) {
            if (!Set.of("working", "wants_you", "idle").contains(entry.getKey()))
                throw new IllegalArgumentException("Unknown behavior status: " + entry.getKey() + ".");
            boolean valid = entry.getValue().isJsonObject() && switch (stringOr(entry.getValue().getAsJsonObject().get("type"))) {
                case "stand", "wander", "follow", "jump", "spin" -> !entry.getValue().getAsJsonObject().has("target");
                case "look" -> !entry.getValue().getAsJsonObject().has("target") || "player".equals(stringOr(entry.getValue().getAsJsonObject().get("target")));
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Invalid " + entry.getKey()
                + " behavior: use stand, wander, follow, jump, spin, or look at the player.");
        }
        return value.getAsJsonObject();
    }

    private static String stringOr(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    private static String text(JsonObject request, String key, String fallback, int max) {
        if (!request.has(key)) return fallback;
        var value = request.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Station " + key + " must be a string.");
        String text = value.getAsString().strip();
        boolean multiline = key.equals("instructions");
        if (text.codePointCount(0, text.length()) > max
            || text.codePoints().anyMatch(c -> Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\t'))))
            throw new IllegalArgumentException("Station " + key + " must be at most " + max + " characters, with no control characters.");
        return text;
    }

    private static int number(JsonObject request, String key, int fallback, int min, int max) {
        if (!request.has(key)) return fallback;
        var value = request.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || value.getAsDouble() != Math.rint(value.getAsDouble())
            || value.getAsDouble() < min || value.getAsDouble() > max)
            throw new IllegalArgumentException("Station " + key + " must be a whole number from " + min + " to " + max + ".");
        return value.getAsInt();
    }

    /** Every field, as tools and diagnostics show it. */
    public JsonObject json() {
        var result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("name", name);
        result.addProperty("projectId", projectId);
        result.addProperty("providerId", providerId);
        result.addProperty("model", model);
        result.addProperty("reasoningLevel", reasoningLevel);
        result.addProperty("serviceTier", serviceTier);
        result.addProperty("permissionMode", permissionMode);
        result.addProperty("instructions", instructions);
        result.add("behaviors", behaviors());
        result.addProperty("radius", radius);
        result.addProperty("height", height);
        result.addProperty("body", body);
        result.addProperty("agentId", agentId);
        return result;
    }
}
