package toomanyagents.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** A small side panel for saving a drawn box or editing one; the world stays visible beside it. */
public final class SurveyScreen extends Screen {
    private record Choice(String id, String name) {}
    private final SurveyMode survey;
    private final AgentUiAccess access;
    private final SurveyMode.Box existing;
    private final BlockPos min, max;
    private final String dimension;
    private final List<Choice> projects = new ArrayList<>(), agents = new ArrayList<>();
    private String kind, projectId, label, agentId;
    private boolean busy, confirmDelete;
    private String feedback = "";
    private int left, top, panelWidth, panelHeight;
    private EditBox labelField;
    private Button done, cancel, saveButton;

    /** A new box from drawn corners, or an existing box with optional new corners. */
    SurveyScreen(SurveyMode survey, AgentUiAccess access, SurveyMode.Box existing, BlockPos min, BlockPos max, String dimension, String kind, String projectId) {
        super(Component.literal("Survey"));
        this.survey = survey;
        this.access = access;
        this.existing = existing;
        this.min = min;
        this.max = max;
        this.dimension = dimension;
        this.kind = existing == null ? kind : existing.kind();
        this.projectId = existing == null ? projectId : existing.projectId();
        label = existing != null && station() ? AgentModels.text(existing.row(), "label") : SurveyMode.lastLabel;
        agentId = existing != null && station() ? AgentModels.text(existing.row(), "agentId") : "";
        for (var item : AgentModels.array(access.projects(), "projects")) {
            var row = item.getAsJsonObject();
            projects.add(new Choice(AgentModels.text(row, "id"), AgentModels.text(row, "name")));
        }
        if (existing != null && station()) {
            agents.add(new Choice("", "Nobody"));
            for (var item : access.list()) {
                var row = item.getAsJsonObject();
                boolean active = AgentModels.text(row, "lifecycle").equals("active") && !row.get("conversationArchived").getAsBoolean();
                if ((active && AgentModels.text(row, "projectId").equals(this.projectId)) || AgentModels.text(row, "id").equals(agentId))
                    agents.add(new Choice(AgentModels.text(row, "id"), AgentModels.text(row, "name")));
            }
        }
    }

    private boolean station() { return kind.equals("station"); }
    private boolean redrawn() { return min != null; }
    private BlockPos low() { return redrawn() ? min : existing.min(); }
    private BlockPos high() { return redrawn() ? max : existing.max(); }
    private int color() { return survey.color(projectId); }

