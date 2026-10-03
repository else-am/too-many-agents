package toomanyagents.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import toomanyagents.TooManyAgentsClientSettings;

/**
 * Agent screens may use their own GUI scale without changing the game's.
 * They lay out in their own coordinates; drawing is scaled and mouse input converted.
 * The window's scale is untouched, so game-scaled UI can share a frame with mod UI.
 */
public final class ScreenScale {
    private ScreenScale() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Pre event) -> {
            var screen = event.getScreen();
            float factor = factor(screen);
            if (factor == 1) return;
            var window = Minecraft.getInstance().getWindow();
            screen.width = (int) Math.ceil(window.getGuiScaledWidth() / factor);
            screen.height = (int) Math.ceil(window.getGuiScaledHeight() / factor);
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Render.Pre event) -> {
            var screen = event.getScreen();
            float factor = factor(screen);
            if (factor == 1) return;
            event.setCanceled(true);
            var graphics = new Scaled(event.getGuiGraphics(), factor, screen);
            screen.renderWithTooltip(graphics, (int) (event.getMouseX() / factor), (int) (event.getMouseY() / factor), event.getPartialTick());
            graphics.flush();
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonPressed.Pre event) -> {
            float factor = factor(event.getScreen());
            if (factor == 1) return;
            event.setCanceled(true);
            event.getScreen().mouseClicked(event.getMouseX() / factor, event.getMouseY() / factor, event.getButton());
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonReleased.Pre event) -> {
            float factor = factor(event.getScreen());
            if (factor == 1) return;
            event.setCanceled(true);
            event.getScreen().mouseReleased(event.getMouseX() / factor, event.getMouseY() / factor, event.getButton());
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseDragged.Pre event) -> {
            float factor = factor(event.getScreen());
            if (factor == 1) return;
            event.setCanceled(true);
            event.getScreen().mouseDragged(event.getMouseX() / factor, event.getMouseY() / factor, event.getMouseButton(),
                event.getDragX() / factor, event.getDragY() / factor);
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseScrolled.Pre event) -> {
            float factor = factor(event.getScreen());
            if (factor == 1) return;
            event.setCanceled(true);
            event.getScreen().mouseScrolled(event.getMouseX() / factor, event.getMouseY() / factor, event.getScrollDeltaX(), event.getScrollDeltaY());
        });
    }

    /** Largest scale that fits the window, for the settings choices. */
    public static int max() {
        var client = Minecraft.getInstance();
        return client.getWindow().calculateScale(0, client.isEnforceUnicode());
    }

    /** Screen coordinates per game GUI coordinate; 1 when the screen uses the game's scale. */
    public static float factor(Screen screen) {
        int own = TooManyAgentsClientSettings.get().screenScale();
        if (own <= 0 || !scaled(screen)) return 1;
        var client = Minecraft.getInstance();
        var window = client.getWindow();
        return (float) (window.calculateScale(own, client.isEnforceUnicode()) / window.getGuiScale());
    }

    // Mod settings and agent inventories keep the game's scale.
    private static boolean scaled(Screen screen) {
        if (screen instanceof InventoryAgentSidebar || screen instanceof AgentWorkspaceScreen
            || screen instanceof AgentChatScreen chat && chat.docked()) return false;
        return screen != null && screen.getClass().getPackageName().equals(ScreenScale.class.getPackageName())
            && !(screen instanceof TooManyAgentsSettingsScreen || screen instanceof ProviderSettingsScreen || screen instanceof AgentInventoryScreen);
    }

    /** Draws a screen's coordinates at the chosen scale, including clipping and tooltip placement. */
    private static final class Scaled extends GuiGraphics {
        private final float factor;
        private final Screen screen;

        Scaled(GuiGraphics parent, float factor, Screen screen) {
            super(Minecraft.getInstance(), Minecraft.getInstance().renderBuffers().bufferSource());
            this.factor = factor;
            this.screen = screen;
            pose().last().pose().set(parent.pose().last().pose());
            pose().scale(factor, factor, 1);
        }

        @Override public int guiWidth() { return screen.width; }
        @Override public int guiHeight() { return screen.height; }

        // Minecraft clips in game GUI coordinates regardless of the pose.
        @Override public void enableScissor(int minX, int minY, int maxX, int maxY) {
            super.enableScissor((int) Math.floor(minX * factor), (int) Math.floor(minY * factor),
                (int) Math.ceil(maxX * factor), (int) Math.ceil(maxY * factor));
        }

        @Override public boolean containsPointInScissor(int x, int y) {
            return super.containsPointInScissor((int) (x * factor), (int) (y * factor));
        }
    }
}
