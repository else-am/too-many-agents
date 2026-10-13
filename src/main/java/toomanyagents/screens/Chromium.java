package toomanyagents.screens;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.EnumProgress;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import net.minecraft.client.Minecraft;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.browser.ScreenBrowser;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefFileDialogCallback;
import org.cef.callback.CefMenuModel;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefContextMenuHandlerAdapter;
import org.cef.handler.CefKeyboardHandler;
import org.cef.handler.CefKeyboardHandlerAdapter;
import org.cef.handler.CefDialogHandler;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * The one embedded Chromium shared by every screen. It is installed and started on first use, off the render thread:
 * on macOS its startup waits on the main thread, which the render thread keeps pumping.
 */
final class Chromium {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Path HOME = Path.of(System.getProperty("user.home"), ".too-many-agents");
    static final int FPS = 60;

    enum Phase { OFF, STARTING, READY, FAILED, STOPPED }
    private static volatile Phase phase = Phase.OFF;
    private static volatile String progress = "Starting browser";
    private static volatile CefApp app;
    private static volatile CefClient client;
    private static final Map<CefBrowser, ScreenView> views = new ConcurrentHashMap<>();

    // Runs in every frame once it loads. It reports whether the focused element takes text, so clicking into a field
    // starts typing mode; focus inside an iframe never reaches the top document. New windows open in the same screen:
    // jcef cancels popups from windowless browsers before we could redirect them.
    private static final String PAGE_SCRIPT = """
        (() => {
          if (window.__screen || !window.cefQuery) return;
          window.__screen = true;
          document.addEventListener('click', e => {
            const link = e.target.closest && e.target.closest('a[target]');
            if (!link || /^_(self|parent|top)$/i.test(link.target)) return;
            let named = false;
            try { named = link.target !== '_blank' && !!top.frames[link.target]; } catch {}
            if (!named) link.target = '_top';
          }, true);
          window.open = url => { if (url) top.location.href = new URL(url, location.href).href; return top; };
          const takesText = e => !!e && (e.isContentEditable || e.tagName === 'TEXTAREA' || (e.tagName === 'INPUT' &&
            !/^(button|checkbox|radio|submit|reset|file|image|color|range|hidden)$/i.test(e.type)));
          const report = () => {
            let e = document.activeElement;
            while (e && e.shadowRoot && e.shadowRoot.activeElement) e = e.shadowRoot.activeElement;
            window.cefQuery({request: 'screen-editing:' + (document.hasFocus() && takesText(e) ? 1 : 0)});
          };
          document.addEventListener('focusin', report, true);
          document.addEventListener('focusout', () => setTimeout(report, 0), true);
        })();
        """;

    static Phase phase() { return phase; }
    static int browsers() { return views.size(); }
    static String progress() { return progress; }

    static void start() {
        if (phase != Phase.OFF) return;
        phase = Phase.STARTING;
        var thread = new Thread(Chromium::startNow, "too-many-agents-chromium");
        thread.setDaemon(true);
        thread.start();
    }

