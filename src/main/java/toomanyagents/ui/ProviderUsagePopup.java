package toomanyagents.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Account limits share one percent-used scale; opening this never starts an agent turn. */
final class ProviderUsagePopup {
    private final AgentUiAccess access;
    private JsonObject report;
    private boolean open, pinned, fetching, dismissed;
    private long requestedAt;
    private int x, y, width, height, scroll, contentHeight;

    ProviderUsagePopup(AgentUiAccess access) { this.access = access; }

    void toggle() { pinned = !pinned; open = pinned; dismissed = !pinned; }
    void close() { pinned = false; open = false; dismissed = true; }
    boolean visible() { return open; }
    boolean contains(double mx, double my) {
        return open && mx >= x && mx < x + width && my >= y && my < y + height;
    }
    boolean scroll(double mx, double my, double amount) {
        if (!contains(mx, my)) return false;
        scroll = Math.clamp(scroll - (int)Math.round(amount * 24), 0, Math.max(0, contentHeight - height + 12));
        return true;
    }

    void render(GuiGraphics g, Font font, int sidebarWidth, int sidebarHeight, int mx, int my, boolean hover) {
        if (!hover) dismissed = false;
        boolean bridge = open && mx >= x && mx < x + width && my >= y + height && my < sidebarHeight - 11;
        open = pinned || !dismissed && hover || contains(mx, my) || bridge;
        if (!open) return;
        long now = System.currentTimeMillis();
        if (!fetching && now - requestedAt >= 60_000) {
            requestedAt = now;
            fetching = true;
            access.usage().whenComplete((value, failure) -> Minecraft.getInstance().execute(() -> {
                report = failure == null ? value : new JsonObject();
                fetching = false;
            }));
        }
        contentHeight = 27;
        if (report == null) contentHeight += 20;
        else if (providers().isEmpty()) contentHeight += 20;
        else for (var entry : providers()) {
            int count = AgentModels.array(entry.getValue().getAsJsonObject(), "windows").size();
            contentHeight += 23 + (count == 0 ? 18 : count * 38);
        }
        x = 10;
        width = sidebarWidth - 20;
        height = Math.min(contentHeight + 12, Math.max(40, sidebarHeight - 86));
        y = sidebarHeight - 40 - height;
        scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight - height + 12));
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        g.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF59625D);
        g.fill(x, y, x + width, y + height, 0xFF18201E);
        g.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
        int left = x + 9, right = x + width - 10, rowY = y + 10 - scroll;
        g.drawString(font, "Usage", left, rowY, 0xEEE6D3, false);
        String scale = "% used";
        g.drawString(font, scale, right - font.width(scale), rowY, 0x9FAAA8, false);
        rowY += 23;
        if (report == null || providers().isEmpty()) {
            g.drawString(font, fetching ? "Loading…" : "Usage unavailable", left, rowY, 0xAAB4B0, false);
        } else for (var entry : providers()) {
            JsonObject provider = entry.getValue().getAsJsonObject();
            String id = entry.getKey();
            ProviderIcon.render(g, id, left, rowY);
            g.drawString(font, id, left + 14, rowY, 0xEEE6D3, false);
            rowY += 18;
            var windows = AgentModels.array(provider, "windows");
            if (windows.isEmpty()) {
                String message = AgentModels.text(provider, "message");
                g.drawString(font, font.plainSubstrByWidth(message.isBlank() ? "Usage unavailable" : message, right - left), left, rowY, 0x9FAAA8, false);
                rowY += 18;
            }
            for (var limit : windows) {
                JsonObject window = limit.getAsJsonObject();
                boolean known = window.has("usedPercent") && !window.get("usedPercent").isJsonNull();
                double used = known ? window.get("usedPercent").getAsDouble() : 0;
                known &= Double.isFinite(used);
                String percent = known ? Math.round(used) + "%" : "—";
                int labelWidth = right - left - font.width(percent) - 6;
                g.drawString(font, font.plainSubstrByWidth(AgentModels.text(window, "label"), labelWidth), left, rowY, 0xC7D0C9, false);
                g.drawString(font, percent, right - font.width(percent), rowY, 0xC7D0C9, false);
                g.fill(left, rowY + 12, right, rowY + 16, 0xFF35433D);
                if (known) g.fill(left, rowY + 12, left + (int)Math.round((right - left) * Math.clamp(used, 0, 100) / 100), rowY + 16,
                    used >= 90 ? 0xFFD59B75 : 0xFF9BAD96);
                String reset = reset(window, now);
                g.drawString(font, font.plainSubstrByWidth(reset, right - left), left, rowY + 20, 0x8F9F98, false);
                rowY += 38;
            }
            rowY += 5;
        }
        g.disableScissor();
        if (contentHeight + 12 > height) {
            int track = height - 8, thumb = Math.max(12, track * height / (contentHeight + 12));
            int top = y + 4 + scroll * (track - thumb) / (contentHeight + 12 - height);
            g.fill(x + width - 4, top, x + width - 2, top + thumb, 0xFF75817F);
        }
        g.pose().popPose();
    }

    private java.util.Set<java.util.Map.Entry<String, com.google.gson.JsonElement>> providers() {
        return report==null?java.util.Set.of():report.entrySet();
    }

    private static String reset(JsonObject window, long now) {
        String reset = AgentModels.text(window,"resetsAt");
        if(reset.isBlank())return "Reset time unavailable";
        long resetTime;
        try { resetTime=java.time.Instant.parse(reset).toEpochMilli(); }
        catch(java.time.format.DateTimeParseException ignored){return reset;}
        long minutes=Math.max(0,(resetTime-now+59_999)/60_000);
        if (minutes == 0) return "Reset due";
        if (minutes < 60) return "Resets in " + minutes + "m";
        if (minutes < 1440) return "Resets in " + minutes / 60 + "h " + minutes % 60 + "m";
        return "Resets in " + minutes / 1440 + "d " + minutes % 1440 / 60 + "h";
    }
}
