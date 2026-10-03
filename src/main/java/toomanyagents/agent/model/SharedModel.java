package toomanyagents.agent.model;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared schemas, validated without discarding additive provider fields. */
public final class SharedModel {
    private static final JsonObject MODEL;
    static {
        try (var in = SharedModel.class.getResourceAsStream("/too_many_agents/shared-model-schema.json")) {
            if (in == null) throw new IOException("Missing shared schema");
            MODEL = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private SharedModel() {
    }

    public static JsonObject schema(String name) {
        JsonObject schema = MODEL.getAsJsonObject("schemas").getAsJsonObject(name);
        if (schema == null) throw new IllegalArgumentException("Unknown shared schema " + name);
        return schema.deepCopy();
    }

    public static void validateEvent(JsonObject event) {
        validate("threadEventSchema", event);
        String type = str(event, "type");
        if (event.has("clientRequestSequence") || (event.has("item") && str(event.getAsJsonObject("item"), "type").equals("userMessage") && event.getAsJsonObject("item").has("clientRequestSequence"))) throw new IllegalArgumentException("Legacy request sequence is unsupported");
        JsonObject policy = MODEL.getAsJsonObject("scopePolicies").getAsJsonObject(type);
        if (policy != null && !str(policy, "policy").equals("thread-or-turn") && !str(policy, "policy").equals(str(event.getAsJsonObject("scope"), "kind"))) throw new IllegalArgumentException(type + " has invalid scope");
        if (event.has("item")) validateExtension(event.getAsJsonObject("item"));
        if (type.equals("thread/extensionState/updated")) extension(str(event, "kind"));
    }

    public static void validateDelta(JsonObject delta) {
        validate("threadDeltaSchema", delta);
        for (String field : List.of("providerTurnId", "parentRef")) keyPart(delta, field);
        if (delta.has("key")) for (String field : List.of("providerItemId", "channel", "parentRef")) keyPart(delta.getAsJsonObject("key"), field);
        if (delta.has("item")) keyPart(delta.getAsJsonObject("item"), "childRef");
        if (delta.has("snapshot")) keyPart(delta.getAsJsonObject("snapshot"), "childRef");
        if (delta.has("item") && str(delta.getAsJsonObject("item"), "type").equals("extension")) {
            extension(str(delta.getAsJsonObject("item"), "kind"));
            if (!delta.has("presentation")) throw new IllegalArgumentException("Extension requires presentation");
        }
        if (str(delta, "kind").equals("extension.state")) extension(str(delta, "extensionKind"));
    }

    public static void validateJsonSchema(JsonObject schema, JsonElement value) {
        check(schema, value, schema, "payload");
    }

    public static void validate(String schema, JsonElement value) {
        JsonObject root = MODEL.getAsJsonObject("schemas").getAsJsonObject(schema);
        if (root == null) throw new IllegalArgumentException("Unknown shared schema " + schema);
        check(root, validationValue(schema, value), root, schema);
        refine(schema, value);
    }

    private static void validateExtension(JsonObject item) {
        if (str(item, "type").equals("extension")) extension(str(item, "kind"));
    }

    private static void extension(String kind) {
        if (!kind.matches("[a-z0-9-]+/[a-z0-9-]+")) throw new IllegalArgumentException("Invalid extension namespace: " + kind);
    }

    private static void keyPart(JsonObject o, String k) {
        if (o.has(k) && str(o, k).contains("\u001f")) throw new IllegalArgumentException("Invalid provider key: " + k);
    }

    static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static void check(JsonObject s, JsonElement v, JsonObject root, String path) {
        if (s.has("$ref")) {
            JsonElement ref = root;
            for (String part : s.get("$ref").getAsString().substring(2).split("/")) ref = ref.getAsJsonObject().get(part.replace("~1", "/").replace("~0", "~"));
            check(ref.getAsJsonObject(), v, root, path);
            return;
        }
        if (s.has("anyOf") || s.has("oneOf")) {
            boolean exclusive = s.has("oneOf");
            JsonArray variants = s.getAsJsonArray(exclusive ? "oneOf" : "anyOf");
            int matches = 0;
            for (JsonElement variant : variants) {
                try {
                    check(variant.getAsJsonObject(), v, root, path);
                    matches++;
                } catch (IllegalArgumentException ignored) {
                    // A union is valid if one of its branches accepts the value.
                }
            }
            if (matches == 0 || (exclusive && matches != 1)) fail(path, "one matching variant");
        }
        if (s.has("allOf")) for (JsonElement part : s.getAsJsonArray("allOf")) check(part.getAsJsonObject(), v, root, path);
        if (s.has("const") && !s.get("const").equals(v)) fail(path, "constant");
        if (s.has("enum")) {
            boolean found = false;
            for (JsonElement e : s.getAsJsonArray("enum")) found|=e.equals(v);
            if (!found) fail(path, "enum");
        }
        if (s.has("type")) {
            String t = s.get("type").getAsString();
            boolean ok = switch (t) {
                case "object" -> v.isJsonObject();
                case "array" -> v.isJsonArray();
                case "null" -> v.isJsonNull();
                case "string" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isString();
                case "boolean" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean();
                case "number", "integer" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && (t.equals("number") || v.getAsDouble()%1 == 0);
                default -> throw new IllegalArgumentException("Unsupported schema type " + t);
            };
            if (!ok) fail(path, t);
        }
        if (v.isJsonObject()) {
            JsonObject o = v.getAsJsonObject();
            if (s.has("required")) for (JsonElement k : s.getAsJsonArray("required")) if (!o.has(k.getAsString())) fail(path + "." + k.getAsString(), "required");
            JsonObject props = s.has("properties") ? s.getAsJsonObject("properties") : new JsonObject();
            for (var e : o.entrySet()) if (props.has(e.getKey())) check(props.getAsJsonObject(e.getKey()), e.getValue(), root, path + "." + e.getKey());
            else if (s.has("additionalProperties") && s.get("additionalProperties").isJsonObject()) check(s.getAsJsonObject("additionalProperties"), e.getValue(), root, path + "." + e.getKey());
            if (s.has("propertyNames")) for (String k : o.keySet()) check(s.getAsJsonObject("propertyNames"), new JsonPrimitive(k), root, path);
        }
        if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            if (s.has("minItems") && a.size()<s.get("minItems").getAsInt()) fail(path, "minItems");
            if (s.has("maxItems") && a.size()>s.get("maxItems").getAsInt()) fail(path, "maxItems");
            if (s.has("prefixItems")) {
                JsonArray tuple = s.getAsJsonArray("prefixItems");
                if (a.size() != tuple.size()) fail(path, "tuple length " + tuple.size());
                for (int i = 0; i < tuple.size(); i++) check(tuple.get(i).getAsJsonObject(), a.get(i), root, path + "[" + i + "]");
            }
            if (s.has("items") && s.get("items").isJsonObject()) for (JsonElement e : a) check(s.getAsJsonObject("items"), e, root, path + "[]");
        }
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
            String text = v.getAsString();
            if (s.has("format") && s.get("format").getAsString().equals("uri")) {
                try {
                    if (!new java.net.URI(text).isAbsolute()) fail(path, "absolute URI");
                }
                catch (java.net.URISyntaxException invalid) {
                    fail(path, "URI");
                }
            }
            if (s.has("minLength") && text.length()<s.get("minLength").getAsInt()) fail(path, "minLength");
            if (s.has("maxLength") && text.length()>s.get("maxLength").getAsInt()) fail(path, "maxLength");
            if (s.has("pattern") && !java.util.regex.Pattern.compile(s.get("pattern").getAsString()).matcher(text).find()) fail(path, "pattern");
        }
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
            double n = v.getAsDouble();
            for (String bound : List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) if (s.has(bound)) {
                double limit = s.get(bound).getAsDouble();
                if (switch (bound) {
                    case "minimum" -> n<limit; case "maximum" -> n>limit; case "exclusiveMinimum" -> n<=limit; default -> n>=limit;
                }) fail(path, bound);
            }
        }
    }

    private static JsonElement validationValue(String schema, JsonElement value) {
        if (!value.isJsonObject()) return value;
        JsonObject object = value.getAsJsonObject().deepCopy();
        boolean interaction = schema.toLowerCase(Locale.ROOT).contains("interaction");
        if (schema.equals("pluginInteractionDescriptionSchema")) {
            for (String field : List.of("title", "detail")) {
                if (object.has(field) && object.get(field).isJsonPrimitive()) object.addProperty(field, str(object, field).trim());
            }
        }
        if (schema.equals("pluginExtensionInteractionRequestPayloadSchema")
                || (interaction && (str(object, "kind").contains("/") || str(object, "kind").equals("plugin")))) {
            if (object.has("title") && object.get("title").isJsonPrimitive()) object.addProperty("title", str(object, "title").trim());
        }
        if (Set.of("pendingInteractionCreateSchema", "pendingInteractionSchema", "interactionLifecycleSchema",
                "approvalInteractionOutcomeSchema", "userQuestionInteractionOutcomeSchema",
                "pluginExtensionInteractionOutcomeSchema", "providerInteractionOutcomeSchema").contains(schema)
                && object.has("payload")) object.add("payload", validationValue("pendingInteractionPayloadSchema", object.get("payload")));
        if (str(object, "type").equals("system/interaction/lifecycle") && object.has("interaction")) {
            object.add("interaction", validationValue("interactionLifecycleSchema", object.get("interaction")));
        }
        if (interaction && object.has("resolution")) object.add("resolution", validationValue("pendingInteractionResolutionSchema", object.get("resolution")));
        if (interaction && object.has("description")) object.add("description", validationValue("pluginInteractionDescriptionSchema", object.get("description")));
        return object;
    }

    private static void refine(String schema, JsonElement value) {
        if (schema.equals("threadEventTypeSchema") && !MODEL.getAsJsonObject("scopePolicies").has(value.getAsString())) fail(schema, "known event type");
        if (schema.equals("bridgeGrammarVersionsSchema")) {
            JsonArray range = value.getAsJsonArray();
            if (range.get(0).getAsInt() > range.get(1).getAsInt()) fail(schema, "ascending grammar range");
        }
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        if (Set.of("initializeParamsSchema", "initializeResultSchema", "bridgeCapabilitiesSchema").contains(schema)) {
            if (object.has("grammarVersions")) refine("bridgeGrammarVersionsSchema", object.get("grammarVersions"));
            if (object.has("capabilities") && object.get("capabilities").isJsonObject()) refine("bridgeCapabilitiesSchema", object.get("capabilities"));
        }
        if (schema.equals("systemErrorEventDataSchema")) {
            if (object.has("reconnectAttempt") != object.has("reconnectTotal")) fail(schema, "paired reconnect counts");
            if (object.has("reconnectAttempt") && object.get("reconnectAttempt").getAsInt() > object.get("reconnectTotal").getAsInt()) fail(schema, "attempt at most total");
        }
        if (schema.equals("pluginExtensionInteractionRequestPayloadSchema")
                || (Set.of("pendingInteractionPayloadSchema", "interactionRequestPayloadSchema").contains(schema)
                && !Set.of("approval", "user_question", "plugin").contains(str(object, "kind")))) {
            extension(str(object, "kind"));
        }
        if (schema.equals("pendingInteractionUserQuestionQuestionSchema")) question(object);
        if (schema.equals("pendingInteractionUserAnswerSchema") && object.has("freeText")) nonblank(object, "freeText");
        if (schema.equals("pluginInteractionDescriptionSchema")) {
            for (String field : List.of("title", "detail")) if (object.has(field)) nonblank(object, field);
            if (object.has("payload")) bytes(object.get("payload"));
        }
        boolean interaction = schema.toLowerCase(Locale.ROOT).contains("interaction");
        boolean event = schema.equals("threadEventSchema") || schema.equals("providerEventSchema");
        if (interaction && str(object, "kind").equals("user_question") && object.has("questions")) {
            Set<String> ids = new HashSet<>();
            for (JsonElement element : object.getAsJsonArray("questions")) {
                JsonObject question = element.getAsJsonObject();
                question(question);
                if (!ids.add(str(question, "id"))) fail(schema, "unique question ids");
            }
        }
        if (interaction && str(object, "kind").equals("user_answer") && object.has("answers")) {
            for (JsonElement answer : object.getAsJsonObject("answers").asMap().values()) {
                if (answer.getAsJsonObject().has("freeText")) nonblank(answer.getAsJsonObject(), "freeText");
            }
        }
        if (interaction && str(object, "kind").contains("/") && object.has("title")) {
            extension(str(object, "kind"));
            nonblank(object, "title");
            if (str(object, "title").trim().length() > 160) fail("title", "maxLength");
            if (object.has("data")) bytes(object.get("data"));
        }
        if (Set.of("pendingInteractionCreateSchema", "pendingInteractionSchema", "interactionLifecycleSchema",
                "approvalInteractionOutcomeSchema", "userQuestionInteractionOutcomeSchema",
                "pluginExtensionInteractionOutcomeSchema", "providerInteractionOutcomeSchema").contains(schema)
                && object.has("payload") && object.get("payload").isJsonObject()) {
            refine("pendingInteractionPayloadSchema", object.get("payload"));
        }
        if ((event && str(object, "type").equals("system/interaction/lifecycle"))
                || schema.equals("systemInteractionLifecycleEventDataSchema")) {
            refine("interactionLifecycleSchema", object.get("interaction"));
        }
        if ((event && str(object, "type").equals("system/userQuestion/lifecycle"))
                || schema.equals("systemUserQuestionLifecycleEventDataSchema")) {
            refine("userQuestionPendingInteractionPayloadSchema", object.get("payload"));
            if (object.has("resolution") && !object.get("resolution").isJsonNull()) refine("userQuestionPendingInteractionResolutionSchema", object.get("resolution"));
        }
        if (interaction && object.has("resolution") && object.get("resolution").isJsonObject()) {
            refine("pendingInteractionResolutionSchema", object.get("resolution"));
        }
        if (interaction && object.has("description") && object.get("description").isJsonObject()) {
            refine("pluginInteractionDescriptionSchema", object.get("description"));
        }
    }

    private static void question(JsonObject q) {
        for (String k : List.of("id", "prompt", "shortLabel")) if (q.has(k)) nonblank(q, k);
        Set<String> options = new HashSet<>();
        if (q.has("options")) for (JsonElement e : q.getAsJsonArray("options")) {
            JsonObject o = e.getAsJsonObject();
            for (String k : List.of("value", "label", "description")) if (o.has(k)) nonblank(o, k);
            if (!options.add(str(o, "value"))) fail("options", "unique values");
        }
        if (!q.get("allowFreeText").getAsBoolean() && options.isEmpty()) fail("question", "an answer option");
    }

    private static void nonblank(JsonObject o, String k) {
        if (str(o, k).isBlank()) fail(k, "nonblank text");
    }

    private static void bytes(JsonElement e) {
        if (e.toString().getBytes(StandardCharsets.UTF_8).length>65536) fail("payload", "at most 64 KiB");
    }

    private static void fail(String path, String requirement) {
        throw new IllegalArgumentException(path + ": expected " + requirement);
    }
}
