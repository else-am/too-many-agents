package toomanyagents.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Pixel provider marks sized to Minecraft's 9px text line. */
final class ProviderIcon {
    static final int SIZE = 9;
    private static final ResourceLocation CLAUDE = texture("claude");
    private static final ResourceLocation OPENAI = texture("openai");

    private ProviderIcon() {}

    /** Draws the mark for a provider id; y is the top of the text it sits beside. Returns the width drawn. */
    static int render(GuiGraphics g, String providerId, int x, int textY) {
        ResourceLocation icon = switch (providerId) {
            case "claude-code" -> CLAUDE;
            case "codex" -> OPENAI;
            default -> null;
        };
        if (icon == null) return 0;
        g.blit(icon, x, textY - 1, 0, 0, SIZE, SIZE, SIZE, SIZE);
        return SIZE;
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath("too_many_agents", "textures/gui/provider/" + name + ".png");
    }
}
