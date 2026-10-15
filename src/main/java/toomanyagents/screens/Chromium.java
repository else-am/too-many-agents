package toomanyagents.screens;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
import org.cef.browser.CefRequestContext;
import org.cef.browser.ScreenBrowser;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefFileDialogCallback;
import org.cef.callback.CefMenuModel;
import org.cef.callback.CefQueryCallback;
import org.cef.callback.CefSchemeRegistrar;
import org.cef.handler.CefContextMenuHandlerAdapter;
import org.cef.handler.CefKeyboardHandler;
import org.cef.handler.CefKeyboardHandlerAdapter;
import org.cef.handler.CefDialogHandler;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefRequestContextHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
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
    private static final Path PROFILE = System.getProperty("too_many_agents.devClient", "").isBlank()
        ? HOME.resolve("browser-profile")
        : net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("too-many-agents/browser-profile").toAbsolutePath();
    private static final boolean MANAGED = !System.getProperty("too_many_agents.devClient", "").isBlank();
    static final int FPS = 60;

    enum Phase { OFF, STARTING, READY, FAILED, STOPPED }
    private static volatile Phase phase = Phase.OFF;
    private static volatile String progress = "Starting browser";
    private static volatile CefApp app;
    private static volatile CefClient client;
    private static final Map<CefBrowser, ScreenView> views = new ConcurrentHashMap<>();
    private static final AtomicLong deniedNavigations = new AtomicLong(), deniedRequests = new AtomicLong();

    // Reports focused fields and trees, so clicking one routes keyboard input to the page.
    // Focus inside an iframe never reaches the top document. New windows open in the same screen:
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
          const takesKeys = e => !!e && (e.matches('[role="tree"], [role="treeitem"]') || e.isContentEditable || e.tagName === 'TEXTAREA' || (e.tagName === 'INPUT' &&
            !/^(button|checkbox|radio|submit|reset|file|image|color|range|hidden)$/i.test(e.type)));
          const report = () => {
            let e = document.activeElement;
            while (e && e.shadowRoot && e.shadowRoot.activeElement) e = e.shadowRoot.activeElement;
            window.cefQuery({request: 'screen-editing:' + (document.hasFocus() && takesKeys(e) ? 1 : 0)});
          };
          document.addEventListener('focusin', report, true);
          document.addEventListener('focusout', () => setTimeout(report, 0), true);
        })();
        """;

    private static final String CAPTURE_SCRIPT = captureScript();

    private static String captureScript() {
        try (var stream = Chromium.class.getResourceAsStream("/assets/too_many_agents/browser/capture.js")) {
            return new String(java.util.Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    static Phase phase() { return phase; }
    static int browsers() { return views.size(); }
    static String progress() { return progress; }
    static long deniedNavigations() { return deniedNavigations.get(); }
    static long deniedRequests() { return deniedRequests.get(); }

    /**
     * Where a URL may load: only in a web browser, only in a built-in one, in either (about:), or nowhere. Nowhere
     * covers file: and every scheme not listed, such as chrome: and devtools:.
     */
    enum Place { WEB, BUILTIN, EITHER, NOWHERE }

    static Place place(String url) {
        int colon = url == null ? -1 : url.indexOf(':');
        if (colon < 0) return Place.NOWHERE;
        String rest = url.substring(colon + 1);
        return switch (url.substring(0, colon).toLowerCase(java.util.Locale.ROOT)) {
            case "http", "https", "ws", "wss", "data" -> Place.WEB;
            case Builtin.SCHEME -> Place.BUILTIN;
            case "about" -> Place.EITHER;
            // These carry the origin they belong to.
            case "blob", "filesystem" -> place(rest);
            case "view-source" -> place(rest) == Place.WEB ? Place.WEB : Place.NOWHERE;
            default -> Place.NOWHERE;
        };
    }

    static boolean builtin(String url) { return place(url) == Place.BUILTIN; }

    /**
     * Chromium's PDF viewer, which a web page's PDF loads in a frame of its own. Allowed only there, never as the
     * page itself; agents' DevTools can't reach that frame's separate process without the refused Target domain.
     */
    private static boolean pdfViewer(String url, CefFrame frame) {
        return frame != null && !frame.isMain() && url.startsWith("chrome-extension://mhjfbmdgcfjbbpaeojofohoefgiehjai/");
    }

    static void start() {
        if (phase != Phase.OFF) return;
        phase = Phase.STARTING;
        var thread = new Thread(Chromium::startNow, "too-many-agents-chromium");
        thread.setDaemon(true);
        thread.start();
    }

    private static void startNow() {
        try {
            var contextReady = new java.util.concurrent.CompletableFuture<Void>();
            var builder = new CefAppBuilder();
            builder.setInstallDir(HOME.resolve(MANAGED ? "cef-152.0.6" : "cef").toFile());
            builder.setProgressHandler((state, percent) -> progress = switch (state) {
                case DOWNLOADING -> percent >= 0 ? "Installing browser " + Math.round(percent) + "%" : "Installing browser";
                case EXTRACTING, INSTALL -> "Installing browser";
                default -> "Starting browser";
            });
            builder.addJcefArgs("--mute-audio");
            // M152 soft-navigation metrics CHECK on stale NSApp timestamps from synthetic macOS input.
            // Disable only those metrics; retain native OSR input routing, including select popups.
            if (System.getProperty("os.name", "").startsWith("Mac"))
                builder.addJcefArgs("--disable-features=SoftNavigationDetection");
            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                // Standard, so pages have an origin; display-isolated, so only tma: pages can link, frame or embed tma:
                // URLs; not local, so tma: pages can't reach file: URLs. jcef passes this to every Chromium process.
                @Override public void onRegisterCustomSchemes(CefSchemeRegistrar registrar) {
                    registrar.addCustomScheme(Builtin.SCHEME, true, false, true, true, true, false, true);
                }
                @Override public void onContextInitialized() { contextReady.complete(null); }
                // macOS: Cmd+Q reaches Chromium first. Quit through Minecraft so the world saves.
                @Override public boolean onBeforeTerminate() {
                    Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
                    return true;
                }
            });
            CefSettings settings = builder.getCefSettings();
            settings.windowless_rendering_enabled = true;
            settings.root_cache_path = PROFILE.toString();
            settings.cache_path = settings.root_cache_path;
            settings.log_file = PROFILE.resolveSibling("browser.log").toString();
            settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
            if (MANAGED) {
                java.nio.file.Files.createDirectories(HOME);
                // Clients share the native binaries, but must not extract them concurrently.
                try (var file = java.nio.channels.FileChannel.open(HOME.resolve("cef-152.0.6.lock"),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                     var lock = file.lock()) {
                    builder.install();
                }
                builder.setSkipInstallation(true);
            }
            long started = System.nanoTime();
            var app = builder.build();
            var client = app.createClient();
            handle(client);
            Chromium.app = app;
            Chromium.client = client;
            // createClient returning is not the browser-process context-ready callback.
            contextReady.get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (phase != Phase.STARTING) return;
            phase = Phase.READY;
            LOG.info("Chromium {} ready in {} ms", app.getVersion(), (System.nanoTime() - started) / 1_000_000);
        } catch (Throwable e) {
            progress = "Browser failed to start";
            phase = Phase.FAILED;
            LOG.error("Could not start Chromium for screens", e);
        }
    }

    private static void handle(CefClient client) {
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override public void onAfterCreated(CefBrowser browser) {
                ((ScreenBrowser) browser).initialize(CAPTURE_SCRIPT).exceptionally(error -> {
                    LOG.error("Could not initialize screen input", error);
                    var view = views.get(browser);
                    if (view != null) view.crashed((ScreenBrowser) browser);
                    return null;
                });
            }
        });
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override public void onLoadingStateChange(CefBrowser browser, boolean loading, boolean back, boolean forward) {
                if (!((ScreenBrowser) browser).ready()) return;
                var view = views.get(browser);
                if (view != null) view.loading((ScreenBrowser) browser, loading);
            }
            @Override public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transition) {
                if (!((ScreenBrowser) browser).pageStarted(frame)) return;
                var view = views.get(browser);
                if (view != null && frame.isMain()) view.loadStarted((ScreenBrowser) browser, frame.getURL());
            }
            @Override public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode code, String text, String failedUrl) {
                if (!((ScreenBrowser) browser).ready() && frame.isMain()) ((ScreenBrowser) browser).startPageDone();
                // An aborted load is only the previous page giving way to the next.
                var view = views.get(browser);
                if (view != null && frame.isMain() && code != ErrorCode.ERR_ABORTED && place(failedUrl) != Place.NOWHERE) {
                    view.loadStarted((ScreenBrowser) browser, failedUrl);
                    view.loadFinished((ScreenBrowser) browser, failedUrl);
                }
            }
            @Override public void onLoadEnd(CefBrowser browser, CefFrame frame, int status) {
                if (!((ScreenBrowser) browser).ready()) {
                    if (frame.isMain()) ((ScreenBrowser) browser).startPageDone();
                    return;
                }
                frame.executeJavaScript(PAGE_SCRIPT + CAPTURE_SCRIPT, "", 0);
                var view = views.get(browser);
                if (view != null && frame.isMain()) view.loadFinished((ScreenBrowser) browser, frame.getURL());
            }
        });
        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
                if (!((ScreenBrowser) browser).ready()) return;
                var view = views.get(browser);
                if (view != null && frame.isMain()) view.address((ScreenBrowser) browser, url);
            }
            @Override public void onTitleChange(CefBrowser browser, String title) {
                if (!((ScreenBrowser) browser).ready()) return;
                var view = views.get(browser);
                if (view != null) view.title((ScreenBrowser) browser, title);
            }
        });
        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override public void onRenderProcessTerminated(CefBrowser browser, CefRequestHandler.TerminationStatus status, int code, String message) {
                var view = views.get(browser);
                if (view != null) {
                    LOG.warn("Screen renderer terminated: {} (code {}): {}", status, code, message);
                    view.crashed((ScreenBrowser) browser);
                }
            }
            @Override public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request, boolean userGesture, boolean redirect) {
                var view = views.get(browser);
                var screen = (ScreenBrowser) browser;
                var place = pdfViewer(request.getURL(), frame) ? Place.WEB : place(request.getURL());
                // A browser never changes between web and built-in content; nothing loads file: URLs. Only the
                // player's own click on a built-in page's link follows it out, into a new web browser.
                if (place != Place.EITHER && place != (screen.builtin ? Place.BUILTIN : Place.WEB)) {
                    deniedNavigations.incrementAndGet();
                    if (view != null && frame.isMain() && screen.builtin && place == Place.WEB && userGesture && !redirect)
                        view.follow(screen, request.getURL());
                    return true;
                }
                if (view != null && frame.isMain() && screen.builtin && screen.ready() && TerminalPages.location(request.getURL())) {
                    var access = view.access;
                    if (access == null || !TerminalPages.id(request.getURL()).equals(access.terminal())) {
                        deniedNavigations.incrementAndGet();
                        return true;
                    }
                }
                if (view != null && frame.isMain() && screen.builtin && screen.ready() && FilePages.location(request.getURL())) {
                    if (userGesture && !redirect) {
                        view.filesIntent(screen, request.getURL());
                        return true;
                    }
                    var access = view.access;
                    // A script can change presentation within its root; it cannot choose a new root.
                    if (access != null && !FilePages.sameRoot(access, request.getURL())) {
                        deniedNavigations.incrementAndGet();
                        return true;
                    }
                }
                if (view != null && frame.isMain()) {
                    if (screen.builtin) view.documentStarting(screen, request.getURL());
                    view.browsing((ScreenBrowser) browser, request.getURL());
                    if (userGesture && !redirect) view.gesture((ScreenBrowser) browser);
                }
                return false;
            }
            // Runs on Chromium's IO thread for every request. tma: content goes only to a screen's current built-in
            // browser, under the access granted for it, and to that browser's main frame and the tma: documents in it.
            // A URL alone grants nothing; browsers set aside or closing have no access.
            @Override public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame, CefRequest request,
                                                                                boolean navigation, boolean download, String initiator, BoolRef disableDefault) {
                String url = request.getURL();
                var place = pdfViewer(url, frame) ? Place.WEB : place(url);
                if (place == Place.NOWHERE) return DENY;
                if (place != Place.BUILTIN) return null;
                var view = views.get(browser);
                var access = view == null ? null : view.access;
                boolean allowed = access != null && access.browser() == browser && access.generation() == view.generation
                    && ((ScreenBrowser) browser).builtin && frame != null
                    && (frame.isMain() || !navigation && builtin(frame.getURL()));
                if (!allowed) return DENY;
                // The preload scanner can request CSS before CEF publishes the main frame's URL. Its native
                // browser kind and current grant identify it; consulting that provisional URL loses early assets.
                // Blob URLs the page made itself load as usual.
                return url.regionMatches(true, 0, Builtin.SCHEME + ":", 0, Builtin.SCHEME.length() + 1) ? Builtin.requests(view, access, frame) : null;
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
                if (request.startsWith("screen-terminal:")) {
                    if (persistent) callback.failure(1, "Terminal requests must be one-shot.");
                    else TerminalPages.query(views.get(browser), (ScreenBrowser) browser, frame, request.substring("screen-terminal:".length()), callback);
                    return true;
                }
                if (request.startsWith("screen-files:watch")) {
                    var view = views.get(browser);
                    var access = view == null ? null : view.access;
                    if (persistent || frame == null || !frame.isMain() || !((ScreenBrowser) browser).builtin || access == null
                        || access.browser() != browser || view.browser != browser || !FilePages.current(view, access)
                        || !request.equals("screen-files:watch:" + access.document()) || !FilePages.location(frame.getURL()))
                        callback.failure(1, "Only the current files page can watch files.");
                    else FileWatches.subscribe(view, access, id, callback);
                    return true;
                }
                if (request.startsWith("screen-capture:")) {
                    var view = views.get(browser);
                    String frameId = frame.getIdentifier();
                    if (view != null) Minecraft.getInstance().execute(() -> {
                        if (view.browser != browser) return;
                        ScreenInput.pointerLock(view, frameId, request.equals("screen-capture:locked"));
                        if (request.equals("screen-capture:ready")) {
                            var loaded = browser.getFrameByIdentifier(frameId);
                            if (loaded != null) ScreenInput.captureLoaded(view, loaded);
                        }
                    });
                    callback.success("");
                    return true;
                }
                if (!request.startsWith("screen-editing:")) return false;
                var view = views.get(browser);
                if (view != null) view.editing((ScreenBrowser) browser, request.endsWith("1"));
                callback.success("");
                return true;
            }
            @Override public void onQueryCanceled(CefBrowser browser, CefFrame frame, long id) {
                FileWatches.queryCancelled(browser, id);
            }
        }, true);
        client.addMessageRouter(router);
    }

    private static final CefResourceRequestHandler DENY = new CefResourceRequestHandlerAdapter() {
        @Override public boolean onBeforeResourceLoad(CefBrowser browser, CefFrame frame, CefRequest request) {
            deniedRequests.incrementAndGet();
            return true;
        }
    };

    /** A browser for the page at url: built-in for tma: pages, web for everything else. The caller creates it. */
    static ScreenBrowser open(ScreenView view, String url, int width, int height) {
        boolean builtin = builtin(url);
        // CefBrowser_N.onBeforeClose disposes the request context passed to its constructor.
        // Each built-in browser therefore owns a fresh in-memory context, separate from the web profile.
        var context = builtin ? java.util.Objects.requireNonNull(
            CefRequestContext.createContext(new CefRequestContextHandlerAdapter() {}), "Could not create the built-in browser context") : null;
        var browser = new ScreenBrowser(client, context, builtin, url, width, height, FPS, view);
        views.put(browser, view);
        browser.setCloseAllowed();
        return browser;
    }

    static void close(ScreenBrowser browser) {
        if (views.remove(browser) == null) return;
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
