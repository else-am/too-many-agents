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

    static void permissionDefaults(JsonObject settings) {
        for (var value : AgentModels.array(AgentModels.provider(settings), "permissionFields")) {
            if (!value.isJsonObject()) continue;
            var field = value.getAsJsonObject();
            String key = AgentModels.text(field, "key");
            if (!key.isBlank() && !settings.has(key) && field.has("default")) settings.add(key, field.get("default").deepCopy());
        }
    }

    void load(JsonObject catalog) {
        models.clear();
        for (var item : array(catalog, "models")) models.add(item.getAsJsonObject());
        if (models.stream().noneMatch(item -> text(item, "id").equals(model)) && !models.isEmpty()) {
            model = text(models.getFirst(), "id");
        }
        normalize();
    }

    List<JsonObject> choices() { return List.copyOf(models); }

    boolean available() { return !models.isEmpty(); }

    void nextModel() {
        if (models.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < models.size(); i++) if (text(models.get(i), "id").equals(model)) index = i;
        model = text(models.get((index + 1) % models.size()), "id");
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
        String name = text(selectedTier(), "name");
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
        for (var item : array(selected(), "serviceTiers")) if (item.isJsonObject()) result.add(item.getAsJsonObject());
        return result;
    }

    String modelLabel() {
        var selected = selected();
        var name = text(selected, "name");
        return name.isBlank() ? model : name;
    }

    private JsonObject selected() {
        return models.stream().filter(item -> text(item, "id").equals(model)).findFirst().orElseGet(JsonObject::new);
    }

    String permissionError(String mode) {
        var supported = selected().get("supportsAutoMode");
        return mode.equals("auto") && supported != null && !supported.isJsonNull() && !supported.getAsBoolean()
            ? "Approve for me is unavailable for " + modelLabel() + ". Change approval mode or model." : "";
    }

    List<String> efforts() {
        var result = new ArrayList<String>();
        for (var item : array(selected(), "efforts")) result.add(item.getAsString());
        return result;
    }

    private void normalizeEffort() {
        var choices = efforts();
        if (choices.contains(effort)) return;
        String preferred = text(selected(), "defaultEffort");
        effort = choices.contains(preferred) ? preferred : choices.isEmpty() ? "" : choices.getFirst();
    }

    static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    static boolean minecraftProject(JsonObject projects, String id) {
        if (id.isBlank() || id.equals("minecraft")) return true;
        for (var value : array(projects, "projects")) {
            var project = value.getAsJsonObject();
            if (text(project, "id").equals(id)) return !text(project, "minecraftWorldId").isBlank();
        }
        return false;
    }

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
