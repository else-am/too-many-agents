package toomanyagents.screens;

import java.awt.Cursor;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * With an empty hand, the crosshair is the mouse of the screen it points at: hover, click, drag and scroll go to the
 * page. Clicking into a text field starts typing mode, where keys go to the page until Esc or a click elsewhere.
 * Render thread only.
 */
public final class ScreenInput {
    static final double REACH = 8;
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L, FOCUS_AFTER_CLICK_NANOS = 1_500_000_000L;

    private record Aim(ScreenView view, double x, double y) {}
    private static Aim aim;
    private static ScreenView hovered, pressed;
    private static final Set<Integer> pressedButtons = new HashSet<>(), consumedButtons = new HashSet<>();
    private static int lastX = -1, lastY = -1, clicks;
    private static long lastClickAt;
    private static ScreenView clicked;
    private static int lastClickButton = -1;

    private static ScreenView typing;
    private static boolean typingAddress, addressSelected;
    private static final StringBuilder address = new StringBuilder();

    private ScreenInput() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> frame(event.getPartialTick().getGameTimeDeltaPartialTick(true)));
        NeoForge.EVENT_BUS.addListener(ScreenInput::mouseButton);
        NeoForge.EVENT_BUS.addListener(ScreenInput::scroll);
        NeoForge.EVENT_BUS.addListener(ScreenInput::crosshair);
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> hint(event.getGuiGraphics()));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            if (typing != null && Minecraft.getInstance().screen != null) stopTyping(false);
        });
    }

    // What the views and renderer ask.

    static String addressText(ScreenView view) { return typing == view && typingAddress ? address.toString() : null; }
    static boolean editingAddress(ScreenView view) { return addressText(view) != null; }
    static boolean addressSelected() { return addressSelected; }

    static void forget(ScreenView view) {
        if (typing == view) typing = null;
        if (hovered == view) hovered = null;
        if (pressed == view) { pressed = null; pressedButtons.clear(); }
        if (clicked == view) clicked = null;
        if (aim != null && aim.view == view) aim = null;
    }

    /** The page reports whether its focused element takes text. Only a click of yours starts typing. */
    static void editingChanged(ScreenView view, boolean editable) {
        if (editable && clicked == view && System.nanoTime() - lastClickAt < FOCUS_AFTER_CLICK_NANOS && (typing == null || typing == view)) {
            if (typing == null) KeyMapping.releaseAll();
            typing = view;
            typingAddress = false;
        } else if (!editable && typing == view && !typingAddress) {
            typing = null;
        }
    }

    // Pointing.

    private static boolean pointing() {
        var mc = Minecraft.getInstance();
        return mc.screen == null && mc.getOverlay() == null && mc.player != null && !mc.player.isSpectator()
            && mc.player.getMainHandItem().isEmpty();
    }

    private static void frame(float partialTick) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            aim = null;
            return;
        }
        Vec3 eye = mc.player.getEyePosition(partialTick), look = mc.player.getViewVector(partialTick);
        aim = pointing() ? find(eye, look) : null;
        if (pressed != null) {
            // A held button keeps dragging on its own screen, even past the edge.
            double[] at = pressed.hit(eye, look);
            if (at != null) move(pressed, at[0], at[1]);
            return;
        }
        if (hovered != null && (aim == null || aim.view != hovered)) {
            if (hovered.browser != null) hovered.browser.mouse(MouseEvent.MOUSE_EXITED, lastX, lastY, MouseEvent.NOBUTTON, 0, 0);
            hovered = null;
        }
        if (aim != null) {
            hovered = aim.view;
            move(aim.view, aim.x, aim.y);
        }
    }

    private static Aim find(Vec3 eye, Vec3 look) {
        Aim best = null;
        double bestDistance = REACH;
        for (var view : Screens.views()) {
            double[] at = view.hit(eye, look);
            if (at == null || at[2] > bestDistance) continue;
            if (!view.onPage(at[0], at[1]) && view.barPart(at[0], at[1]) < 0) continue;
            best = new Aim(view, at[0], at[1]);
            bestDistance = at[2];
        }
        if (best == null) return null;
        // Something solid in front of the screen blocks it.
        var mc = Minecraft.getInstance();
        var blocked = mc.level.clip(new ClipContext(eye, eye.add(look.scale(bestDistance - 0.01)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return blocked.getType() == HitResult.Type.MISS ? best : null;
    }

    private static void move(ScreenView view, double x, double y) {
        if (view.browser == null) return;
        int px = view.pixelX(x), py = view.pixelY(y);
        if (px == lastX && py == lastY) return;
        lastX = px;
        lastY = py;
        int id = pressedButtons.isEmpty() ? MouseEvent.MOUSE_MOVED : MouseEvent.MOUSE_DRAGGED;
        view.browser.mouse(id, px, py, MouseEvent.NOBUTTON, modifiers() | buttonMask(), 0);
    }

    private static void mouseButton(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre event) {
        int button = event.getButton();
        if (event.getAction() == GLFW.GLFW_RELEASE) {
            if (pressedButtons.remove(button)) {
                event.setCanceled(true);
                if (pressed != null && pressed.browser != null)
                    pressed.browser.mouse(MouseEvent.MOUSE_RELEASED, lastX, lastY, awtButton(button), modifiers() | buttonMask(), clicks);
                if (pressedButtons.isEmpty()) pressed = null;
            } else if (consumedButtons.remove(button)) event.setCanceled(true);
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        if (typingAddress && (aim == null || aim.view != typing || aim.view.barPart(aim.x, aim.y) != 3)) stopTyping(false);
        if (aim == null) return;
        event.setCanceled(true);
        var view = aim.view;
        int part = view.barPart(aim.x, aim.y);
        if (part >= 0 || view.url.isBlank() || !Trust.allowedHere() || view.crashed || view.browser == null) {
            // Our own controls; the page never sees these clicks.
            consumedButtons.add(button);
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            if (part == 0 && view.browser != null) view.browser.goBack();
            else if (part == 1 && view.browser != null) view.browser.goForward();
            else if (part == 2) view.reload();
            else if (part == 3 || view.url.isBlank()) startAddress(view);
            else if (!Trust.allowedHere()) Trust.allow(Minecraft.getInstance().getSingleplayerServer());
            else if (view.crashed) view.reload();
            return;
        }
        int px = view.pixelX(aim.x), py = view.pixelY(aim.y);
        long now = System.nanoTime();
        boolean again = clicked == view && button == lastClickButton && now - lastClickAt < DOUBLE_CLICK_NANOS
            && Math.abs(px - lastX) < 6 && Math.abs(py - lastY) < 6;
        clicks = again ? clicks + 1 : 1;
        lastClickAt = now;
        lastClickButton = button;
        clicked = view;
        for (var other : Screens.views()) if (other != view && other.browser != null) other.browser.setFocus(false);
        view.browser.setFocus(true);
        if (typing != null && typing != view) stopTyping(false);
        pressed = view;
        pressedButtons.add(button);
        lastX = px;
        lastY = py;
        view.browser.mouse(MouseEvent.MOUSE_PRESSED, px, py, awtButton(button), modifiers() | buttonMask(), clicks);
    }

    private static void scroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        if (aim == null || aim.view.browser == null || aim.view.barPart(aim.x, aim.y) >= 0) return;
        event.setCanceled(true);
        double delta = event.getScrollDeltaY() != 0 ? event.getScrollDeltaY() : event.getScrollDeltaX();
        int mods = modifiers() | (event.getScrollDeltaY() == 0 ? InputEvent.SHIFT_DOWN_MASK : 0);
        aim.view.browser.wheel(aim.view.pixelX(aim.x), aim.view.pixelY(aim.y), (int) Math.round(delta * 100), mods);
    }

    // Typing.

    /** Called from the keyboard handler before Minecraft sees a key. True when the key went to a screen. */
    public static boolean keyPress(int key, int scancode, int action, int mods) {
        if (typing == null) return false;
        if (Minecraft.getInstance().screen != null) {
            stopTyping(false);
            return false;
        }
        if (typingAddress) {
            if (action != GLFW.GLFW_RELEASE) addressKey(key, mods);
            return true;
        }
        var browser = typing.browser;
        if (browser == null) {
            stopTyping(false);
            return false;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (action == GLFW.GLFW_PRESS) stopTyping(true);
            return true;
        }
        if (shortcut(mods) && action != GLFW.GLFW_RELEASE) {
            var frame = browser.getFocusedFrame() != null ? browser.getFocusedFrame() : browser.getMainFrame();
            boolean handled = true;
            switch (key) {
                case GLFW.GLFW_KEY_C -> frame.copy();
                case GLFW.GLFW_KEY_V -> frame.paste();
                case GLFW.GLFW_KEY_X -> frame.cut();
                case GLFW.GLFW_KEY_A -> frame.selectAll();
                case GLFW.GLFW_KEY_Z -> { if ((mods & GLFW.GLFW_MOD_SHIFT) != 0) frame.redo(); else frame.undo(); }
                case GLFW.GLFW_KEY_Y -> frame.redo();
                default -> handled = false;
            }
            if (handled) return true;
        }
        int code;
        char character = KeyEvent.CHAR_UNDEFINED;
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> { code = KeyEvent.VK_BACK_SPACE; character = '\b'; }
            case GLFW.GLFW_KEY_DELETE -> { code = KeyEvent.VK_DELETE; character = 127; }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { code = KeyEvent.VK_ENTER; character = '\r'; }
            case GLFW.GLFW_KEY_TAB -> { code = KeyEvent.VK_TAB; character = '\t'; }
            case GLFW.GLFW_KEY_LEFT -> code = KeyEvent.VK_LEFT;
            case GLFW.GLFW_KEY_RIGHT -> code = KeyEvent.VK_RIGHT;
            case GLFW.GLFW_KEY_UP -> code = KeyEvent.VK_UP;
            case GLFW.GLFW_KEY_DOWN -> code = KeyEvent.VK_DOWN;
            case GLFW.GLFW_KEY_HOME -> code = KeyEvent.VK_HOME;
            case GLFW.GLFW_KEY_END -> code = KeyEvent.VK_END;
            case GLFW.GLFW_KEY_PAGE_UP -> code = KeyEvent.VK_PAGE_UP;
            case GLFW.GLFW_KEY_PAGE_DOWN -> code = KeyEvent.VK_PAGE_DOWN;
            default -> {
                // Printable keys are named by the keyboard layout.
                String name = GLFW.glfwGetKeyName(key, scancode);
                if (name == null || name.length() != 1) return true;
                character = name.charAt(0);
                code = KeyEvent.getExtendedKeyCodeForChar(character);
            }
        }
        int awtMods = awtModifiers(mods);
        browser.key(action == GLFW.GLFW_RELEASE ? KeyEvent.KEY_RELEASED : KeyEvent.KEY_PRESSED, code, character, awtMods);
        // GLFW sends no character for Enter, so the page would never see it as typed.
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (action != GLFW.GLFW_RELEASE) browser.key(KeyEvent.KEY_TYPED, code, character, awtMods);
        }
        return true;
    }

    /** Called from the keyboard handler for typed text. True when the text went to a screen. */
    public static boolean charTyped(int codePoint, int mods) {
        if (typing == null || Minecraft.getInstance().screen != null) return false;
        if (typingAddress) {
            if (addressSelected) address.setLength(0);
            addressSelected = false;
            address.appendCodePoint(codePoint);
            return true;
        }
        if (typing.browser == null) return false;
        for (char c : Character.toChars(codePoint)) typing.browser.key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c, awtModifiers(mods));
        return true;
    }

    private static void startAddress(ScreenView view) {
        if (typing == null) KeyMapping.releaseAll();
        typing = view;
        typingAddress = true;
        address.setLength(0);
        address.append(view.url);
        addressSelected = !view.url.isBlank();
    }

    private static void stopTyping(boolean blurPage) {
        if (blurPage && typing != null && typing.browser != null)
            typing.browser.executeJavaScript("document.activeElement && document.activeElement.blur()", "", 0);
        typing = null;
        typingAddress = false;
    }

    private static void addressKey(int key, int mods) {
        var keyboard = Minecraft.getInstance().keyboardHandler;
        if (shortcut(mods)) {
            switch (key) {
                case GLFW.GLFW_KEY_A -> addressSelected = address.length() > 0;
                case GLFW.GLFW_KEY_C -> keyboard.setClipboard(address.toString());
                case GLFW.GLFW_KEY_X -> { keyboard.setClipboard(address.toString()); address.setLength(0); addressSelected = false; }
                case GLFW.GLFW_KEY_V -> {
                    if (addressSelected) address.setLength(0);
                    addressSelected = false;
                    address.append(keyboard.getClipboard().replaceAll("\\s+", " ").strip());
                }
                default -> {}
            }
            return;
        }
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> stopTyping(false);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                var view = typing;
                String target = target(address.toString());
                stopTyping(false);
                if (target.isEmpty()) return;
                // Entering an address yourself is consent to load pages here.
                Trust.allow(Minecraft.getInstance().getSingleplayerServer());
                view.navigate(target);
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (addressSelected) address.setLength(0);
                else if (address.length() > 0) address.deleteCharAt(address.length() - 1);
                addressSelected = false;
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_HOME, GLFW.GLFW_KEY_END -> addressSelected = false;
            default -> {}
        }
    }

    /** An address as typed: a URL, a bare host, or else a search. */
    static String target(String typed) {
        String text = typed.strip();
        if (text.isEmpty()) return "";
        boolean port = text.matches("^[^/\\s]+:\\d+([/?#].*)?$");
        if (!port && text.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) return text;
        if (!text.contains(" ") && (port || text.contains(".") || text.startsWith("localhost"))) {
            boolean local = text.startsWith("localhost") || text.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+.*");
            return (local ? "http://" : "https://") + text;
        }
        return "https://duckduckgo.com/?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    // Cursor and hint.

    private static void crosshair(RenderGuiLayerEvent.Pre event) {
        if (!event.getName().equals(VanillaGuiLayers.CROSSHAIR)) return;
        var view = pressed != null ? pressed : aim != null ? aim.view : null;
        if (view == null || !pointing()) return;
        event.setCanceled(true);
        int shape = view.cursor;
        if (aim != null && pressed == null) {
            int part = aim.view.barPart(aim.x, aim.y);
            if (part == 3 || (part < 0 && view.url.isBlank())) shape = Cursor.TEXT_CURSOR;
            else if (part >= 0 || view.browser == null || view.crashed || !Trust.allowedHere()) shape = Cursor.HAND_CURSOR;
        }
        var graphics = event.getGuiGraphics();
        CursorArt.draw(graphics, shape, graphics.guiWidth() / 2, graphics.guiHeight() / 2);
    }

    private static void hint(GuiGraphics graphics) {
        if (typing == null || Minecraft.getInstance().screen != null) return;
        var font = Minecraft.getInstance().font;
        String text = typingAddress ? "Enter an address · Esc to cancel" : "Typing on screen · Esc to stop";
        float scale = Minecraft.getInstance().getWindow().getGuiScale() >= 3 ? 0.5f : 1f;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(graphics.guiWidth() / 2f, graphics.guiHeight() - 52, 0);
        pose.scale(scale, scale, 1);
        int x = -font.width(text) / 2;
        graphics.fill(x - 5, -4, x + font.width(text) + 5, 12, 0xB0101114);
        graphics.drawString(font, text, x, 0, 0xFFE6E8EB, false);
        pose.popPose();
    }

    // AWT event details.

    private static boolean shortcut(int mods) { return (mods & (Minecraft.ON_OSX ? GLFW.GLFW_MOD_SUPER : GLFW.GLFW_MOD_CONTROL)) != 0; }

    private static int awtButton(int button) {
        return switch (button) {
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> MouseEvent.BUTTON3;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> MouseEvent.BUTTON2;
            default -> MouseEvent.BUTTON1;
        };
    }

    private static int buttonMask() {
        int mask = 0;
        if (pressedButtons.contains(GLFW.GLFW_MOUSE_BUTTON_LEFT)) mask |= InputEvent.BUTTON1_DOWN_MASK;
        if (pressedButtons.contains(GLFW.GLFW_MOUSE_BUTTON_MIDDLE)) mask |= InputEvent.BUTTON2_DOWN_MASK;
        if (pressedButtons.contains(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) mask |= InputEvent.BUTTON3_DOWN_MASK;
        return mask;
    }

    private static int modifiers() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        int mods = 0;
        if (down(window, GLFW.GLFW_KEY_LEFT_SHIFT) || down(window, GLFW.GLFW_KEY_RIGHT_SHIFT)) mods |= GLFW.GLFW_MOD_SHIFT;
        if (down(window, GLFW.GLFW_KEY_LEFT_CONTROL) || down(window, GLFW.GLFW_KEY_RIGHT_CONTROL)) mods |= GLFW.GLFW_MOD_CONTROL;
        if (down(window, GLFW.GLFW_KEY_LEFT_ALT) || down(window, GLFW.GLFW_KEY_RIGHT_ALT)) mods |= GLFW.GLFW_MOD_ALT;
        if (down(window, GLFW.GLFW_KEY_LEFT_SUPER) || down(window, GLFW.GLFW_KEY_RIGHT_SUPER)) mods |= GLFW.GLFW_MOD_SUPER;
        return awtModifiers(mods);
    }

    private static boolean down(long window, int key) { return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS; }

    private static int awtModifiers(int mods) {
        int awt = 0;
        if ((mods & GLFW.GLFW_MOD_SHIFT) != 0) awt |= InputEvent.SHIFT_DOWN_MASK;
        if ((mods & GLFW.GLFW_MOD_CONTROL) != 0) awt |= InputEvent.CTRL_DOWN_MASK;
        if ((mods & GLFW.GLFW_MOD_ALT) != 0) awt |= InputEvent.ALT_DOWN_MASK;
        if ((mods & GLFW.GLFW_MOD_SUPER) != 0) awt |= InputEvent.META_DOWN_MASK;
        return awt;
    }

    /** Status for development checks. */
    static String state() {
        if (typing == null) return aim == null ? "none" : "pointing";
        return typingAddress ? "address" : "typing";
    }
}
