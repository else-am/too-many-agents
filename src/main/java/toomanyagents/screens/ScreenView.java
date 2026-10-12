package toomanyagents.screens;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
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
    final Direction facing, right;
    final int blocksWide, blocksHigh;
    /** The navigation bar's height across the top of the screen, and the page's size below it, in blocks and pixels. */
    final double barHeight, pageWidth, pageHeight;
    final int pixelWidth, pixelHeight;
    /** The page's top-left corner in the world, just under the bar and on the panel's front. */
    final Vec3 topLeft;
    final Vec3 normal, rightVector;
    private final Texture page = new Texture("page"), popup = new Texture("popup");

    /** The bar's controls, left to right. */
    enum Part { NAME, BACK, FORWARD, RELOAD, ADDRESS, POWER }
    /** Each control's left and right edge across the bar, in blocks, and the width of a square button. */
    private final double[] partStart = new double[Part.values().length], partEnd = new double[Part.values().length];
    final double unit;

    ScreenBrowser browser;
    String url = "";
    /** The player's last click or key on the page, and whether the page's next address is the player's doing. */
    long inputAt;
    private boolean playerNavigating;
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
        right = ScreenEntity.right(facing);
        blocksWide = screen.width();
        blocksHigh = screen.height();
        pageWidth = blocksWide - 2 * MARGIN;
        barHeight = Math.clamp((blocksHigh - 2 * MARGIN) * 0.08, 0.14, 0.3);
        pageHeight = blocksHigh - 2 * MARGIN - barHeight;
        double scale = Math.min(1, Math.sqrt(MAX_PIXELS / (pageWidth * pageHeight * PIXELS_PER_BLOCK * PIXELS_PER_BLOCK)));
        pixelWidth = (int) Math.round(pageWidth * PIXELS_PER_BLOCK * scale);
        pixelHeight = (int) Math.round(pageHeight * PIXELS_PER_BLOCK * scale);
        normal = Vec3.atLowerCornerOf(facing.getNormal());
        rightVector = Vec3.atLowerCornerOf(right.getNormal());
        // Buttons are bar-height squares, narrowed on small screens so the address field keeps some room.
        unit = Math.min(barHeight, pageWidth / 8);
        double x = place(Part.NAME, 0, Math.clamp(pageWidth * 0.2, unit * 1.6, unit * 6));
        x = place(Part.BACK, x, unit);
        x = place(Part.FORWARD, x, unit);
        x = place(Part.RELOAD, x, unit);
        x = place(Part.ADDRESS, x, pageWidth - x - unit);
        place(Part.POWER, x, unit);
        topLeft = Vec3.atCenterOf(screen.getPos()).add(normal.scale(-0.5 + ScreenEntity.DEPTH + 0.002))
            .add(rightVector.scale(-0.5 + MARGIN)).add(0, blocksHigh - 0.5 - MARGIN - barHeight, 0);
    }

    boolean matches(ScreenEntity other) {
        return other.width() == blocksWide && other.height() == blocksHigh && other.facing() == facing && other.getPos().equals(screen.getPos());
    }

    Vec3 center() { return topLeft.add(rightVector.scale(pageWidth / 2)).add(0, -pageHeight / 2, 0); }

    /** Where a ray meets the page plane, in blocks from the top-left corner, or null if it points away. */
    double[] hit(Vec3 origin, Vec3 direction) {
        double facingDot = direction.dot(normal);
        if (facingDot >= -1e-6) return null;
        double distance = topLeft.subtract(origin).dot(normal) / facingDot;
        if (distance <= 0) return null;
        Vec3 offset = origin.add(direction.scale(distance)).subtract(topLeft);
        return new double[] {offset.dot(rightVector), -offset.y, distance};
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
        if (y < -barHeight || y >= 0) return null;
        for (var part : Part.values()) if (x >= start(part) && x < end(part)) return part;
        return null;
    }

    boolean onPage(double x, double y) { return x >= 0 && y >= 0 && x <= pageWidth && y <= pageHeight; }
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

    void reload() {
        crashed = false;
        if (browser != null) browser.reload();
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
        close();
        url = "";
        crashed = loading = canGoBack = canGoForward = editable = false;
        playerNavigating = false;
        Screens.edit(this, record -> new Screen(record.id(), record.name(), "", ""));
    }

    void rename(String name) {
        Screens.edit(this, record -> new Screen(record.id(), name, record.url(), record.openedBy()));
    }

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
        if (browser != null) Chromium.close(browser);
        browser = null;
        page.release();
        popup.release();
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
            if (!value) playerNavigating = false;
        });
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
    private boolean recentInput() { return System.nanoTime() - inputAt < 1_000_000_000L; }

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