    @Override protected void init() {
        // Docked right of the crosshair, so the box itself stays in view.
        panelWidth = Math.max(140, Math.min(200, width / 2 - 14));
        left = width - panelWidth - 10;
        int y = existing == null ? 30 : 42, inner = panelWidth - 20, x = left + 10;
        if (existing == null) {
            int half = inner / 2;
            addRenderableWidget(new Flat(x, y, half, "Project box", () -> !station(), () -> { kind = "bounds"; rebuildWidgets(); }));
            addRenderableWidget(new Flat(x + half, y, inner - half, "Station", this::station, () -> { kind = "station"; rebuildWidgets(); }));
            y += 26;
            addRenderableWidget(new Row(x, y, inner, "Project", () -> name(projects, projectId), () -> { projectId = next(projects, projectId); rebuildWidgets(); }));
            y += 22;
        }
        if (station()) {
            labelField = new EditBox(font, x, y + 2, inner, 18, Component.literal("Label"));
            labelField.setMaxLength(40);
            labelField.setValue(label);
            labelField.setHint(Component.literal("desk").withColor(0x707070));
            labelField.setResponder(value -> { label = value; confirmDelete = false; });
            addRenderableWidget(labelField);
            setInitialFocus(labelField);
            y += 26;
        }
        if (existing != null && station()) {
            addRenderableWidget(new Row(x, y, inner, "Agent", () -> name(agents, agentId), () -> { agentId = next(agents, agentId); }));
            y += 22;
        }
        if (existing != null) {
            int half = (inner - 6) / 2;
            addRenderableWidget(Button.builder(Component.literal("Redraw"), b -> { onClose(); survey.redraw(existing); })
                .bounds(x, y + 4, half, 20).build()).active = !busy;
            addRenderableWidget(Button.builder(Component.literal(confirmDelete ? "Really delete" : station() ? "Delete" : "Clear box"), b -> delete())
                .bounds(x + half + 6, y + 4, inner - half - 6, 20).build()).active = !busy;
            y += 28;
        }
        y += 14;
        int half = (inner - 6) / 2;
        // Done until something changes, then Cancel and Save.
        done = addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x, y, inner, 20).build());
        cancel = addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x, y, half, 20).build());
        saveButton = addRenderableWidget(Button.builder(Component.literal(busy ? "Saving…" : "Save"), b -> save()).bounds(x + half + 6, y, inner - half - 6, 20).build());
        saveButton.active = !busy && !projectId.isBlank();
        panelHeight = y + 30;
        top = Math.max(8, (height - panelHeight) / 2);
        for (var child : children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget) widget.setY(widget.getY() + top);
        tick();
    }

    private boolean changed() {
        if (existing == null || redrawn()) return true;
        return station() && (!label.strip().equals(AgentModels.text(existing.row(), "label")) || !agentId.equals(AgentModels.text(existing.row(), "agentId")));
    }

    @Override public void tick() {
        boolean changed = changed() || busy;
        done.visible = !changed;
        cancel.visible = saveButton.visible = changed;
    }

    private static String name(List<Choice> choices, String id) {
        for (var choice : choices) if (choice.id().equals(id)) return choice.name();
        return choices.isEmpty() ? "None" : "Choose";
    }
    private static String next(List<Choice> choices, String id) {
        if (choices.isEmpty()) return id;
        int index = -1;
        for (int i = 0; i < choices.size(); i++) if (choices.get(i).id().equals(id)) index = i;
        return choices.get((index + 1) % choices.size()).id();
    }

    private void save() {
        if (busy) return;
        if (station() && label.isBlank()) { feedback = "Name the station."; return; }
        var steps = new ArrayList<JsonObject>();
        if (existing == null) {
            var request = request(station() ? "station-create" : "bounds-set");
            request.addProperty("projectId", projectId);
            if (station()) request.addProperty("label", label.strip());
            corners(request);
            steps.add(request);
        } else if (!station()) {
            if (redrawn()) { var request = request("bounds-set"); request.addProperty("projectId", projectId); corners(request); steps.add(request); }
        } else {
            String id = AgentModels.text(existing.row(), "id");
            if (redrawn() || !label.strip().equals(AgentModels.text(existing.row(), "label"))) {
                var request = request("station-update");
                request.addProperty("stationId", id);
                request.addProperty("label", label.strip());
                if (redrawn()) corners(request);
                steps.add(request);
            }
            if (!agentId.equals(AgentModels.text(existing.row(), "agentId"))) {
                var request = request("station-assign");
                request.addProperty("stationId", id);
                request.addProperty("agentId", agentId);
                steps.add(request);
            }
        }
        if (station()) SurveyMode.lastLabel = label.strip();
        run(steps, "Saved");
    }

    private void delete() {
        if (!confirmDelete) { confirmDelete = true; rebuildWidgets(); return; }
        var request = request(station() ? "station-delete" : "bounds-clear");
        if (station()) request.addProperty("stationId", AgentModels.text(existing.row(), "id"));
        else request.addProperty("projectId", projectId);
        run(List.of(request), station() ? "Deleted" : "Cleared");
    }

    private void corners(JsonObject request) {
        request.add("min", xyz(low()));
        request.add("max", xyz(high()));
        request.addProperty("dimension", dimension);
    }
    private static JsonArray xyz(BlockPos pos) { var a = new JsonArray(); a.add(pos.getX()); a.add(pos.getY()); a.add(pos.getZ()); return a; }
    private static JsonObject request(String operation) { var o = new JsonObject(); o.addProperty("operation", operation); return o; }

    /** Runs the edits in order and stops at the first rejection, showing its message. */
    private void run(List<JsonObject> steps, String done) {
        if (steps.isEmpty()) { onClose(); return; }
        busy = true;
        feedback = "";
        rebuildWidgets();
        CompletableFuture<JsonObject> chain = CompletableFuture.completedFuture(null);
        for (var step : steps) chain = chain.thenCompose(unused -> access.projectCommand(step));
        chain.whenComplete((unused, error) -> screenExecutor.execute(() -> {
            busy = false;
            survey.refresh();
            if (error == null) { survey.say(done); onClose(); return; }
            feedback = AgentModels.error(error);
            confirmDelete = false;
            rebuildWidgets();
        }));
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !busy) { if (changed()) save(); else onClose(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public void onClose() { if (!busy) minecraft.setScreen(null); }
    @Override public void removed() { survey.closed(); }
    @Override public boolean isPauseScreen() { return false; }

    // The world and its outlines stay visible; only the panel is drawn.
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int bottom = top + panelHeight;
        g.fill(left, top, left + panelWidth, bottom, 0xF4121212);
        g.fill(left, top, left + 2, bottom, 0xFF000000 | color());
        int x = left + 10;
        String title = existing == null ? (station() ? "New station" : "New project box")
            : station() ? AgentModels.text(existing.row(), "label") : survey.projectName(projectId);
        String size = SurveyMode.size(low(), high());
        g.drawString(font, font.plainSubstrByWidth(title, panelWidth - 30 - font.width(size)), x, top + 10, 0xFFFFFFFF, false);
        g.drawString(font, size, left + panelWidth - 10 - font.width(size), top + 10, 0xFF000000 | SurveyMode.ACCENT, false);
        if (existing != null) {
            String sub = (station() ? survey.projectName(projectId) : "Project box") + (redrawn() ? " - redrawn" : "");
            g.drawString(font, font.plainSubstrByWidth(sub, panelWidth - 20), x, top + 23, 0xFF8C8C8C, false);
        }
        super.render(g, mouseX, mouseY, partialTick);
        if (!feedback.isBlank()) {
            var lines = font.split(Component.literal(feedback), panelWidth - 20);
            int y = bottom + 6;
            g.fill(left, bottom, left + panelWidth, bottom + 10 + lines.size() * 10, 0xF4121212);
            for (var line : lines) { g.drawString(font, line, x, y, 0xFFE59A8C, false); y += 10; }
        }
    }

    /** A quiet text toggle with a colored underline, like the settings tabs. */
    private final class Flat extends Button {
        private final java.util.function.BooleanSupplier selected;
        Flat(int x, int y, int width, String text, java.util.function.BooleanSupplier selected, Runnable press) {
            super(x, y, width, 20, Component.literal(text), b -> press.run(), n -> n.get());
            this.selected = selected;
        }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            boolean on = selected.getAsBoolean();
            if (isHoveredOrFocused() && !on) g.fill(getX() + 2, getY() + 2, getX() + getWidth() - 2, getY() + 18, 0x22FFFFFF);
            g.fill(getX() + 4, getY() + 18, getX() + getWidth() - 4, getY() + 19, on ? 0xFF000000 | color() : 0xFF3A3A3A);
            g.drawCenteredString(font, getMessage(), getX() + getWidth() / 2, getY() + 6, on ? 0xFFFFFF : 0x8C8C8C);
        }
    }

    /** A label and a value that changes to the next choice when clicked. */
    private final class Row extends Button {
        private final java.util.function.Supplier<String> value;
        Row(int x, int y, int width, String text, java.util.function.Supplier<String> value, Runnable press) {
            super(x, y, width, 20, Component.literal(text), b -> press.run(), n -> n.get());
            this.value = value;
        }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            if (isHoveredOrFocused()) g.fill(getX(), getY() + 1, getX() + getWidth(), getY() + 19, 0x22FFFFFF);
            g.drawString(font, getMessage(), getX() + 2, getY() + 6, 0x8C8C8C, false);
            String shown = value.get();
            int color = 0xFFFFFF;
            String text = font.plainSubstrByWidth(shown, getWidth() - 70) + "  ›";
            g.drawString(font, text, getX() + getWidth() - 2 - font.width(text), getY() + 6, color, false);
        }
    }
}
