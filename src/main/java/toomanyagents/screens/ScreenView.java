package toomanyagents.screens;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.cef.browser.CefDevToolsClient;
import org.cef.browser.ScreenBrowser;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** One screen on the client: its browser and the textures its frames land in. Render thread only. */
final class ScreenView implements ScreenBrowser.View {
    /** The frame around the page, in blocks. */
    static final double MARGIN = 1 / 16.0;
    static final int PIXELS_PER_BLOCK = 256;
    static final int MAX_PIXELS = 1920 * 1080;
    private static int nextId;

    /** The entity this view draws; replaced when the same screen is tracked again. */
    ScreenEntity screen;
    final Direction facing, right, up;
    final int blocksWide, blocksHigh;
    /** The navigation bar's height across the bottom of the screen, and the page's size above it, in blocks and pixels. */
    final double barHeight, pageWidth, pageHeight;
    final int pixelWidth, pixelHeight;
    /** The page's top-left corner in the world, on the panel's front. The bar runs along the page's bottom. */
    final Vec3 topLeft;
    final Vec3 normal, rightVector, upVector;
    private final Texture page = new Texture("page"), popup = new Texture("popup");

    /** The bar's controls, left to right. */
    enum Part { NAME, BACK, FORWARD, RELOAD, ADDRESS, MENU }
    /** The ⋯ menu's rows, top to bottom. It opens upward from the bar. */
    enum Action {
        OFF("Turn off (clear page)"), PICKUP("Pick up (save page)"), RESET("Reset and pick up");

        final String label;
        Action(String label) { this.label = label; }
    }
    /** Each control's left and right edge across the bar, in blocks, and the width of a square button. */
    private final double[] partStart = new double[Part.values().length], partEnd = new double[Part.values().length];
    final double unit;
    /** The menu rises from the bar's right end over the page: its left edge, top, width and row height, in blocks. */
    final double menuX, menuTop, menuWidth, menuRow;

    ScreenBrowser browser;
    String url = "", title = "";
    /**
     * The player's last click or key on the page, unless an agent's call has come since, and whether the page's next
     * address is the player's doing.
     */
    private long inputAt;
    private boolean playerInput, playerNavigating;
    /** Agent calls waiting on this page; closing it fails them. An open waits for its page's load to finish. */
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private CompletableFuture<Void> opening;
    private boolean openingStarted;
    /** The page an open replaced, whose late loads mustn't complete it. */
    private String superseded = "";
    /** Changes whenever an agent opens a page or the browser closes, so a call's answer can't come from another page. */
    long generation;
    private CefDevToolsClient devTools;
    /** The page last read from the entity; our own saves reach it a tick later. */
    private String saved = "";
    boolean loading, canGoBack, canGoForward, crashed;
    /** The focused element takes text. */
    boolean editable;
    int cursor;
    Rectangle popupBounds;
    private boolean popupShown;
    private boolean live = true;
    long lastSeen;

    ScreenView(ScreenEntity screen) {
        this.screen = screen;
        facing = screen.facing();
        up = screen.up();
        right = ScreenEntity.right(facing, up);
        upVector = Vec3.atLowerCornerOf(up.getNormal());
        blocksWide = screen.width();
        blocksHigh = screen.height();
        pageWidth = blocksWide - 2 * MARGIN;
        barHeight = Math.clamp((blocksHigh - 2 * MARGIN) * 0.08, 0.14, 0.3);
        pageHeight = blocksHigh - 2 * MARGIN - barHeight;
        var pixels = viewport(blocksWide, blocksHigh);
        pixelWidth = pixels[0];
        pixelHeight = pixels[1];
        normal = Vec3.atLowerCornerOf(facing.getNormal());
        rightVector = Vec3.atLowerCornerOf(right.getNormal());
        // Buttons are bar-height squares, narrowed on small screens so the address field keeps some room.
        unit = Math.min(barHeight, pageWidth / 8);
        double x = place(Part.NAME, 0, Math.clamp(pageWidth * 0.2, unit * 1.6, unit * 6));
        x = place(Part.BACK, x, unit);
        x = place(Part.FORWARD, x, unit);
        x = place(Part.RELOAD, x, unit);
        x = place(Part.ADDRESS, x, pageWidth - x - unit);
        place(Part.MENU, x, unit);
        // Rows fit three to the page, even on the smallest screen.
        menuRow = Math.min(barHeight * 1.2, pageHeight / Action.values().length);
        menuWidth = Math.min(pageWidth, menuRow * 9);
        menuX = pageWidth - menuWidth;
        menuTop = pageHeight - menuRow * Action.values().length;
        topLeft = Vec3.atCenterOf(screen.getPos()).add(normal.scale(-0.5 + ScreenEntity.DEPTH + 0.002))
            .add(rightVector.scale(-0.5 + MARGIN)).add(upVector.scale(blocksHigh - 0.5 - MARGIN));
    }

