package toomanyagents.ui;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Pending questions remain answerable while the agent works or waits. */
public final class AgentQuestionScreen extends Screen {
    private final AgentUiAccess access;
    private final Screen parent;
    private final String agentId, requestId;
    private JsonObject request;
    private final List<Button> choices = new ArrayList<>();
    private List<FormattedCharSequence> lines = List.of();
    private String answer = "", feedback = "Choose an option or write your own answer.";
    private boolean responding, resolved;
    private int left, contentWidth, detailsTop, detailsBottom, scroll, maxScroll, choicePage;
    private EditBox answerField;
    private Button sendButton, upButton, downButton;

    public AgentQuestionScreen(AgentUiAccess access, Screen parent, String agentId, JsonObject request) {
        super(Component.literal("Answer question"));
        this.access = access;
        this.parent = parent;
        this.agentId = agentId;
        this.request = request.deepCopy();
        requestId = AgentModels.text(request, "id");
    }

    @Override protected void init() {
        contentWidth = Math.min(760, width - 24);
        left = (width - contentWidth) / 2;
        var options = AgentModels.array(request, "options");
        choicePage = Math.clamp(choicePage, 0, Math.max(0, (options.size() - 1) / 4));
        int firstChoice = choicePage * 4;
        int visibleChoices = Math.min(4, options.size() - firstChoice);
        int columns = visibleChoices > 1 ? 2 : 1;
        int rows = (visibleChoices + columns - 1) / columns;
        boolean pages = options.size() > 4;
        detailsTop = 34;
        detailsBottom = height - (rows * 24 + 99 + (pages ? 24 : 0));
        upButton = addRenderableWidget(Button.builder(Component.literal("↑"), button -> scrollBy(-pageHeight()))
            .bounds(left + contentWidth - 46, 8, 20, 20).build());
        downButton = addRenderableWidget(Button.builder(Component.literal("↓"), button -> scrollBy(pageHeight()))
            .bounds(left + contentWidth - 20, 8, 20, 20).build());
        choices.clear();
        StringBuilder details = new StringBuilder(AgentModels.text(request, "title"));
        String extra = AgentModels.text(request, "details");
        if (!extra.isBlank()) details.append("\n\n").append(extra);
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i).getAsString();
            String label = (i + 1) + ". " + option;
            details.append("\n\n").append(label);
            if (i < firstChoice || i >= firstChoice + visibleChoices) continue;
            int slot = i - firstChoice;
            int w = (contentWidth - (columns - 1) * 6) / columns;
            Button choice = addRenderableWidget(Button.builder(Component.literal(label), button -> {
                answerField.setValue(option);
                setFocused(answerField);
            }).bounds(left + (slot % columns) * (w + 6), detailsBottom + 8 + (slot / columns) * 24, w, 20).build());
            if (font.width(label) > w - 8) choice.setTooltip(Tooltip.create(Component.literal(option)));
            choices.add(choice);
        }
        if (pages) {
            int w = (contentWidth - 6) / 2;
            int y = detailsBottom + 8 + rows * 24;
            addRenderableWidget(Button.builder(Component.literal("← Previous choices"), button -> {
                choicePage--;
                rebuildWidgets();
            }).bounds(left, y, w, 20).build()).active = choicePage > 0;
            addRenderableWidget(Button.builder(Component.literal("More choices →"), button -> {
                choicePage++;
                rebuildWidgets();
            }).bounds(left + w + 6, y, w, 20).build()).active = firstChoice + visibleChoices < options.size();
        }
        answerField = addRenderableWidget(new EditBox(font, left, height - 69, contentWidth, 20, Component.literal("Your answer")));
        answerField.setMaxLength(16384);
        answerField.setValue(answer);
        answerField.setHint(Component.literal("Write an answer…"));
        answerField.setResponder(value -> { answer = value; refresh(); });
        sendButton = addRenderableWidget(Button.builder(Component.literal("Send answer"), button -> respond())
            .bounds(left, height - 31, (contentWidth - 6) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back to chat"), button -> onClose())
            .bounds(left + (contentWidth - 6) / 2 + 6, height - 31, (contentWidth - 6) / 2, 20).build());
        lines = font.split(Component.literal(details.toString()), contentWidth - 24);
        maxScroll = Math.max(0, lines.size() * 11 - (detailsBottom - detailsTop - 12));
        scroll = Math.clamp(scroll, 0, maxScroll);
        refresh();
        setInitialFocus(answerField);
    }

    @Override public void tick() {
        JsonObject current = null;
        for (var item : AgentModels.array(access.snapshot(agentId), "requests")) {
            if (requestId.equals(AgentModels.text(item.getAsJsonObject(), "id"))) current = item.getAsJsonObject();
        }
        if (current == null) {
            if (!resolved && !responding) feedback = "This question is no longer pending.";
            resolved = true;
        } else if (!request.equals(current)) {
            request = current.deepCopy();
            rebuildWidgets();
        }
        refresh();
    }

    private void refresh() {
        if (sendButton == null) return;
        boolean editable = !responding && !resolved;
        for (int i = 0; i < choices.size(); i++) {
            int index = choicePage * 4 + i;
            var option = AgentModels.array(request, "options").get(index).getAsString();
            choices.get(i).active = editable;
            choices.get(i).setMessage(Component.literal((answer.equals(option) ? "✓ " : "") + (index + 1) + ". " + option));
        }
        answerField.setEditable(editable);
        sendButton.active = editable && !answer.isBlank();
        sendButton.setMessage(Component.literal(responding ? "Sending…" : "Send answer"));
        upButton.active = scroll > 0;
        downButton.active = scroll < maxScroll;
    }

    private void respond() {
        if (!sendButton.active) return;
        responding = true;
        feedback = "Sending answer…";
        refresh();
        CompletableFuture<Void> operation;
        try { operation = access.respond(agentId, requestId, answer); }
        catch (RuntimeException failure) { completed(failure); return; }
        operation.whenComplete((unused, failure) -> screenExecutor.execute(() -> completed(failure)));
    }

    private void completed(Throwable failure) {
        responding = false;
        if (failure == null) {
            resolved = true;
            if (minecraft.screen == this) minecraft.setScreen(parent);
        } else feedback = resolved ? "This question is no longer pending." : AgentModels.error(failure);
        refresh();
    }

    private int pageHeight() { return Math.max(22, detailsBottom - detailsTop - 12); }
    private void scrollBy(int amount) { scroll = Math.clamp(scroll + amount, 0, maxScroll); refresh(); }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= left && x <= left + contentWidth && y >= detailsTop && y <= detailsBottom) {
            scrollBy(-(int) (vertical * 33));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            scrollBy((key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1) * pageHeight());
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && answerField.isFocused()) {
            respond();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    public JsonObject diagnostics() {
        var result = request.deepCopy();
        result.addProperty("answer", answer);
        result.addProperty("choicePage", choicePage);
        result.addProperty("scroll", scroll);
        result.addProperty("maxScroll", maxScroll);
        result.addProperty("responding", responding);
        result.addProperty("resolved", resolved);
        return result;
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, left, 14, 0xFFDC80);
        graphics.fill(left, detailsTop, left + contentWidth, detailsBottom, 0xB0101010);
        graphics.enableScissor(left + 4, detailsTop + 4, left + contentWidth - 8, detailsBottom - 4);
        for (int i = 0; i < lines.size(); i++) {
            int y = detailsTop + 6 + i * 11 - scroll;
            if (y >= detailsTop - 11 && y < detailsBottom) graphics.drawString(font, lines.get(i), left + 8, y, 0xEEEEEE);
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int track = detailsBottom - detailsTop - 8;
            int thumb = Math.max(12, track * track / (track + maxScroll));
            int y = detailsTop + 4 + (track - thumb) * scroll / maxScroll;
            graphics.fill(left + contentWidth - 5, detailsTop + 4, left + contentWidth - 3, detailsBottom - 4, 0xFF444444);
            graphics.fill(left + contentWidth - 5, y, left + contentWidth - 3, y + thumb, 0xFFAAAAAA);
        }
        graphics.drawString(font, "Your answer", left, height - 82, 0xEEEEEE);
        graphics.drawString(font, font.plainSubstrByWidth(feedback, contentWidth), left, height - 43, 0xBDBDBD);
        if (font.width(feedback) > contentWidth && mouseY >= height - 46 && mouseY < height - 32) graphics.renderTooltip(font, font.split(Component.literal(feedback), contentWidth), mouseX, mouseY);
    }
}
