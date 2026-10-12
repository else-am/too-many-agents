package org.cef.browser;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

/**
 * An off-screen browser with no AWT window. jcef's own CefBrowserOsr draws through a JOGL canvas, which Minecraft
 * cannot host, so frames go straight to a {@link View} instead. It lives in jcef's package to extend its
 * package-private base class.
 */
public class ScreenBrowser extends CefBrowser_N implements CefRenderHandler {
    /** Receives Chromium's frames and state. Frames are BGRA and only valid during the call. */
    public interface View {
        void paint(ScreenBrowser source, boolean popup, Rectangle[] dirty, ByteBuffer frame, int width, int height);
        void popup(ScreenBrowser source, boolean shown, Rectangle bounds);
        void cursor(ScreenBrowser source, int type);
    }

    // Synthetic AWT events need a source, and jcef's focus handling expects a UI component. Never shown or attached.
    private final Component component = new Component() {};
    private final View view;
    private Rectangle rect;
    private Rectangle popupBounds = new Rectangle();
    /** Re-entry is local to the thread making the native focus call. */
    private final ThreadLocal<Boolean> focusing = new ThreadLocal<>();

    public ScreenBrowser(CefClient client, String url, int width, int height, int fps, View view) {
        super(client, url, null, null, null, settings(fps));
        this.view = view;
        this.rect = new Rectangle(0, 0, width, height);
    }

    private static CefBrowserSettings settings(int fps) {
        var settings = new CefBrowserSettings();
        settings.windowless_frame_rate = fps;
        return settings;
    }

    /**
     * jcef's onGotFocus calls setFocus(true), and windowless Chromium answers that by reporting focus again, on the
     * same stack, until the thread's stack runs out. A focus change made while this thread is already making one is
     * that echo, so it is dropped.
     */
    @Override public void setFocus(boolean enable) {
        if (Boolean.TRUE.equals(focusing.get())) return;
        focusing.set(true);
        try {
            super.setFocus(enable);
        } finally {
            focusing.remove();
        }
    }

    @Override public void createImmediately() {
        if (getNativeRef("CefBrowser") == 0) createBrowser(getClient(), 0, getUrl(), true, false, null, getRequestContext());
    }

    public void resize(int width, int height) {
        rect = new Rectangle(0, 0, width, height);
        wasResized(width, height);
    }

    /** Repaints the whole view, e.g. after frames were ignored while the screen was frozen. */
    public void repaint() { invalidate(); }

    public void mouse(int id, int x, int y, int button, int modifiers, int clicks) {
        sendMouseEvent(new MouseEvent(component, id, System.currentTimeMillis(), modifiers, x, y, clicks, false, button));
    }

    public void wheel(int x, int y, int pixels, int modifiers) {
        sendMouseWheelEvent(new MouseWheelEvent(component, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), modifiers, x, y, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, pixels));
    }

    /** id is KEY_PRESSED, KEY_RELEASED or KEY_TYPED; modifiers are AWT's extended (*_DOWN_MASK) flags. */
    public void key(int id, int keyCode, char keyChar, int modifiers) {
        // AWT forbids a key code on typed events, but Chromium needs one to type Enter; jcef reads it through getKeyCode().
        int code = id == KeyEvent.KEY_TYPED ? KeyEvent.VK_UNDEFINED : keyCode;
        sendKeyEvent(new KeyEvent(component, id, System.currentTimeMillis(), modifiers, code, keyChar) {
            @Override public int getKeyCode() { return keyCode; }
        });
    }

    @Override public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirty, ByteBuffer frame, int width, int height) {
        view.paint(this, popup, dirty, frame, width, height);
    }
    @Override public void onPopupShow(CefBrowser browser, boolean show) { view.popup(this, show, popupBounds); }
    @Override public void onPopupSize(CefBrowser browser, Rectangle size) {
        popupBounds = new Rectangle(size);
        view.popup(this, true, popupBounds);
    }
    @Override public boolean onCursorChange(CefBrowser browser, int cursor) {
        view.cursor(this, cursor);
        return true;
    }

    @Override public Rectangle getViewRect(CefBrowser browser) { return rect; }
    @Override public boolean getScreenInfo(CefBrowser browser, CefScreenInfo info) {
        info.Set(1, 32, 8, false, rect, rect);
        return true;
    }
    @Override public Point getScreenPoint(CefBrowser browser, Point view) { return new Point(view); }
    @Override public Component getUIComponent() { return component; }
    @Override public CefRenderHandler getRenderHandler() { return this; }
    @Override protected CefBrowser_N createDevToolsBrowser(CefClient client, String url, CefRequestContext context, CefBrowser_N parent, Point at) { return null; }
    @Override public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) { return CompletableFuture.failedFuture(new UnsupportedOperationException()); }
    @Override public void addOnPaintListener(Consumer<CefPaintEvent> listener) {}
    @Override public void setOnPaintListener(Consumer<CefPaintEvent> listener) {}
    @Override public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {}
    @Override public boolean startDragging(CefBrowser browser, CefDragData data, int mask, int x, int y) { return false; }
    @Override public void updateDragCursor(CefBrowser browser, int operation) {}
}
