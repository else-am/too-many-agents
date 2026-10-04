package toomanyagents.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Shared model picker state for the two native screens. */
final class AgentModels {
    private final List<JsonObject> models = new ArrayList<>();
    String model = "";
    String effort = "";
    String serviceTier = "default";

    static List<String> permissionModes(JsonObject provider) {
        var result = new ArrayList<String>();
        for (var value : array(object(provider, "capabilities"), "permissionModes")) result.add(value.getAsString());
        return result;
    }

    static String permissionLabel(String mode) {
        return switch (mode) {
            case "accept-edits" -> "Ask for extra access";
            case "auto" -> "Approve for me";
            case "full" -> "Full access";
            default -> mode;
        };
    }

    static JsonObject execution(JsonObject snapshot) { return object(snapshot, "executionOptions"); }

    void load(JsonObject catalog) {
        models.clear();
        for (var item : array(catalog, "models")) models.add(item.getAsJsonObject());
        for (var item : array(catalog, "selectedOnlyModels")) {
            var selected=item.getAsJsonObject();
            if(text(selected,"model").equals(model) && models.stream().noneMatch(choice->text(choice,"model").equals(model)))models.add(selected);
        }
        if (models.stream().noneMatch(item -> text(item, "model").equals(model)) && !models.isEmpty()) {
            model = text(models.stream().filter(item -> item.has("isDefault") && item.get("isDefault").getAsBoolean()).findFirst().orElse(models.getFirst()), "model");
        }
        normalize();
    }

    List<JsonObject> choices() { return List.copyOf(models); }

    boolean available() { return !models.isEmpty(); }

    void nextModel() {
        if (models.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < models.size(); i++) if (text(models.get(i), "model").equals(model)) index = i;
        model = text(models.get((index + 1) % models.size()), "model");
        effort = "";
        normalize();
    }

    void nextEffort() {
        var choices = efforts();
        if (!choices.isEmpty()) effort = choices.get((choices.indexOf(effort) + 1) % choices.size());
    }

    void nextServiceTier() {
        var choices = serviceTiers();
        int index = -1;
        for (int i = 0; i < choices.size(); i++) if (text(choices.get(i), "id").equals(serviceTier)) index = i;
        if (!choices.isEmpty()) serviceTier = text(choices.get((index + 1) % choices.size()), "id");
    }

    boolean hasSpeedChoices() { return serviceTiers().size() > 1; }

    String speedLabel() {
        String name = text(selectedTier(), "label");
        return name.isBlank() ? speedLabel(serviceTier) : name;
    }

    static String speedLabel(String tier) {
        return tier.equals("priority") ? "Fast" : tier.isBlank() || tier.equals("default") ? "Standard" : tier;
    }

    String speedTooltip() {
        String description = text(selectedTier(), "description");
        return "Next new turn speed: " + speedLabel() + (description.isBlank() ? "" : " - " + description)
            + ". Queued messages keep this choice; steering keeps the current turn's speed."
            + (hasSpeedChoices() ? " Click to change." : " No other speed is available for this model.");
    }

    void normalize() {
        normalizeEffort();
        if (serviceTiers().stream().noneMatch(item -> text(item, "id").equals(serviceTier))) serviceTier = "default";
    }

    private JsonObject selectedTier() {
        return serviceTiers().stream().filter(item -> text(item, "id").equals(serviceTier)).findFirst().orElseGet(JsonObject::new);
    }

    List<JsonObject> serviceTiers() {
        var result = new ArrayList<JsonObject>();
        for (var item : array(selected(), "supportedServiceTiers")) if (item.isJsonObject()) result.add(item.getAsJsonObject());
        return result;
    }

    String modelLabel() {
        var selected = selected();
        var name = text(selected, "displayName");
        return name.isBlank() ? model : name;
    }

    private JsonObject selected() {
        return models.stream().filter(item -> text(item, "model").equals(model)).findFirst().orElseGet(JsonObject::new);
    }

    List<String> efforts() {
        var result = new ArrayList<String>();
        for (var item : array(selected(), "supportedReasoningEfforts")) result.add(text(item.getAsJsonObject(), "reasoningEffort"));
        return result;
    }

    private void normalizeEffort() {
        var choices = efforts();
        if (choices.contains(effort)) return;
        String preferred = text(selected(), "defaultReasoningEffort");
        effort = choices.contains(preferred) ? preferred : choices.isEmpty() ? "" : choices.getFirst();
    }

    static String text(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    static JsonArray interactions(JsonObject snapshot) { return array(snapshot,"interactions"); }
    static JsonArray queuedMessages(JsonObject snapshot) { return array(snapshot,"queuedMessages"); }

    static JsonObject provider(JsonObject object) {
        return object.has("provider") && object.get("provider").isJsonObject() ? object.getAsJsonObject("provider") : new JsonObject();
    }


    static JsonObject object(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject();
    }

    static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }

    static String error(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
