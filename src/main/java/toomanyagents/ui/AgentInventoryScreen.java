package toomanyagents.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import toomanyagents.AgentInventoryMenu;

/** Vanilla container interaction, with both equipment views and no crafting controls. */
public final class AgentInventoryScreen extends AbstractContainerScreen<AgentInventoryMenu> {
    private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");
    private boolean renderingPreview;
    private boolean inventoryPositioned;
    private int previousHeight;

    public AgentInventoryScreen(AgentInventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = AgentInventoryMenu.PLAYER_Y + 166;
    }

    @Override protected void init() {
        int previousTop = topPos;
        boolean wasScrollable = inventoryPositioned && previousHeight < imageHeight + 30;
        super.init();
        // Keep the player at vanilla's position when the upper inventory fits.
        topPos = scrollable() ? clampTop(wasScrollable ? previousTop : minimumTop())
            : Math.max(22, (height - 166) / 2 - AgentInventoryMenu.PLAYER_Y);
        inventoryPositioned = true;
        previousHeight = height;

    }

    public boolean isRenderingPreview() { return renderingPreview; }
    public String agentId() { return menu.agentId; }
    public int playerInventoryLeft() { return leftPos + AgentInventoryMenu.PLAYER_X; }
    public int playerInventoryTop() { return topPos + AgentInventoryMenu.PLAYER_Y; }

    public void closeAgentInventory() {
        var player = minecraft.player;
        if (player == null) return;
        // Close the native menu without briefly returning to the game and grabbing the mouse.
        player.connection.send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(menu.containerId));
        player.containerMenu = player.inventoryMenu;
        minecraft.setScreen(new InventoryScreen(player));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (scrollable()) graphics.drawCenteredString(font, "Scroll to view both inventories", leftPos + imageWidth / 2,
            height - 9, 0xAEB6B5);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBg(graphics, partialTick, mouseX, mouseY);
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        var entity = minecraft.level == null ? null : minecraft.level.getEntity(menu.entityId);
        panel(graphics, leftPos, topPos, entity instanceof LivingEntity living ? living : null, mouseX, mouseY);
        panel(graphics, playerInventoryLeft(), playerInventoryTop(), minecraft.player, mouseX, mouseY);
    }

    private void panel(GuiGraphics graphics, int x, int y, LivingEntity body, int mouseX, int mouseY) {
        graphics.blit(BACKGROUND, x, y, 0, 0, 176, 166);
        graphics.fill(x + 97, y + 7, x + 171, y + 61, 0xFFC6C6C6);
        if (body == null) return;
        int portraitScale = (int)Math.min(30, Math.min(60 / Math.max(.1, body.getBbHeight()), 42 / Math.max(.1, body.getBbWidth())));
        renderingPreview = true;
        try {
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x + 26, y + 8, x + 75, y + 78,
                portraitScale, .0625F, mouseX, mouseY, body);
        } finally { renderingPreview = false; }
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, font.plainSubstrByWidth(title.getString(), imageWidth), 0, -13, 0xFFFFFF);
        graphics.drawString(font, playerInventoryTitle, AgentInventoryMenu.PLAYER_X, AgentInventoryMenu.PLAYER_Y - 13, 0xFFFFFF);
    }

    private boolean scrollable() { return height < imageHeight + 30; }
    private int minimumTop() { return height - imageHeight - 8; }
    private int clampTop(int y) { return Math.max(minimumTop(), Math.min(22, y)); }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (scrollable() && x >= leftPos && x < leftPos + imageWidth && vertical != 0) {
            topPos = clampTop(topPos + (int)Math.round(vertical * 18));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    public JsonObject playerInventoryBounds() {
        var result = new JsonObject();
        result.addProperty("x", playerInventoryLeft());
        result.addProperty("y", playerInventoryTop());
        result.addProperty("width", 176);
        result.addProperty("height", 166);
        return result;
    }

    /** Native diagnostics read client menu copies, never the server's live inventory. */
    public JsonObject inventoryState() {
        var result = new JsonObject();
        result.addProperty("containerId", menu.containerId);
        result.addProperty("agentId", menu.agentId);
        result.addProperty("carriedCount", menu.getCarried().getCount());
        result.addProperty("scrollable", scrollable());
        result.add("playerBounds", playerInventoryBounds());
        var slots = new JsonArray();
        for (var slot : menu.slots) {
            var row = new JsonObject();
            row.addProperty("slot", slot.index);
            row.addProperty("x", leftPos + slot.x);
            row.addProperty("y", topPos + slot.y);
            row.addProperty("size", 16);
            row.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString());
            row.addProperty("count", slot.getItem().getCount());
            slots.add(row);
        }
        result.add("slots", slots);
        return result;
    }
}
