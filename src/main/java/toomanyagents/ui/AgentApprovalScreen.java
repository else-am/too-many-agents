package toomanyagents.ui;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Readable approval details with explicit decisions supplied by the backend. */
public final class AgentApprovalScreen extends Screen {
    private final AgentUiAccess access;
    private final Screen parent;
    private final String agentId, requestId;
    private JsonObject request;
    private final List<Button> decisions = new ArrayList<>();
    private List<FormattedCharSequence> lines = List.of();
    private String feedback = "Scroll to read details - Page Up / Page Down";
    private boolean responding, resolved;
    private int left, contentWidth, detailsTop, detailsBottom, scroll, maxScroll;
    private Button upButton, downButton;

    public AgentApprovalScreen(AgentUiAccess access, Screen parent, String agentId, JsonObject request) {
        super(Component.literal("Approval requested"));
        this.access = access;
        this.parent = parent;
        this.agentId = agentId;
        this.request = request.deepCopy();
        requestId = AgentModels.text(request, "id");
    }

    @Override protected void init() {
        contentWidth = Math.min(760, width - 24);
        left = (width - contentWidth) / 2;
        var options = AgentModels.array(AgentModels.object(request,"payload"), "availableDecisions");
        int columns = options.size() > 1 ? 2 : 1;
        int rows = (options.size() + columns - 1) / columns;
        detailsTop = 34;
        detailsBottom = height - (rows * 24 + 58);
        upButton = addRenderableWidget(Button.builder(Component.literal("↑"), button -> scrollBy(-Math.max(22, detailsBottom - detailsTop - 12)))
            .bounds(left + contentWidth - 46, 8, 20, 20).build());
        downButton = addRenderableWidget(Button.builder(Component.literal("↓"), button -> scrollBy(Math.max(22, detailsBottom - detailsTop - 12)))
            .bounds(left + contentWidth - 20, 8, 20, 20).build());
        decisions.clear();
        for (int i = 0; i < options.size(); i++) {
            String decisionId = options.get(i).getAsString();
            String label = AgentInteractions.decisionLabel(decisionId);
            int w = (contentWidth - (columns - 1) * 6) / columns;
            Button decision = addRenderableWidget(Button.builder(Component.literal(label), button -> respond(decisionId))
                .bounds(left + (i % columns) * (w + 6), detailsBottom + 8 + (i / columns) * 24, w, 20).build());
            if (font.width(label) > w - 8) decision.setTooltip(Tooltip.create(Component.literal(label)));
            decisions.add(decision);
        }
        var back = addRenderableWidget(Button.builder(Component.literal("Back to chat"), button -> onClose())
            .bounds(left, height - 31, contentWidth, 20).build());
        String title = "Approval requested";
        String details = AgentInteractions.approvalDetails(request);
        lines = font.split(Component.literal(title + "\n\n" + (details.isBlank() ? "No additional details provided." : details)), contentWidth - 24);
        maxScroll = Math.max(0, lines.size() * 11 - (detailsBottom - detailsTop - 12));
        scroll = Math.clamp(scroll, 0, maxScroll);
        refresh();
        setInitialFocus(back);
    }

    @Override public void tick() {
        JsonObject current = null;
        for (var item : AgentModels.interactions(access.snapshot(agentId))) {
            if (requestId.equals(AgentModels.text(item.getAsJsonObject(), "id")) && java.util.Set.of("pending","resolving").contains(AgentModels.text(item.getAsJsonObject(),"status"))) current = item.getAsJsonObject();
        }
        if (current == null) {
            if (!resolved && !responding) feedback = "This request is no longer pending.";
            resolved = true;
        } else if (!request.equals(current)) {
            request = current.deepCopy();
            rebuildWidgets();
        }
        refresh();
    }

    private void refresh() {
        for (Button decision : decisions) decision.active = !responding && !resolved && !AgentModels.text(request,"status").equals("resolving") && !AgentModels.text(access.snapshot(agentId),"status").equals("disconnected");
        upButton.active = scroll > 0;
        downButton.active = scroll < maxScroll;
    }

    private void respond(String label) {
        if (responding || resolved || AgentModels.text(access.snapshot(agentId),"status").equals("disconnected")) return;
        responding = true;
        feedback = "Sending decision…";
        refresh();
        CompletableFuture<Void> operation;
        try { operation = access.respond(agentId, requestId, AgentInteractions.approvalResolution(request,label)); }
        catch (RuntimeException failure) { completed(failure); return; }
        operation.whenComplete((unused, failure) -> screenExecutor.execute(() -> completed(failure)));
    }

    private void completed(Throwable failure) {
        responding = false;
        if (failure == null) {
            resolved = true;
            if (minecraft.screen == this) minecraft.setScreen(parent);
        } else feedback = AgentModels.error(failure);
        refresh();
    }

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
            scrollBy((key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1) * Math.max(22, detailsBottom - detailsTop - 12));
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }
    public JsonObject diagnostics() {
        var result = request.deepCopy();
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
        graphics.drawString(font, font.plainSubstrByWidth(feedback, contentWidth), left, height - 46, 0xBDBDBD);
        if (font.width(feedback) > contentWidth && mouseY >= height - 49 && mouseY < height - 32) graphics.renderTooltip(font, font.split(Component.literal(feedback), contentWidth), mouseX, mouseY);
    }
}