    /** The page's size in pixels for a screen this many blocks across and high. */
    static int[] viewport(int blocksWide, int blocksHigh) {
        double width = blocksWide - 2 * MARGIN, height = blocksHigh - 2 * MARGIN - Math.clamp((blocksHigh - 2 * MARGIN) * 0.08, 0.14, 0.3);
        double scale = Math.min(1, Math.sqrt(MAX_PIXELS / (width * height * PIXELS_PER_BLOCK * PIXELS_PER_BLOCK)));
        return new int[] {(int) Math.round(width * PIXELS_PER_BLOCK * scale), (int) Math.round(height * PIXELS_PER_BLOCK * scale)};
    }

    boolean matches(ScreenEntity other) {
        return other.width() == blocksWide && other.height() == blocksHigh && other.facing() == facing && other.up() == up && other.getPos().equals(screen.getPos());
    }

    Vec3 center() { return topLeft.add(rightVector.scale(pageWidth / 2)).add(upVector.scale(-pageHeight / 2)); }

    /** Where a ray meets the page plane, in blocks from the top-left corner, or null if it points away. */
    double[] hit(Vec3 origin, Vec3 direction) {
        double facingDot = direction.dot(normal);
        if (facingDot >= -1e-6) return null;
        double distance = topLeft.subtract(origin).dot(normal) / facingDot;
        if (distance <= 0) return null;
        Vec3 offset = origin.add(direction.scale(distance)).subtract(topLeft);
        return new double[] {offset.dot(rightVector), -offset.dot(upVector), distance};
    }

    private double place(Part part, double x, double width) {
        partStart[part.ordinal()] = x;
        partEnd[part.ordinal()] = x + width;
        return x + width;
    }

    double start(Part part) { return partStart[part.ordinal()]; }
    double end(Part part) { return partEnd[part.ordinal()]; }

    /** The bar control under a point, or null when off the bar. */
    Part barPart(double x, double y) {
        if (y < pageHeight || y >= pageHeight + barHeight) return null;
        for (var part : Part.values()) if (x >= start(part) && x < end(part)) return part;
        return null;
    }

    /** The menu row under a point, or null. Only meaningful while the menu is open. */
    Action menuAction(double x, double y) {
        if (x < menuX || x >= menuX + menuWidth || y < menuTop) return null;
        int row = (int) ((y - menuTop) / menuRow);
        return row < Action.values().length ? Action.values()[row] : null;
    }

    /** A menu row's centre in the world, for development checks. */
    Vec3 menuCenter(Action action) {
        return topLeft.add(rightVector.scale(menuX + menuWidth / 2)).add(upVector.scale(-(menuTop + (action.ordinal() + 0.5) * menuRow)));
    }

    /** A bar control's centre in the world, for development checks. */
    Vec3 barCenter(Part part) {
        return topLeft.add(rightVector.scale((start(part) + end(part)) / 2)).add(upVector.scale(-(pageHeight + barHeight / 2)));
    }

    boolean onPage(double x, double y) { return x >= 0 && y >= 0 && x <= pageWidth && y < pageHeight; }
    int pixelX(double x) { return Math.clamp((int) Math.floor(x / pageWidth * pixelWidth), 0, pixelWidth - 1); }
    int pixelY(double y) { return Math.clamp((int) Math.floor(y / pageHeight * pixelHeight), 0, pixelHeight - 1); }

    /** Keeps the browser in step with the screen's saved page. An idle screen has no browser. */
    void sync(String value, boolean trusted) {
        if (!value.equals(saved)) {
            saved = value;
            if (!value.equals(url)) {
                url = value;
                crashed = false;
                if (browser != null && !url.isBlank()) browser.loadURL(url);
            }
        }
        if (url.isBlank() && browser != null) close();
        if (browser == null && trusted && !url.isBlank() && Chromium.phase() == Chromium.Phase.READY) {
            browser = Chromium.open(this, url, pixelWidth, pixelHeight);
            live = true;
        }
    }

    void navigate(String target) {
        // A typed script runs in the page rather than becoming the screen's address.
        if (target.regionMatches(true, 0, "javascript:", 0, 11)) {
            if (browser != null) browser.getMainFrame().executeJavaScript(target.substring(11), "", 0);
            return;
        }
        url = target;
        crashed = false;
        if (browser != null) browser.loadURL(target);
        Screens.saveUrl(this, target, true);
    }

    /** The player reloading takes the page over, like navigating. */
    void reload() {
        crashed = false;
        if (browser != null) browser.reload();
        Screens.edit(this, record -> new Screen(record.id(), record.name(), record.url(), ""));
    }

    void back() {
        if (browser == null || !canGoBack) return;
        playerNavigating = true;
        browser.goBack();
    }

