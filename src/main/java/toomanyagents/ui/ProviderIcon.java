package toomanyagents.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Pixel provider marks sized to Minecraft's 9px text line. */
final class ProviderIcon {
    static final int SIZE = 9;
    private static final ResourceLocation CLAUDE = texture("claude");
    private static final ResourceLocation OPENAI = texture("openai");
    private static final ResourceLocation HERMES = texture("hermes");
    private static final ResourceLocation CURSOR = texture("cursor");
    private static final ResourceLocation PI = texture("pi");
    private static final ResourceLocation OPENCODE = texture("opencode");
    private static final ResourceLocation OMP = texture("omp");
    private static final ResourceLocation GROK = texture("grok");

    private ProviderIcon() {}

    /** Draws the mark for a provider id; y is the top of the text it sits beside. Returns the width drawn. */
    static int render(GuiGraphics g, String providerId, int x, int textY) {
        ResourceLocation icon = textureFor(providerId);
        if (icon == null) return 0;
        draw(g, icon, x, textY - 1);
        return SIZE;
    }

    static void draw(GuiGraphics g, ResourceLocation icon, int x, int y) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(icon, x, y, 0, 0, SIZE, SIZE, SIZE, SIZE);
        RenderSystem.disableBlend();
    }

    static ResourceLocation textureFor(String providerId) {
        return switch (providerId) {
            case "claude-code" -> CLAUDE;
            case "codex" -> OPENAI;
            case "acp-hermes-agent" -> HERMES;
            case "acp-cursor" -> CURSOR;
            case "pi" -> PI;
            case "acp-opencode" -> OPENCODE;
            case "acp-omp" -> OMP;
            case "acp-grok" -> GROK;
            default -> null;
        };
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath("too_many_agents", "textures/gui/provider/" + name + ".png");
    }
}