    private static void startNow() {
        try {
            var builder = new CefAppBuilder();
            builder.setInstallDir(HOME.resolve("cef").toFile());
            builder.setProgressHandler((state, percent) -> progress = switch (state) {
                case DOWNLOADING -> percent >= 0 ? "Installing browser " + Math.round(percent) + "%" : "Installing browser";
                case EXTRACTING, INSTALL -> "Installing browser";
                default -> "Starting browser";
            });
            builder.addJcefArgs("--mute-audio");
            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                // macOS: Cmd+Q reaches Chromium first. Quit through Minecraft so the world saves.
                @Override public boolean onBeforeTerminate() {
                    Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
                    return true;
                }
            });
            CefSettings settings = builder.getCefSettings();
            settings.windowless_rendering_enabled = true;
            settings.root_cache_path = HOME.resolve("browser-profile").toString();
            settings.cache_path = settings.root_cache_path;
            settings.log_file = HOME.resolve("browser.log").toString();
            settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
            long started = System.nanoTime();
            var app = builder.build();
            var client = app.createClient();
            handle(client);
            Chromium.app = app;
            Chromium.client = client;
            phase = Phase.READY;
            LOG.info("Chromium {} ready in {} ms", app.getVersion(), (System.nanoTime() - started) / 1_000_000);
        } catch (Throwable e) {
            progress = "Browser failed to start";
            phase = Phase.FAILED;
            LOG.error("Could not start Chromium for screens", e);
        }
    }

    private static void handle(CefClient client) {
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override public void onLoadingStateChange(CefBrowser browser, boolean loading, boolean back, boolean forward) {
                var view = views.get(browser);
                if (view != null) view.loading((ScreenBrowser) browser, loading, back, forward);
            }
            @Override public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transition) {
                var view = views.get(browser);
                if (view != null && frame.isMain()) view.loadStarted((ScreenBrowser) browser, frame.getURL());
            }
            @Override public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode code, String text, String failedUrl) {
                // An aborted load is only the previous page giving way to the next.
                var view = views.get(browser);
                if (view != null && frame.isMain() && code != ErrorCode.ERR_ABORTED) view.loadStarted((ScreenBrowser) browser, failedUrl);
            }
            @Override public void onLoadEnd(CefBrowser browser, CefFrame frame, int status) {
                frame.executeJavaScript(PAGE_SCRIPT, "", 0);
            }
        });
        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
                var view = views.get(browser);
                if (view != null && frame.isMain()) view.address((ScreenBrowser) browser, url);
            }
            @Override public void onTitleChange(CefBrowser browser, String title) {
                var view = views.get(browser);
                if (view != null) view.title((ScreenBrowser) browser, title);
            }
        });
        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override public void onRenderProcessTerminated(CefBrowser browser, CefRequestHandler.TerminationStatus status, int code, String message) {
                var view = views.get(browser);
                if (view != null) view.crashed((ScreenBrowser) browser);
            }
            @Override public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request, boolean userGesture, boolean redirect) {
                var view = views.get(browser);
                if (view != null && frame.isMain() && userGesture && !redirect) view.gesture((ScreenBrowser) browser);
                return false;
            }
        });
        client.addKeyboardHandler(new CefKeyboardHandlerAdapter() {
            // Keys the page leaves unhandled would otherwise go back to the game window as native events, where
            // macOS can treat them as menu shortcuts (e.g. entering full screen). The page has had its turn.
            @Override public boolean onKeyEvent(CefBrowser browser, CefKeyboardHandler.CefKeyEvent event) { return true; }
        });
        client.addContextMenuHandler(new CefContextMenuHandlerAdapter() {
            // Chromium's own menu would open as a native window; pages' own menus still work.
            @Override public void onBeforeContextMenu(CefBrowser browser, CefFrame frame, CefContextMenuParams params, CefMenuModel model) { model.clear(); }
        });
        client.addDialogHandler(new CefDialogHandler() {
            // A native file picker would open outside the game.
            @Override public boolean onFileDialog(CefBrowser browser, FileDialogMode mode, String title, String path,
                                                  java.util.Vector<String> filters, java.util.Vector<String> extensions,
                                                  java.util.Vector<String> descriptions, CefFileDialogCallback callback) {
                callback.Cancel();
                return true;
            }
        });
        var router = CefMessageRouter.create();
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override public boolean onQuery(CefBrowser browser, CefFrame frame, long id, String request, boolean persistent, CefQueryCallback callback) {
                if (!request.startsWith("screen-editing:")) return false;
                var view = views.get(browser);
                if (view != null) view.editing((ScreenBrowser) browser, request.endsWith("1"));
                callback.success("");
                return true;
            }
        }, true);
        client.addMessageRouter(router);
    }

    static ScreenBrowser open(ScreenView view, String url, int width, int height) {
        var browser = new ScreenBrowser(client, url, width, height, FPS, view);
        views.put(browser, view);
        browser.setCloseAllowed();
        browser.createImmediately();
        return browser;
    }

    static void close(ScreenBrowser browser) {
        views.remove(browser);
        browser.close(true);
    }

    /** Called on the render thread as the game closes, after the world has saved. */
    static void shutdown() {
        if (phase == Phase.OFF || phase == Phase.STOPPED) return;
        long deadline = System.nanoTime() + 10_000_000_000L;
        // Startup may still be waiting on this thread.
        while (phase == Phase.STARTING && System.nanoTime() < deadline) pump();
        phase = Phase.STOPPED;
        if (app == null) return;
        views.keySet().forEach(browser -> browser.close(true));
        views.clear();
        client.dispose();
        app.dispose();
        // Chromium's shutdown finishes on the main thread, so keep its run loop turning until it does.
        while (CefApp.getState() != CefApp.CefAppState.TERMINATED && System.nanoTime() < deadline) pump();
        LOG.info("Chromium stopped: {}", CefApp.getState());
    }

    private static void pump() {
        GLFW.glfwPollEvents();
        try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