    void forward() {
        if (browser == null || !canGoForward) return;
        playerNavigating = true;
        browser.goForward();
    }

    /** Closes the browser and leaves the screen idle, keeping its id and name. */
    void powerOff() {
        shutDown();
        Screens.edit(this, record -> new Screen(record.id(), record.name(), "", ""));
    }

    /** powerOff without the record change, for callers that make it themselves. */
    void shutDown() {
        close();
        url = "";
        crashed = loading = canGoBack = canGoForward = editable = false;
        playerNavigating = false;
    }

    void rename(String name) {
        Screens.edit(this, record -> new Screen(record.id(), name, record.url(), record.openedBy()));
    }

    /** The player clicked or typed on the page. */
    void playerInput() {
        inputAt = System.nanoTime();
        playerInput = true;
    }

    /** An agent is acting on the page, so what it does next isn't the player's, whatever they did just before. */
    private void agentInput() {
        playerInput = false;
        playerNavigating = false;
    }

    /** An agent shows a page here. Completes once that page has loaded. */
    CompletableFuture<Void> open(String target) {
        agentInput();
        if (opening != null) opening.completeExceptionally(new ScreenTools.ScreenError("unavailable", "Another page was opened on this screen."));
        // Ready before Chromium is asked, in case it answers at once.
        var waiter = track(new CompletableFuture<Void>());
        opening = waiter;
        openingStarted = false;
        superseded = browser == null ? "" : url;
        generation++;
        url = target;
        title = "";
        crashed = false;
        if (browser != null) browser.loadURL(target);
        else Chromium.start();
        return waiter;
    }

    /** Physical input uses Chromium's complete keyboard mapping, while retaining player navigation ownership. */
    void playerInput(String method, com.google.gson.JsonObject params) {
        playerInput();
        if (devTools == null) devTools = browser.getDevToolsClient();
        devTools.executeDevToolsMethod(method, params.toString()).exceptionally(error -> {
            onRender(() -> ScreenInput.closing(this));
            com.mojang.logging.LogUtils.getLogger().warn("Screen input failed", error);
            return null;
        });
    }

    /** Runs one DevTools method on the page. */
    CompletableFuture<String> devTools(String method, String params) {
        agentInput();
        if (devTools == null) devTools = browser.getDevToolsClient();
        var result = track(new CompletableFuture<String>());
        devTools.executeDevToolsMethod(method, params).whenComplete((value, failure) -> {
            if (failure != null) result.completeExceptionally(failure);
            else result.complete(value);
        });
        return result;
    }

    /** The page's last painted frame, exactly as painted. Owned by the caller. */
    NativeImage frame() { return page.read(); }

    private <T> CompletableFuture<T> track(CompletableFuture<T> call) {
        pending.add(call);
        call.whenComplete((value, failure) -> pending.remove(call));
        return call;
    }

    int pendingCalls() { return pending.size(); }

    /** Distant or unseen screens keep their last frame; their pages tick over slowly. */
    void setLive(boolean value) {
        if (live == value || browser == null) return;
        live = value;
        browser.setWindowlessFrameRate(value ? Chromium.FPS : 1);
        if (value) browser.repaint();
    }

    boolean live() { return live; }
    boolean painted() { return page.width > 0; }
    boolean popupVisible() { return popupShown && popupBounds != null && popup.width > 0; }
    ResourceLocation pageTexture() { return page.location; }
    ResourceLocation popupTexture() { return popup.location; }

    void close() {
        ScreenInput.closing(this);
        if (devTools != null) devTools.close();
        devTools = null;
        if (browser != null) Chromium.close(browser);
        browser = null;
        generation++;
        opening = null;
        title = "";
        page.release();
        popup.release();
        var failure = new ScreenTools.ScreenError("unavailable", "The screen's page was closed.");
        for (var call : pending) call.completeExceptionally(failure);
    }

    /** Regenerates the distance mip levels after new frames, once per drawn frame. */
    void prepare() {
        page.updateMipmaps();
        popup.updateMipmaps();
    }

    // Chromium callbacks. They arrive on the render thread on macOS, and on Chromium's own thread elsewhere.
    // Each names its browser, so a closed or replaced browser's late callbacks are ignored.

    @Override public void paint(ScreenBrowser source, boolean isPopup, Rectangle[] dirty, ByteBuffer frame, int width, int height) {
        if (!live) return;
        var target = isPopup ? popup : page;
        if (RenderSystem.isOnRenderThread()) {
            if (source == browser) target.upload(dirty, frame, width, height);
            return;
        }
        // The frame is only valid during this call, so copy it before handing it over.
        var copy = MemoryUtil.memAlloc(width * height * 4);
        MemoryUtil.memCopy(MemoryUtil.memAddress(frame), MemoryUtil.memAddress(copy), (long) width * height * 4);
        Minecraft.getInstance().execute(() -> {
            if (source == browser) target.upload(new Rectangle[] {new Rectangle(0, 0, width, height)}, copy, width, height);
            MemoryUtil.memFree(copy);
        });
    }

