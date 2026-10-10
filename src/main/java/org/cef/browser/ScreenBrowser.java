package org.cef.browser;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
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
 * Spike: an off-screen browser with no AWT window. jcef's own CefBrowserOsr draws through a JOGL canvas,
 * which Minecraft cannot host, so frames go straight to a paint callback instead.
 */
public class ScreenBrowser extends CefBrowser_N implements CefRenderHandler {
    /** Receives Chromium's BGRA frame and the regions that changed. Called on the platform's main thread. */
    public interface Painter { void paint(Rectangle[] dirty, ByteBuffer frame, int width, int height); }

    // Synthetic AWT events need a source component; it is never shown.
    private static final Component SOURCE = new Component() {};
    private final Painter painter;
    private Rectangle rect;

    public ScreenBrowser(CefClient client, String url, int width, int height, int fps, Painter painter) {
        super(client, url, null, null, null, settings(fps));
        this.painter = painter;
        this.rect = new Rectangle(0, 0, width, height);
    }

    private static CefBrowserSettings settings(int fps) {
        var settings = new CefBrowserSettings();
        settings.windowless_frame_rate = fps;
        return settings;
    }

    @Override public void createImmediately() {
        if (getNativeRef("CefBrowser") == 0) createBrowser(getClient(), 0, getUrl(), true, false, null, getRequestContext());
    }

    public void resize(int width, int height) {
        rect = new Rectangle(0, 0, width, height);
        wasResized(width, height);
    }

    public void mouse(int id, int x, int y, int button, int modifiers, int clicks) {
        sendMouseEvent(new MouseEvent(SOURCE, id, System.currentTimeMillis(), modifiers, x, y, clicks, false, button));
    }

    public void wheel(int x, int y, int amount) {
        sendMouseWheelEvent(new MouseWheelEvent(SOURCE, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, x, y, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, amount));
    }

    @Override public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirty, ByteBuffer frame, int width, int height) {
        if (!popup) painter.paint(dirty, frame, width, height);
    }

    @Override public Rectangle getViewRect(CefBrowser browser) { return rect; }
    @Override public boolean getScreenInfo(CefBrowser browser, CefScreenInfo info) {
        info.Set(1, 32, 8, false, rect, rect);
        return true;
    }
    @Override public Point getScreenPoint(CefBrowser browser, Point view) { return new Point(view); }
    @Override public Component getUIComponent() { return null; }
    @Override public CefRenderHandler getRenderHandler() { return this; }
    @Override protected CefBrowser_N createDevToolsBrowser(CefClient client, String url, CefRequestContext context, CefBrowser_N parent, Point at) { return null; }
    @Override public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) { return CompletableFuture.failedFuture(new UnsupportedOperationException()); }
    @Override public void onPopupShow(CefBrowser browser, boolean show) {}
    @Override public void onPopupSize(CefBrowser browser, Rectangle size) {}
    @Override public void addOnPaintListener(Consumer<CefPaintEvent> listener) {}
    @Override public void setOnPaintListener(Consumer<CefPaintEvent> listener) {}
    @Override public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {}
    @Override public boolean onCursorChange(CefBrowser browser, int cursor) { return true; }
    @Override public boolean startDragging(CefBrowser browser, CefDragData data, int mask, int x, int y) { return false; }
    @Override public void updateDragCursor(CefBrowser browser, int operation) {}
}
