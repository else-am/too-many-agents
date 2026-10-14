package org.cef.browser;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    private final String initialUrl;
    /**
     * Shows the mod's own tma: pages. Fixed for the life of this native browser: a page moving between built-in and
     * web content moves to a new browser, so neither inherits the other's state or instrumentation.
     */
    public final boolean builtin;
    private volatile boolean navigationRequested, ready;
    /** A built-in browser's blank page. about:blank can't go to tma: pages, which only tma: pages may reach. */
    public static final String BUILTIN_START = "tma://files/_app/start.html";
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private Rectangle rect;
    private Rectangle popupBounds = new Rectangle();
    /** Re-entry is local to the thread making the native focus call. */
    private final ThreadLocal<Boolean> focusing = new ThreadLocal<>();
    /**
     * DevTools calls from sending until settled; those Chromium has given an id to, by id; and answers that came
     * before their id did, which Chromium can send first. All guarded by this.
     */
    private final Set<CompletableFuture<String>> pending = new HashSet<>();
    private final Map<Integer, CompletableFuture<String>> calls = new HashMap<>();
    private final Map<Integer, Answer> early = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Answer> eldest) { return size() > MAX_CALLS; }
    };
    private static final int MAX_CALLS = 256, CALL_SECONDS = 60;
    private record Answer(boolean success, String result) {}
    private final CefDevToolsMessageObserver answers = new CefDevToolsMessageObserver() {
        @Override public void onDevToolsMethodResult(CefBrowser browser, int id, boolean success, String result) {
            CompletableFuture<String> call;
            synchronized (ScreenBrowser.this) {
                if (closed) return;
                call = calls.remove(id);
                if (call == null) early.put(id, new Answer(success, result));
            }
            if (call != null) settle(call, new Answer(success, result));
        }
        @Override public void onDevToolsEvent(CefBrowser browser, String method, String params) {}
    };
    private CefRegistration observer;
    private CompletableFuture<Void> observing;
    private boolean closed;

    /**
     * A null context means the shared web profile. The browser starts on a blank page of its own kind, which the
     * real page replaces once page compatibility is installed.
     */
    public ScreenBrowser(CefClient client, CefRequestContext context, boolean builtin, String url, int width, int height, int fps, View view) {
        super(client, builtin ? BUILTIN_START : "about:blank", context, null, null, settings(fps));
        this.builtin = builtin;
        this.initialUrl = url;
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

    /**
     * Install page compatibility before scripts can cache native functions, then begin the actual navigation. The page
     * replaces the blank one where it can; elsewhere the blank page stays first in the history, see {@link #firstEntry}.
     */
    public CompletableFuture<String> initialize(String script) {
        var params = new com.google.gson.JsonObject();
        params.addProperty("source", script);
        return devTools("Page.enable", "{}")
            .thenCompose(ignored -> devTools("Page.addScriptToEvaluateOnNewDocument", params.toString()))
            .thenCombine(started, (result, ignored) -> {
                navigationRequested = true;
                if (replacesStart()) getMainFrame().executeJavaScript("location.replace(" + new com.google.gson.JsonPrimitive(initialUrl) + ")", "", 0);
                else loadURL(initialUrl);
                return result;
            });
    }

    private boolean replacesStart() { return initialUrl.matches("(?i)(https?|tma|about):.*"); }

    /** The index of the first real page in this browser's history; earlier entries are the blank start page. */
    public int firstEntry() { return replacesStart() ? 0 : 1; }

    /**
     * Runs a DevTools method. jcef's own CefDevToolsClient loses answers that arrive before Chromium hands back the
     * call's id, which browser-side methods such as Page.getNavigationHistory always do, so calls are matched here.
     * A call that never answers gives up after a while; it is never retried, as it may have run.
     */
    public CompletableFuture<String> devTools(String method, String params) {
        var result = new CompletableFuture<String>();
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new CefDevToolsClient.DevToolsException("The browser closed."));
            if (pending.size() >= MAX_CALLS) return CompletableFuture.failedFuture(new CefDevToolsClient.DevToolsException("Too many DevTools calls are waiting."));
            pending.add(result);
        }
        // Settled calls leave every table at once, so a late answer finds nothing to complete.
        result.orTimeout(CALL_SECONDS, TimeUnit.SECONDS).whenComplete((value, failure) -> {
            synchronized (this) {
                pending.remove(result);
                calls.values().remove(result);
            }
        });
        observe().whenComplete((ignored, observationFailure) -> {
            if (observationFailure != null) {
                result.completeExceptionally(observationFailure);
                return;
            }
            if (result.isDone()) return;
            try {
                executeDevToolsMethod(method, params).whenComplete((id, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    Answer answer;
                    synchronized (this) {
                        answer = early.remove(id);
                        if (result.isDone() || closed) return;
                        if (answer == null) calls.put(id, result);
                    }
                    if (answer != null) settle(result, answer);
                });
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** Registers for DevTools answers once. Native calls stay outside the lock the answers take. */
    private CompletableFuture<Void> observe() {
        CompletableFuture<Void> registration;
        synchronized (this) {
            if (observing != null) return observing;
            if (closed) return CompletableFuture.failedFuture(new CefDevToolsClient.DevToolsException("The browser closed."));
            registration = observing = new CompletableFuture<>();
        }
        try {
            var added = addDevToolsMessageObserver(answers);
            boolean kept;
            synchronized (this) {
                kept = !closed;
                if (kept) observer = added;
            }
            if (!kept) {
                added.dispose();
                registration.completeExceptionally(new CefDevToolsClient.DevToolsException("The browser closed."));
            } else registration.complete(null);
        } catch (RuntimeException failure) {
            registration.completeExceptionally(failure);
        }
        return registration;
    }

    private static void settle(CompletableFuture<String> call, Answer answer) {
        if (answer.success()) call.complete(answer.result());
        else call.completeExceptionally(new CefDevToolsClient.DevToolsException("DevTools method failed", answer.result()));
    }

    @Override public void close(boolean force) {
        List<CompletableFuture<String>> unanswered;
        CefRegistration registration;
        synchronized (this) {
            closed = true;
            unanswered = List.copyOf(pending);
            pending.clear();
            calls.clear();
            early.clear();
            registration = observer;
            observer = null;
        }
        if (registration != null) registration.dispose();
        var failure = new CefDevToolsClient.DevToolsException("The browser closed.");
        started.completeExceptionally(failure);
        for (var call : unanswered) call.completeExceptionally(failure);
        super.close(force);
    }

    /** The blank page has loaded, or failed to. */
    public void startPageDone() { started.complete(null); }

    /** Ignore the blank page's callbacks. Any main-frame load starting after the real navigation is requested is it. */
    public boolean pageStarted(CefFrame frame) {
        if (!ready && frame.isMain() && navigationRequested) ready = true;
        return ready;
    }

    public boolean ready() { return ready; }

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
