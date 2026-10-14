package toomanyagents.screens;

import java.awt.Cursor;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import com.mojang.blaze3d.platform.InputConstants;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * With an empty hand, the crosshair is the mouse of the screen it points at: hover, click, drag and scroll go to the
 * page. Clicking into a text field starts typing mode, where keys go to the page until Esc or a click elsewhere.
 * The bar's own fields, the screen's name and address, are edited the same way. The bar's ⋯ opens a menu over the
 * page, which takes every click until it closes. Render thread only.
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

    private static final KeyMapping captureKey = new KeyMapping("Capture screen keyboard and mouse", KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "Too Many Agents");
    private static ScreenView captured;
    private static int captureTrigger = -1;
    private static double cursorX, cursorY;
    private static String lockedFrame;
    private static final Set<Integer> capturedKeys = new HashSet<>();
    private record HeldKey(int code, char character) {}
    private static final Map<Integer, HeldKey> heldKeys = new HashMap<>();
    private static final Set<Integer> consumedKeys = new HashSet<>();

    private static ScreenView typing;
    /** The bar field being edited (name or address), or null when keys go to the page. */
    private static ScreenView.Part field;
    private static boolean fieldSelected;
    private static final StringBuilder fieldText = new StringBuilder();
    static final int MAX_NAME = 32;

    /** The screen whose menu is open, the hotbar slot it opened with, and until when a reset waits for its second click. */
    private static ScreenView menu;
    private static int menuSlot;
    private static long confirmUntil;
    private static final long CONFIRM_NANOS = 3_000_000_000L;

    private ScreenInput() {}

    static void register(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(captureKey));
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> frame(event.getPartialTick().getGameTimeDeltaPartialTick(true)));
        NeoForge.EVENT_BUS.addListener(ScreenInput::mouseButton);
        NeoForge.EVENT_BUS.addListener(ScreenInput::scroll);
        NeoForge.EVENT_BUS.addListener(ScreenInput::crosshair);
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath("too_many_agents", "screen_typing"), (graphics, tick) -> hint(graphics)));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            if (captured != null && !captureValid()) stopCapture();
            if (typing != null && Minecraft.getInstance().screen != null) stopTyping(false);
            if (menu != null && !menuStillOpen()) closeMenu();
        });
    }

    // What the views and renderer ask.

    /** The text being edited in a bar field of this view, or null. */
    static String fieldText(ScreenView view, ScreenView.Part part) { return typing == view && field == part ? fieldText.toString() : null; }
    static boolean fieldSelected() { return fieldSelected; }
    static boolean typing(ScreenView view) { return typing == view && Minecraft.getInstance().screen == null; }
    static boolean pointing(ScreenView view) { return aim != null && aim.view == view && pointing(); }

    /** A page is closing under the player: stop typing into it. */
    static void closing(ScreenView view) {
        if (captured == view) stopCapture();
        if (typing == view) stopTyping(false);
    }

    static void forget(ScreenView view) {
        closing(view);
        if (menu == view) closeMenu();
        if (typing == view) typing = null;
        if (hovered == view) hovered = null;
        if (pressed == view) { pressed = null; pressedButtons.clear(); }
        if (clicked == view) clicked = null;
        if (aim != null && aim.view == view) aim = null;
    }

    static boolean menuOpen(ScreenView view) { return menu == view; }
    static boolean confirmingReset() { return menu != null && System.nanoTime() < confirmUntil; }

    /** The menu row the crosshair is on, or null. */
    static ScreenView.Action menuHovered(ScreenView view) {
        return menu == view && aim != null && aim.view == view ? view.menuAction(aim.x, aim.y) : null;
    }

    /** The page reports whether its focused element takes text. Only a click of yours starts typing. */
    static void editingChanged(ScreenView view, boolean editable) {
        if (captured != null) return;
        if (editable && menu == null && clicked == view && System.nanoTime() - lastClickAt < FOCUS_AFTER_CLICK_NANOS && (typing == null || typing == view)) {
            if (typing == null) KeyMapping.releaseAll();
            typing = view;
            field = null;
        } else if (!editable && typing == view && field == null) {
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
        if (captured != null) {
            if (captureValid()) { updateCapturedAim(); if (lockedFrame == null) hover(); return; }
            stopCapture();
        }
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
        hover();
    }

    private static void hover() {
        // Native controls own their hover; page drags still get movement and a release at the edge.
        if (pressed != null && aim != null && pressed == aim.view) {
            move(pressed, aim.x, aim.y);
            return;
        }
        boolean onPage = menu == null && aim != null && aim.view.onPage(aim.x, aim.y);
        if (hovered != null && (!onPage || aim.view != hovered)) {
            if (hovered.browser != null) hovered.browser.mouse(MouseEvent.MOUSE_EXITED, lastX, lastY, MouseEvent.NOBUTTON, 0, 0);
            hovered = null;
            lastX = lastY = -1;
        }
        if (onPage) {
            hovered = aim.view;
            move(aim.view, aim.x, aim.y);
        }
    }

    private static Aim find(Vec3 eye, Vec3 look) {
        Aim best = null;
        double bestDistance = REACH;
        for (var view : Screens.views()) {
            if (view.screen == null || view.screen.isRemoved() || view.screen.level() != Minecraft.getInstance().level) continue;
            double[] at = view.hit(eye, look);
            if (at == null || at[2] > bestDistance) continue;
            if (!view.onPage(at[0], at[1]) && view.barPart(at[0], at[1]) == null) continue;
            best = new Aim(view, at[0], at[1]);
            bestDistance = at[2];
        }
        if (best == null) return null;
        // A block or a nearer entity in front of the screen blocks it.
        var mc = Minecraft.getInstance();
        Vec3 surface = eye.add(look.scale(bestDistance - 0.01));
        if (mc.level.clip(new ClipContext(eye, surface, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)).getType() != HitResult.Type.MISS) return null;
        var screen = best.view.screen;
        var nearer = ProjectileUtil.getEntityHitResult(mc.player, eye, surface, new AABB(eye, surface).inflate(1),
            entity -> entity != screen && !entity.isSpectator() && entity.isPickable(), bestDistance * bestDistance);
        return nearer == null ? best : null;
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
        // Captured clicks bypass vanilla's swing along with its attack/use handling.
        if (captured == null && (menu != null || aim != null)) {
            var player = Minecraft.getInstance().player;
            if (player != null) player.swing(InteractionHand.MAIN_HAND);
        }
        if (menu != null) {
            // An open menu takes the click: a row acts, anything else closes it.
            event.setCanceled(true);
            consumedButtons.add(button);
            var action = menuHovered(menu);
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action != null) choose(menu, action);
            else closeMenu();
            return;
        }
        if (field != null && (aim == null || aim.view != typing || aim.view.barPart(aim.x, aim.y) != field)) stopTyping(false);
        if (aim == null) return;
        event.setCanceled(true);
        var view = aim.view;
        var part = view.barPart(aim.x, aim.y);
        if (part != null || view.url.isBlank() || !Trust.allowedHere() || view.crashed || view.browser == null) {
            // Our own controls; the page never sees these clicks.
            consumedButtons.add(button);
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            if (part == ScreenView.Part.NAME) startField(view, ScreenView.Part.NAME, view.screen.record().name());
            else if (part == ScreenView.Part.BACK) view.back();
            else if (part == ScreenView.Part.FORWARD) view.forward();
            else if (part == ScreenView.Part.RELOAD) view.reload();
            else if (part == ScreenView.Part.MENU) openMenu(view);
            else if (part == ScreenView.Part.ADDRESS || view.url.isBlank()) startField(view, ScreenView.Part.ADDRESS, view.url);
            else if (!Trust.allowedHere()) Trust.allow(Minecraft.getInstance().getSingleplayerServer());
            else if (view.crashed) view.reload();
            return;
        }
        view.playerInput();
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
        if (aim == null) return;
        if (captured != null) event.setCanceled(true);
        if (menu != null) {
            event.setCanceled(true);
            return;
        }
        if (aim.view.browser == null || aim.view.barPart(aim.x, aim.y) != null) return;
        event.setCanceled(true);
        double delta = event.getScrollDeltaY() != 0 ? event.getScrollDeltaY() : event.getScrollDeltaX();
        int mods = modifiers() | (event.getScrollDeltaY() == 0 ? InputEvent.SHIFT_DOWN_MASK : 0);
        aim.view.browser.wheel(aim.view.pixelX(aim.x), aim.view.pixelY(aim.y), (int) Math.round(delta * 100), mods);
    }

    // Capture keeps Minecraft's mouse grabbed, but consumes its raw deltas before they turn the player.

    private static boolean captureValid() {
        var mc = Minecraft.getInstance();
        return pointing() && mc.isWindowActive() && mc.mouseHandler.isMouseGrabbed() && mc.level != null
            && captured.browser != null && !captured.crashed && Trust.allowedHere()
            && captured.screen != null && !captured.screen.isRemoved() && Screens.existing(captured.screen.getUUID()) == captured
            && captured.screen.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) <= REACH * REACH;
    }

    private static void startCapture() {
        var view = aim.view;
        Minecraft.getInstance().mouseHandler.grabMouse();
        cursorX = view.pixelX(aim.x);
        cursorY = view.pixelY(aim.y);
        stopTyping(false);
        KeyMapping.releaseAll();
        captured = typing = view;
        field = null;
        lockedFrame = null;
        for (var other : Screens.views()) if (other.browser != null) other.browser.setFocus(other == view);
        captureScript(view, "window.__screenCapture?.enable(true)");
        updateCapturedAim();
    }

    private static void stopCapture() {
        var view = captured;
        if (view == null) return;
        captureScript(view, "window.__screenCapture?.enable(false)");
        if (pressed == view) {
            for (int button : pressedButtons) {
                if (view.browser != null) view.browser.mouse(MouseEvent.MOUSE_RELEASED, lastX, lastY, awtButton(button), 0, clicks);
                consumedButtons.add(button);
            }
            pressedButtons.clear();
            pressed = null;
        }
        releaseCapturedKeys();
        captured = null;
        if (menu == view) closeMenu();
        stopTyping(false);
        if (view.browser != null) view.browser.setFocus(false);
        captured = null;
        lockedFrame = null;
        clicked = null;
        aim = null;
        KeyMapping.releaseAll();
    }

    private static void releaseCapturedKeys() {
        if (captured != null && captured.browser != null)
            for (int key : capturedKeys) ScreenKeys.send(captured, key, 0, GLFW.GLFW_RELEASE, 0);
        consumedKeys.addAll(capturedKeys);
        capturedKeys.clear();
    }

    /** A new page keeps the panel captured, but cannot inherit held keys or a pointer lock. */
    static void pageLoading(ScreenView view) {
        if (captured != view) { closing(view); return; }
        releaseCapturedKeys();
        lockedFrame = null;
        if (menu == view) closeMenu();
        stopTyping(false);
    }

    private static void captureScript(ScreenView view, String script) {
        if (view.browser == null) return;
        for (String id : view.browser.getFrameIdentifiers()) {
            var frame = view.browser.getFrameByIdentifier(id);
            if (frame != null) frame.executeJavaScript(script, "", 0);
        }
    }

    static void captureLoaded(ScreenView view, org.cef.browser.CefFrame frame) {
        if (captured == view) frame.executeJavaScript("window.__screenCapture?.enable(true)", "", 0);
    }

    static void pointerLock(ScreenView view, String frame, boolean locked) {
        if (captured != view) return;
        if (locked) lockedFrame = frame;
        else if (frame.equals(lockedFrame)) lockedFrame = null;
    }

    /** Called at the native mouse handler's turn step, before sensitivity or camera smoothing. */
    public static boolean mouseMove(double dx, double dy) {
        if (captured == null) return false;
        if (!captureValid()) { stopCapture(); return true; }
        if (dx == 0 && dy == 0) return true;
        captured.playerInput();
        if (lockedFrame != null) {
            var frame = captured.browser.getFrameByIdentifier(lockedFrame);
            if (frame != null) frame.executeJavaScript("window.__screenCapture?.move(" + dx + "," + dy + ")", "", 0);
        } else {
            cursorX = Math.clamp(cursorX + dx, 0, captured.pixelWidth - 1);
            double height = (captured.pageHeight + captured.barHeight) / captured.pageHeight * captured.pixelHeight;
            cursorY = Math.clamp(cursorY + dy, 0, height - 1);
            updateCapturedAim();
            hover();
        }
        return true;
    }

    private static void updateCapturedAim() {
        aim = new Aim(captured, (cursorX + 0.5) / captured.pixelWidth * captured.pageWidth,
            (cursorY + 0.5) / captured.pixelHeight * captured.pageHeight);
    }

    static boolean capturedCursor(ScreenView view) { return captured == view && lockedFrame == null; }
    static double cursorX() { return cursorX; }
    static double cursorY() { return cursorY; }

    // The menu.

    private static void openMenu(ScreenView view) {
        releaseCapturedKeys();
        if (typing != null) stopTyping(false);
        // A page click just before can't start typing once the menu is open.
        clicked = null;
        menu = view;
        menuSlot = Minecraft.getInstance().player.getInventory().selected;
        confirmUntil = 0;
    }

    private static void closeMenu() {
        menu = null;
        confirmUntil = 0;
    }

    /** Opening a GUI, holding an item, changing slot or walking away closes the menu. */
    private static boolean menuStillOpen() {
        var mc = Minecraft.getInstance();
        if (!pointing() || mc.level == null || mc.player.getInventory().selected != menuSlot) return false;
        if (menu.screen == null || menu.screen.isRemoved() || Screens.existing(menu.screen.getUUID()) != menu) return false;
        return menu.screen.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) <= REACH * REACH;
    }

    private static void choose(ScreenView view, ScreenView.Action action) {
        switch (action) {
            case OFF -> {
                closeMenu();
                view.powerOff();
            }
            case PICKUP -> {
                closeMenu();
                Screens.pickUp(view, false);
            }
            case RESET -> {
                // Reset forgets the screen's id, name and page, so it asks twice.
                if (System.nanoTime() < confirmUntil) {
                    closeMenu();
                    Screens.pickUp(view, true);
                } else confirmUntil = System.nanoTime() + CONFIRM_NANOS;
            }
        }
    }

    // Typing.

    /** Called from the keyboard handler before Minecraft sees a key. True when the key went to a screen. */
    public static boolean keyPress(int key, int scancode, int action, int mods) {
        if (captured != null && !captureValid()) stopCapture();
        if (consumedKeys.contains(key)) {
            if (action == GLFW.GLFW_RELEASE) {
                consumedKeys.remove(key);
                if (captureTrigger == key) captureTrigger = -1;
            }
            return true;
        }
        if (captured == null && captureKey.matches(key, scancode) && Minecraft.getInstance().screen == null
                && (field == null && menu == null && aim != null && aim.view.onPage(aim.x, aim.y)
                    && aim.view.browser != null && !aim.view.crashed && Trust.allowedHere())) {
            if (action == GLFW.GLFW_PRESS) {
                consumedKeys.add(key);
                captureTrigger = key;
                startCapture();
            }
            return true;
        }
        if (captured != null && key == GLFW.GLFW_KEY_ESCAPE) {
            if (action == GLFW.GLFW_PRESS) { consumedKeys.add(key); stopCapture(); }
            return true;
        }
        if (captured != null) {
            if (field != null || menu != null) {
                if (action != GLFW.GLFW_RELEASE) {
                    if (field != null) fieldKey(key, mods);
                    if (field == null) consumedKeys.add(key);
                }
                return true;
            }
            if (action == GLFW.GLFW_RELEASE) capturedKeys.remove(key); else capturedKeys.add(key);
            ScreenKeys.send(captured, key, scancode, action, mods);
            return true;
        }
        if (ScreenPlacement.keyPress(key, action)) return true;
        if (menu != null && key == GLFW.GLFW_KEY_ESCAPE && Minecraft.getInstance().screen == null) {
            if (action == GLFW.GLFW_PRESS) closeMenu();
            return true;
        }
        if (typing == null) return false;
        if (Minecraft.getInstance().screen != null) {
            stopTyping(false);
            return false;
        }
        if (field != null) {
            if (action != GLFW.GLFW_RELEASE) fieldKey(key, mods);
            return true;
        }
        var browser = typing.browser;
        if (browser == null) {
            stopTyping(false);
            return false;
        }
        typing.playerInput();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (action == GLFW.GLFW_PRESS) stopTyping(true);
            return true;
        }
        if (captured == null && shortcut(mods) && action != GLFW.GLFW_RELEASE) {
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
            case GLFW.GLFW_KEY_SPACE -> { code = KeyEvent.VK_SPACE; character = ' '; }
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> code = KeyEvent.VK_SHIFT;
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> code = KeyEvent.VK_CONTROL;
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> code = KeyEvent.VK_ALT;
            case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> code = KeyEvent.VK_META;
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
                if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) {
                    code = KeyEvent.VK_F1 + key - GLFW.GLFW_KEY_F1;
                    break;
                }
                String name = GLFW.glfwGetKeyName(key, scancode);
                if (name == null || name.length() != 1) return true;
                character = name.charAt(0);
                code = KeyEvent.getExtendedKeyCodeForChar(character);
            }
        }
        int awtMods = awtModifiers(mods);
        if (action == GLFW.GLFW_RELEASE) heldKeys.remove(key);
        else heldKeys.put(key, new HeldKey(code, character));
        browser.key(action == GLFW.GLFW_RELEASE ? KeyEvent.KEY_RELEASED : KeyEvent.KEY_PRESSED, code, character, awtMods);
        // GLFW sends no character for Enter, so the page would never see it as typed.
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (action != GLFW.GLFW_RELEASE) browser.key(KeyEvent.KEY_TYPED, code, character, awtMods);
        }
        return true;
    }

    /** Called from the keyboard handler for typed text. True when the text went to a screen. */
    public static boolean charTyped(int codePoint, int mods) {
        if (captureTrigger != -1) return true;
        if (typing == null || Minecraft.getInstance().screen != null) return false;
        if (menu != null) return true;
        if (captured != null && field == null) {
            ScreenKeys.text(captured, new String(Character.toChars(codePoint)));
            return true;
        }
        if (field != null) {
            if (fieldSelected) fieldText.setLength(0);
            fieldSelected = false;
            if (field != ScreenView.Part.NAME || fieldText.length() < MAX_NAME) fieldText.appendCodePoint(codePoint);
            return true;
        }
        if (typing.browser == null) return false;
        typing.playerInput();
        for (char c : Character.toChars(codePoint)) typing.browser.key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c, awtModifiers(mods));
        return true;
    }

    private static void startField(ScreenView view, ScreenView.Part part, String text) {
        releaseCapturedKeys();
        if (typing == null) KeyMapping.releaseAll();
        typing = view;
        field = part;
        fieldText.setLength(0);
        fieldText.append(text);
        fieldSelected = !text.isBlank();
    }

    private static void stopTyping(boolean blurPage) {
        if (typing != null && typing.browser != null) {
            for (var key : heldKeys.values()) typing.browser.key(KeyEvent.KEY_RELEASED, key.code, key.character, 0);
        }
        consumedKeys.addAll(heldKeys.keySet());
        heldKeys.clear();
        if (blurPage && typing != null && typing.browser != null)
            typing.browser.executeJavaScript("document.activeElement && document.activeElement.blur()", "", 0);
        typing = captured;
        field = null;
    }

    private static void fieldKey(int key, int mods) {
        var keyboard = Minecraft.getInstance().keyboardHandler;
        if (shortcut(mods)) {
            switch (key) {
                case GLFW.GLFW_KEY_A -> fieldSelected = fieldText.length() > 0;
                case GLFW.GLFW_KEY_C -> keyboard.setClipboard(fieldText.toString());
                case GLFW.GLFW_KEY_X -> { keyboard.setClipboard(fieldText.toString()); fieldText.setLength(0); fieldSelected = false; }
                case GLFW.GLFW_KEY_V -> {
                    if (fieldSelected) fieldText.setLength(0);
                    fieldSelected = false;
                    fieldText.append(keyboard.getClipboard().replaceAll("\\s+", " ").strip());
                    if (field == ScreenView.Part.NAME && fieldText.length() > MAX_NAME) fieldText.setLength(MAX_NAME);
                }
                default -> {}
            }
            return;
        }
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> stopTyping(false);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                var view = typing;
                var part = field;
                String text = fieldText.toString();
                stopTyping(false);
                // The server checks that the name is nonblank and unique.
                if (part == ScreenView.Part.NAME) {
                    view.rename(text.strip());
                    return;
                }
                String target = target(text);
                if (target.isEmpty()) return;
                // Entering an address yourself is consent to load pages here.
                Trust.allow(Minecraft.getInstance().getSingleplayerServer());
                view.navigate(target);
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (fieldSelected) fieldText.setLength(0);
                else if (fieldText.length() > 0) fieldText.deleteCharAt(fieldText.length() - 1);
                fieldSelected = false;
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_HOME, GLFW.GLFW_KEY_END -> fieldSelected = false;
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
        if (captured != null) { event.setCanceled(true); return; }
        var view = pressed != null ? pressed : aim != null ? aim.view : null;
        if (view == null || !pointing()) return;
        event.setCanceled(true);
        var graphics = event.getGuiGraphics();
        CursorArt.draw(graphics, cursorShape(view), graphics.guiWidth() / 2, graphics.guiHeight() / 2);
    }

    static int cursorShape(ScreenView view) {
        if (aim == null || aim.view != view || pressed != null) return view.cursor;
        var part = view.barPart(aim.x, aim.y);
        if (menu != null) return menuHovered(view) != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR;
        if (part == ScreenView.Part.ADDRESS || part == ScreenView.Part.NAME || (part == null && view.url.isBlank())) return Cursor.TEXT_CURSOR;
        if (part != null || view.browser == null || view.crashed || !Trust.allowedHere()) return Cursor.HAND_CURSOR;
        return view.cursor;
    }

    private static void hint(GuiGraphics graphics) {
        if (Minecraft.getInstance().screen != null) return;
        if (typing == null && (aim == null || aim.view.browser == null || !aim.view.onPage(aim.x, aim.y))) return;
        var font = Minecraft.getInstance().font;
        String text = captured != null ? "Screen captured - Esc to release"
            : typing != null ? "Typing on screen, press Esc to release"
            : captureKey.getTranslatedKeyMessage().getString() + " - Capture keyboard and mouse";
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(graphics.guiWidth() / 2f, graphics.guiHeight() - 52, 0);
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
        if (captured != null) return lockedFrame == null ? "captured" : "pointer_locked";
        if (menu != null) return "menu";
        if (typing == null) return aim == null ? "none" : "pointing";
        return field == ScreenView.Part.NAME ? "name" : field == ScreenView.Part.ADDRESS ? "address" : "typing";
    }
}
