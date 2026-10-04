package toomanyagents.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Review pending turns without squeezing them into the live chat. */
public final class AgentQueueScreen extends Screen {
    private final AgentUiAccess access;
    private final Screen parent;
    private final String agentId;
    private JsonArray messages = new JsonArray();
    private List<FormattedCharSequence> lines = List.of();
    private String feedback = "", selectedId = "";
    private int left, contentWidth, selected, scroll, maxScroll;
    private boolean cancelling;
    private Button previousButton, nextButton, cancelButton;

    public AgentQueueScreen(AgentUiAccess access, Screen parent, String agentId) {
        super(Component.literal("Queued messages"));
        this.access = access;
        this.parent = parent;
        this.agentId = agentId;
    }

    @Override protected void init() {
        contentWidth = Math.min(760, width - 24);
        left = (width - contentWidth) / 2;
        previousButton = addRenderableWidget(Button.builder(Component.literal("←"), button -> select(selected - 1))
            .bounds(left, height - 31, 24, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal("→"), button -> select(selected + 1))
            .bounds(left + 30, height - 31, 24, 20).build());
        cancelButton = addRenderableWidget(Button.builder(Component.literal("Cancel message"), button -> cancel())
            .bounds(left + 60, height - 31, 112, 20).build());
        var back = addRenderableWidget(Button.builder(Component.literal("Back to chat"), button -> onClose())
            .bounds(left + 178, height - 31, contentWidth - 178, 20).build());
        messages = AgentModels.queuedMessages(access.snapshot(agentId)).deepCopy();
        select(selected);
        setInitialFocus(back);
    }

    @Override public void tick() {
        var current = AgentModels.queuedMessages(access.snapshot(agentId));
        if (!current.equals(messages)) {
            messages = current.deepCopy();
            int index = selected;
            for (int i = 0; i < messages.size(); i++) {
                if (selectedId.equals(AgentModels.text(messages.get(i).getAsJsonObject(), "id"))) index = i;
            }
            select(index);
        }
        refresh();
    }

    private JsonObject message() { return messages.isEmpty() ? new JsonObject() : messages.get(selected).getAsJsonObject(); }

    private void select(int index) {
        selected = Math.clamp(index, 0, Math.max(0, messages.size() - 1));
        selectedId = AgentModels.text(message(), "id");
        lines = font.split(Component.literal(messages.isEmpty() ? "No messages waiting." : AgentInteractions.queuedText(message())), contentWidth - 24);
        scroll = 0;
        maxScroll = Math.max(0, lines.size() * 11 - (height - 118));
        refresh();
    }

    private void refresh() {
        previousButton.active = !cancelling && selected > 0;
        nextButton.active = !cancelling && selected + 1 < messages.size();
        cancelButton.active = !cancelling && !messages.isEmpty() && !AgentModels.text(access.snapshot(agentId),"status").equals("disconnected");
        cancelButton.setMessage(Component.literal(cancelling ? "Cancelling…" : "Cancel message"));
    }

    private void cancel() {
        if (!cancelButton.active) return;
        cancelling = true;
        feedback = "Cancelling…";
        refresh();
        access.cancelQueued(agentId, selectedId).whenComplete((unused, failure) -> screenExecutor.execute(() -> {
            cancelling = false;
            feedback = failure == null ? "" : AgentModels.error(failure);
            refresh();
        }));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, messages.isEmpty() ? "Queued messages" : "Queued " + (selected + 1) + " of " + messages.size(), left, 12, 0xFFFFFF);
        String details = AgentModels.text(message(), "model") + " - " + AgentModels.text(message(), "reasoningLevel")
            + " - " + AgentModels.speedLabel(AgentModels.text(message(), "serviceTier"));
        graphics.drawString(font, font.plainSubstrByWidth(messages.isEmpty() ? "" : details, contentWidth), left, 29, 0xAAAAAA);
        int top = 45, bottom = height - 61;
        graphics.fill(left, top, left + contentWidth, bottom, 0xB0101010);
        graphics.enableScissor(left + 4, top + 4, left + contentWidth - 8, bottom - 4);
        for (int i = 0; i < lines.size(); i++) {
            int y = top + 6 + i * 11 - scroll;
            if (y >= top - 11 && y < bottom) graphics.drawString(font, lines.get(i), left + 8, y, 0xEEEEEE);
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int track = bottom - top - 8;
            int thumb = Math.max(12, track * track / (track + maxScroll));
            int y = top + 4 + (track - thumb) * scroll / maxScroll;
            graphics.fill(left + contentWidth - 5, top + 4, left + contentWidth - 3, bottom - 4, 0xFF444444);
            graphics.fill(left + contentWidth - 5, y, left + contentWidth - 3, y + thumb, 0xFFAAAAAA);
        }
        String footer = feedback.isBlank() ? AgentInteractions.waitingReason(message()) : feedback;
        graphics.drawString(font, font.plainSubstrByWidth(footer, contentWidth), left, height - 49, 0xAAAAAA);
        if (font.width(footer) > contentWidth && mouseY >= height - 53 && mouseY < height - 33) graphics.renderTooltip(font, font.split(Component.literal(footer), contentWidth), mouseX, mouseY);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= left && x <= left + contentWidth && y >= 45 && y <= height - 61) {
            scroll = Math.clamp(scroll - (int) (vertical * 33), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            scroll = Math.clamp(scroll + (key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1) * Math.max(22, height - 118), 0, maxScroll);
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
