package toomanyagents.ui;

import toomanyagents.TooManyAgentsClientSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

public final class TooManyAgentsSettingsScreen extends Screen {
    private final Screen parent;
    private final AgentUiAccess access;
    private final TooManyAgentsClientSettings settings = TooManyAgentsClientSettings.get();
    private int left, top, contentWidth;

    public TooManyAgentsSettingsScreen(Screen parent) { this(parent, null); }
    /** With access (from inside a world), the screen also links to this world's archive. */
    public TooManyAgentsSettingsScreen(Screen parent, AgentUiAccess access) {
        super(Component.literal("Too Many Agents settings"));
        this.parent = parent;
        this.access = access;
    }

    @Override protected void init() {
        contentWidth = Math.min(390, width - 24);
        left = (width - contentWidth) / 2;
        top = Math.max(8, (height - (access != null ? 242 : 214)) / 2);
        addRenderableWidget(Button.builder(soundLabel(), button -> {
            settings.setNotificationSound(!settings.notificationSound());
            button.setMessage(soundLabel());
        }).bounds(left, top + 34, contentWidth, 20).build())
            .setTooltip(Tooltip.create(Component.literal("A quiet sound for a new unread reply or request for attention.")));
        addRenderableWidget(Button.builder(communicationLabel(), button -> {
            settings.setShowAgentCommunication(!settings.showAgentCommunication());
            button.setMessage(communicationLabel());
        }).bounds(left, top + 62, contentWidth, 20).build())
            .setTooltip(Tooltip.create(Component.literal("Show short summaries of delivered agent messages. Full messages stay in their conversations. This does not change who can communicate.")));
        addRenderableWidget(Button.builder(scaleLabel(), button -> {
            settings.setScreenScale(settings.screenScale() >= ScreenScale.max() ? 0 : settings.screenScale() + 1);
            button.setMessage(scaleLabel());
        }).bounds(left, top + 90, contentWidth, 20).build())
            .setTooltip(Tooltip.create(Component.literal("GUI scale for agent chat and inventory side panels. These settings and agent inventories use the game's scale.")));
        addRenderableWidget(Button.builder(Component.literal("Minecraft key binds…"), button ->
            minecraft.setScreen(new KeyBindsScreen(this, minecraft.options)))
            .bounds(left, top + 118, contentWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Agent providers…"), button ->
            minecraft.setScreen(new ProviderSettingsScreen(this)))
            .bounds(left, top + 146, contentWidth, 20).build());
        if (access != null) addRenderableWidget(Button.builder(Component.literal("Archive…"), button ->
            minecraft.setScreen(new ArchiveScreen(access, this)))
            .bounds(left, top + 174, contentWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(left, top + (access != null ? 212 : 184), contentWidth, 20).build());
    }

    private Component soundLabel() { return Component.literal("Notification sound: " + (settings.notificationSound() ? "ON" : "OFF")); }
    private Component scaleLabel() {
        int scale = Math.min(settings.screenScale(), ScreenScale.max());
        return Component.literal("Agent screen GUI scale: " + (scale == 0 ? "Same as game" : scale));
    }
    private Component communicationLabel() { return Component.literal("Agent communication in chat: " + (settings.showAgentCommunication() ? "ON" : "OFF")); }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, top + 8, 0xFFFFFF);
        if (!settings.error().isBlank()) graphics.drawString(font, font.plainSubstrByWidth(settings.error(),contentWidth),
            left, top + 173, 0xFFAAAA);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