    @Override public void popup(ScreenBrowser source, boolean shown, Rectangle bounds) {
        onRender(() -> {
            if (source != browser) return;
            popupShown = shown;
            popupBounds = bounds;
        });
    }

    @Override public void cursor(ScreenBrowser source, int type) { if (source == browser) cursor = type; }

    void loading(ScreenBrowser source, boolean value, boolean back, boolean forward) {
        onRender(() -> {
            if (source != browser) return;
            loading = value;
            canGoBack = back;
            canGoForward = forward;
            if (value) return;
            playerNavigating = false;
            if (opening != null && !opening.isDone() && openingStarted) {
                opening.complete(null);
                opening = null;
            }
        });
    }

    /**
     * The main frame started loading; an open waits for the load that starts after it. A late load of the page it
     * replaced doesn't count. Redirects can change the address, so any other page does.
     */
    void loadStarted(ScreenBrowser source, String address) {
        onRender(() -> {
            if (source != browser) return;
            ScreenInput.pageLoading(this);
            if (opening == null || opening.isDone()) return;
            String loaded = trimSlash(address);
            if (loaded.equals(trimSlash(url)) || !loaded.equals(trimSlash(superseded))) openingStarted = true;
        });
    }

    private static String trimSlash(String address) {
        return address == null ? "" : address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
    }

    void title(ScreenBrowser source, String value) {
        onRender(() -> { if (source == browser) title = value == null ? "" : value; });
    }

    /** A main-frame navigation the page started from a user gesture. Only the player's own recent input makes it theirs. */
    void gesture(ScreenBrowser source) {
        onRender(() -> {
            if (source == browser && recentInput()) playerNavigating = true;
        });
    }

    void address(ScreenBrowser source, String address) {
        onRender(() -> {
            if (source != browser || address.equals(url)) return;
            url = address;
            // Following a link the player clicked takes the page over; redirects and scripted changes don't.
            Screens.saveUrl(this, address, playerNavigating || recentInput());
            playerNavigating = false;
        });
    }

    /** Same-page navigations skip the gesture check, so a click just before still counts. */
    private boolean recentInput() { return playerInput && System.nanoTime() - inputAt < 1_000_000_000L; }

    void crashed(ScreenBrowser source) { onRender(() -> { if (source == browser) crashed = true; }); }

    void editing(ScreenBrowser source, boolean value) {
        onRender(() -> {
            if (source != browser) return;
            editable = value;
            ScreenInput.editingChanged(this, value);
        });
    }

    private static void onRender(Runnable task) {
        if (RenderSystem.isOnRenderThread()) task.run();
        else Minecraft.getInstance().execute(task);
    }

    /** A texture filled straight from Chromium's BGRA frames, a dirty rectangle at a time. */
    private static final class Texture extends AbstractTexture {
        final ResourceLocation location;
        int width, height;
        private boolean registered, mipmapsStale;

        Texture(String kind) {
            location = ResourceLocation.fromNamespaceAndPath("too_many_agents", "screen/" + kind + "/" + nextId++);
        }

        @Override public void load(ResourceManager manager) {}

        void upload(Rectangle[] dirty, ByteBuffer frame, int frameWidth, int frameHeight) {
            if (!registered) {
                Minecraft.getInstance().getTextureManager().register(location, this);
                registered = true;
            }
            GlStateManager._bindTexture(getId());
            if (frameWidth != width || frameHeight != height) {
                GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, frameWidth, frameHeight, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (IntBuffer) null);
                setFilter(true, true);
                width = frameWidth;
                height = frameHeight;
                dirty = new Rectangle[] {new Rectangle(0, 0, frameWidth, frameHeight)};
            }
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, frameWidth);
            for (var rect : dirty) {
                var r = rect.intersection(new Rectangle(0, 0, frameWidth, frameHeight));
                if (r.isEmpty()) continue;
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, r.x);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, r.y);
                GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, r.x, r.y, r.width, r.height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(frame));
            }
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            mipmapsStale = true;
        }

        /** Reads the frame back from the GPU. Render thread only. */
        NativeImage read() {
            var image = new NativeImage(width, height, false);
            GlStateManager._bindTexture(getId());
            image.downloadTexture(0, false);
            return image;
        }

        void updateMipmaps() {
            if (!mipmapsStale) return;
            GlStateManager._bindTexture(getId());
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            mipmapsStale = false;
        }

        void release() {
            if (registered) Minecraft.getInstance().getTextureManager().release(location);
            registered = false;
            width = height = 0;
        }
    }
}
