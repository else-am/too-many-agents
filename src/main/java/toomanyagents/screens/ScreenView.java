package toomanyagents.screens;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    ScreenIndex.Entry entry;
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

    volatile ScreenBrowser browser;
    /** Shown instead of a browser for a human-granted ?pick location. */
    FilePicker picker;
    /**
     * What the current built-in browser may load, bound to that native browser and the page generation it was granted
     * for; the authorized root joins it with the files view. Null on the web and for every browser but the current one.
     */
    enum Provenance { HUMAN, AGENT }
    record Grant(ConfinedFiles.Root root, Provenance provenance, String notice, String owner, String terminal) {
        Grant(ConfinedFiles.Root root, Provenance provenance, String notice, String owner) { this(root, provenance, notice, owner, ""); }
    }
    private static final java.util.concurrent.atomic.AtomicLong nextDocument = new java.util.concurrent.atomic.AtomicLong();
    record Access(ScreenBrowser browser, long generation, ConfinedFiles.Root root, Provenance provenance, String notice, String owner, String terminal, long document) {
        Access(ScreenBrowser browser, long generation, ConfinedFiles.Root root, Provenance provenance, String notice, String owner, String terminal) {
            this(browser, generation, root, provenance, notice, owner, terminal, nextDocument.incrementAndGet());
        }
    }
    private Grant fileGrant;
    private CompletableFuture<?> granting;
    private String grantAttempt = "";
    String fileError = "";
    volatile Access access;
    int terminalPending;
    /** Addresses outside the current native history, nearest first. Crossing browser kinds always reopens fresh. */
    final LinkedList<Entry> behind = new LinkedList<>(), ahead = new LinkedList<>();
    record Entry(String url, String title, Grant grant) {}
    private static final int HISTORY = 50;
    private boolean browserBack, browserForward;
    /** Detached only long enough to read its history, then closed before the replacement opens. */
    private ScreenBrowser departing;
    private long historyReading;
    String url = "", title = "";
    /**
     * The player's last click or key on the page, unless an agent's call has come since, and whether the page's next
     * address is the player's doing.
     */
    private long inputAt;
    private boolean playerInput, playerNavigating;
    /** Agent calls waiting on this page; closing it fails them. An open waits for its page's load to finish. */
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<Void>> frames = ConcurrentHashMap.newKeySet();
    /** Agents' DevTools calls, which must not outlive the browser they were sent to. */
    private final Set<CompletableFuture<String>> commands = ConcurrentHashMap.newKeySet();
    private CompletableFuture<Void> opening;
    private CompletableFuture<Void> waking;
    private boolean pageReady;
    private boolean openingStarted, openingFinished;
    /** The page an open replaced, whose late loads mustn't complete it. */
    private String superseded = "";
    /** Changes whenever an agent opens a page or the browser closes, so a call's answer can't come from another page. */
    volatile long generation;
    /** The page last read from the entity; our own saves reach it a tick later. */
    private String saved = "";
    boolean loading, canGoBack, canGoForward, crashed;
    /** The focused element takes text. */
    boolean editable;
    int cursor;
    Rectangle popupBounds;
    private boolean popupShown;
    private volatile boolean live = true;
    long lastSeen;

    ScreenView(ScreenEntity screen) {
        this(Screens.entry(screen));
        this.screen = screen;
    }

    ScreenView(ScreenIndex.Entry entry) {
        this.entry = entry;
        facing = Direction.byName(entry.facing());
        up = Direction.byName(entry.up());
        right = ScreenEntity.right(facing, up);
        upVector = Vec3.atLowerCornerOf(up.getNormal());
        blocksWide = entry.width();
        blocksHigh = entry.height();
        pageWidth = blocksWide - 2 * MARGIN;
        barHeight = barHeight(blocksHigh);
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
        topLeft = Vec3.atCenterOf(entry.pos()).add(normal.scale(-0.5 + ScreenEntity.DEPTH + 0.002))
            .add(rightVector.scale(-0.5 + MARGIN)).add(upVector.scale(blocksHigh - 0.5 - MARGIN));
    }

    /** The navigation bar's height in blocks: a thin strip that grows a little on tall screens. */
    static double barHeight(int blocksHigh) { return Math.clamp((blocksHigh - 2 * MARGIN) * 0.06, 0.11, 0.22); }

    /** The page's size in pixels for a screen this many blocks across and high. */
    static int[] viewport(int blocksWide, int blocksHigh) {
        double width = blocksWide - 2 * MARGIN, height = blocksHigh - 2 * MARGIN - barHeight(blocksHigh);
        double scale = Math.min(1, Math.sqrt(MAX_PIXELS / (width * height * PIXELS_PER_BLOCK * PIXELS_PER_BLOCK)));
        return new int[] {(int) Math.round(width * PIXELS_PER_BLOCK * scale), (int) Math.round(height * PIXELS_PER_BLOCK * scale)};
    }

    boolean matches(ScreenEntity other) {
        return matches(Screens.entry(other));
    }

    boolean matches(ScreenIndex.Entry other) {
        return other.width() == blocksWide && other.height() == blocksHigh && other.facing().equals(entry.facing())
            && other.up().equals(entry.up()) && other.pos().equals(entry.pos()) && other.dimension().equals(entry.dimension());
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

    enum BlankAction { TERMINAL, FILES, ADDRESS }
    record Bounds(double x, double y, double width, double height) {
        boolean contains(double px, double py) { return px >= x && py >= y && px < x + width && py < y + height; }
    }

    /** The blank page's buttons, stacked and centred on the page. */
    Bounds blankBounds(BlankAction action) {
        double width = Math.min(pageWidth * 0.8, barHeight * 9), height = Math.min(pageHeight * 0.2, barHeight * 1.6);
        int count = BlankAction.values().length;
        double gap = height * 0.25, top = (pageHeight - count * height - (count - 1) * gap) / 2;
        return new Bounds((pageWidth - width) / 2, top + action.ordinal() * (height + gap), width, height);
    }

    BlankAction blankAction(double x, double y) {
        if (!url.isBlank()) return null;
        for (var action : BlankAction.values()) if (blankBounds(action).contains(x, y)) return action;
        return null;
    }

    boolean onPage(double x, double y) { return x >= 0 && y >= 0 && x <= pageWidth && y < pageHeight; }
    int pixelX(double x) { return Math.clamp((int) Math.floor(x / pageWidth * pixelWidth), 0, pixelWidth - 1); }
    int pixelY(double y) { return Math.clamp((int) Math.floor(y / pageHeight * pixelHeight), 0, pixelHeight - 1); }

    /** Keeps the browser in step with the screen's saved page. An idle screen has no browser. */
    void sync(String value, boolean trusted) {
        // A saved address no browser may load, such as an old file: page, leaves the screen idle.
        if (Chromium.place(value) == Chromium.Place.NOWHERE) value = "";
        if (!value.equals(saved)) {
            saved = value;
            if (!value.equals(url)) {
                cancelGrant();
                fileGrant = null;
                grantAttempt = "";
                url = value;
                crashed = false;
                closePicker();
                if (browser != null && !url.isBlank() && !FilePages.picker(url)) load(url);
            }
        }
        if (url.isBlank() && awake()) close();
        // A saved location is granted afresh before its page starts. An agent's waits until its environment can be checked.
        boolean grantDue = trusted && (FilePages.location(url) || TerminalPages.location(url)) && fileGrant == null && !url.equals(grantAttempt);
        if (grantDue) {
            if (TerminalPages.location(url)) openTerminal(url, false, entry.screen().openedBy());
            else openFiles(url, false, entry.screen().openedBy());
        }
        // The picker needs no browser; its location shows only once granted.
        if (browser == null && departing == null && granting == null && !grantDue && trusted && !url.isBlank() && !FilePages.picker(url)
                && Chromium.phase() == Chromium.Phase.READY && Screens.canOpen()) {
            start(url);
        }
    }

    void navigate(String target) {
        if (TerminalPages.location(target)) { openTerminal(target, true, ""); return; }
        if (FilePages.location(target)) { openFiles(target, true); return; }
        // A typed script runs in the page rather than becoming the screen's address. Built-in pages run only their own.
        if (target.regionMatches(true, 0, "javascript:", 0, 11)) {
            if (browser != null && !browser.builtin) browser.getMainFrame().executeJavaScript(target.substring(11), "", 0);
            return;
        }
        if (Chromium.place(target) == Chromium.Place.NOWHERE) return;
        leavePicker();
        cancelGrant();
        fileGrant = null;
        fileError = "";
        url = target;
        crashed = false;
        if (browser == null) Chromium.start();
        load(target);
        Screens.saveUrl(this, target, true);
    }

    /** Native human controls are the only live source of broader file grants. */
    void files() { openStart(BlankAction.FILES); }

    void terminal() { openStart(BlankAction.TERMINAL); }

    private void openStart(BlankAction action) {
        if (granting != null) return;
        Trust.allow(Minecraft.getInstance().getSingleplayerServer());
        fileError = "";
        var request = ScreenTools.startFolder(entry.screen().id());
        granting = request;
        request.whenComplete((folder, failure) -> onRender(() -> {
            if (granting != request) return;
            granting = null;
            if (failure != null) { fileError = TerminalPages.message(failure); return; }
            if (action == BlankAction.TERMINAL) terminal(folder);
            else openFiles(Builtin.files(folder) + "?pick", true);
        }));
    }

    /** The picker's choices are native human navigation, like the bar's address field. */
    void choose(String target) {
        fileError = "";
        openFiles(target, true);
    }

    /** Show hidden files changes only how the picker's location is saved, never its grant. */
    void pickerHidden(boolean hidden) {
        if (!picking()) return;
        url = grantAttempt = Builtin.files(java.nio.file.Path.of(fileGrant.root().path())) + (hidden ? "?pick&hidden" : "?pick");
        Screens.saveUrl(this, url, true);
    }

    boolean awake() { return browser != null || picker != null; }

    /** A human grant for a ?pick location; agents' grants never keep ?pick. */
    private boolean picking() {
        return fileGrant != null && fileGrant.provenance() == Provenance.HUMAN && fileGrant.root() != null && FilePages.picker(url);
    }

    /** Shows the picker at the granted folder, in place of any browser, which must already have left. */
    private void showPicker() {
        if (picker == null) picker = new FilePicker(this);
        picker.open(fileGrant.root(), FilePages.query(java.net.URI.create(url)).containsKey("hidden"));
        var name = java.nio.file.Path.of(fileGrant.root().path()).getFileName();
        title = name == null ? fileGrant.root().path() : name.toString();
        generation++;
        crashed = loading = pageReady = browserBack = browserForward = editable = popupShown = false;
        // A browser shown after the picker starts blank rather than with an older page's pixels.
        page.release();
        popup.release();
        history();
    }

    private void closePicker() {
        if (picker == null) return;
        picker.close();
        picker = null;
        ScreenInput.pickerClosed(this);
        generation++;
        history();
    }

    /** Leaving the picker for another page records it as one step back, like leaving a browser. */
    private void leavePicker() {
        if (picker == null) return;
        push(behind, new Entry(url, title, fileGrant));
        drop(ahead);
        closePicker();
    }

    /** What follows a browser's departure, or replaces nothing: the granted picker, or a browser for any other page. */
    private void showLocation() {
        if (picking()) showPicker();
        else if (!url.isBlank() && !FilePages.picker(url)) {
            if (browser == null && departing == null) Chromium.start();
        }
    }

    /** Draws the picker before the world renders, while the screen is watched or a screenshot waits for it. */
    void renderPicker() {
        if (picker == null || !Trust.allowedHere()) return;
        // A screenshot waiting for a fresh frame redraws it even when nothing changed.
        boolean force = !frames.isEmpty();
        if (!live && !force && picker.painted()) return;
        if (!picker.render(force)) return;
        if (waking != null) waking.complete(null);
        paintedFrame();
    }

    private void push(LinkedList<Entry> stack, Entry value) {
        stack.push(value);
        while (stack.size() > HISTORY) stack.removeLast();
    }

    private void cancelGrant() {
        if (granting != null) granting.cancel(true);
        granting = null;
    }

    private void openFiles(String target, boolean takeover) { openFiles(target, takeover, ""); }

    /**
     * owner is the agent thread whose saved view this is, or "" for the player. An agent's view is granted only inside
     * the environment its plugin reports now, and only at exactly the root it was saved with, so a moved or replaced root
     * fails closed instead of following the change.
     */
    private void openFiles(String target, boolean takeover, String owner) {
        var agent = owner.isEmpty() ? null : ScreenTools.agentFiles(owner, environment -> target);
        if (!owner.isEmpty() && agent == null) return;
        var request = agent == null ? FilePages.submit(() -> FilePages.grant(target)) : agent.thenApply(opened -> {
            if (!opened.root().path().equals(FilePages.path(target).toString()))
                throw new java.util.concurrent.CompletionException(new java.io.IOException("This view's folder has moved. Reopen it."));
            return opened;
        });
        cancelGrant();
        grantAttempt = target;
        fileError = "";
        granting = request;
        request.whenComplete((opened, failure) -> onRender(() -> {
            if (granting != request) return;
            granting = null;
            if (failure != null) {
                var cause = failure instanceof java.util.concurrent.CompletionException ? failure.getCause() : failure;
                fileError = cause.getMessage() == null ? "Could not open this location." : cause.getMessage();
                return;
            }
            showGranted(opened.url(), new Grant(opened.root(), owner.isEmpty() ? Provenance.HUMAN : Provenance.AGENT, opened.notice(), owner), takeover);
        }));
    }

    /** Applies a completed native grant, keeping the old location in history. */
    private void showGranted(String target, Grant grant, boolean takeover) {
        var previous = picker == null ? null : new Entry(url, title, fileGrant);
        fileGrant = grant;
        url = grantAttempt = target;
        crashed = false;
        if (previous != null) {
            if (!previous.url().equals(url)) { push(behind, previous); drop(ahead); }
            if (!picking()) closePicker();
            showLocation();
        } else if (browser != null) {
            drop(ahead);
            leave(behind, false);
        } else showLocation();
        Screens.saveUrl(this, url, takeover);
    }

    /** The native picker's Terminal action starts a BB shell at the folder the player chose. */
    void terminal(java.nio.file.Path folder) {
        if (granting != null) return;
        if (!Screens.canOpen()) { fileError = "All screen browsers are in use."; return; }
        fileError = "";
        var request = TerminalPages.create(folder);
        granting = request;
        request.whenComplete((session, failure) -> onRender(() -> {
            if (granting != request) return;
            granting = null;
            if (failure != null) { fileError = TerminalPages.message(failure); return; }
            String id = session.get("id").getAsString();
            showGranted(TerminalPages.url(id), new Grant(null, Provenance.HUMAN, "", "", id), true);
        }));
    }

    /** A saved or typed terminal address is reauthorized through BB before it is shown. */
    private void openTerminal(String target, boolean takeover, String owner) {
        var request = TerminalPages.authorize(target, owner);
        if (request == null) {
            if (takeover) fileError = "Minecraft is not connected to BB yet.";
            return;
        }
        cancelGrant();
        grantAttempt = target;
        fileError = "";
        granting = request;
        request.whenComplete((session, failure) -> onRender(() -> {
            if (granting != request) return;
            granting = null;
            if (failure != null) { fileError = TerminalPages.message(failure); return; }
            String id = session.get("id").getAsString();
            showGranted(TerminalPages.url(id), new Grant(null, owner.isEmpty() ? Provenance.HUMAN : Provenance.AGENT, "", owner, id), takeover);
        }));
    }

    /** Only a trusted main-frame navigation from bundled controls can choose another grant. */
    void filesIntent(ScreenBrowser source, String target) {
        onRender(() -> {
            if (source != browser || !recentInput() || !FilePages.location(target)) return;
            playerInput = false;
            var grant = access;
            if (grant == null) return;
            boolean picker = FilePages.picker(target);
            // Only leaving through the picker may take over an agent view or choose a broader root.
            if (!picker && (grant.provenance() == Provenance.AGENT || !FilePages.picker(source.getURL()))) {
                if (grant.root() != null && FilePages.sameRoot(grant, target)) browser.loadURL(target);
                return;
            }
            openFiles(target, grant.provenance() == Provenance.AGENT);
        });
    }

    /** The player clicked a link from a built-in page out to the web. */
    void follow(ScreenBrowser source, String target) {
        onRender(() -> { if (source == browser && recentInput()) navigate(target); });
    }

    /** Loads a page in this browser, or in a new one when it moves between web and built-in content. */
    private void load(String target) {
        if (browser == null) return;
        if (Chromium.builtin(target) == browser.builtin && (!browser.builtin || access == null || access.root() == (fileGrant == null ? null : fileGrant.root()) && access.terminal().equals(fileGrant == null ? "" : fileGrant.terminal()))) {
            authorize();
            browser.loadURL(target);
            return;
        }
        drop(ahead);
        leave(behind, false);
    }

    private void authorize() {
        setAccess(browser != null && browser.builtin ? new Access(browser, generation,
            fileGrant == null ? null : fileGrant.root(), fileGrant == null ? Provenance.AGENT : fileGrant.provenance(), fileGrant == null ? "" : fileGrant.notice(), fileGrant == null ? entry.screen().openedBy() : fileGrant.owner(), fileGrant == null ? "" : fileGrant.terminal()) : null);
    }

    private void setAccess(Access value) {
        Access previous;
        synchronized (this) {
            previous = access;
            access = value;
        }
        FilePages.cancel(previous);
    }

    /** Runs directly in onBeforeBrowse, before the new document can start any data requests. */
    void documentStarting(ScreenBrowser source, String target) {
        String current = source.getURL();
        if (current != null && !current.equals(target) && current.split("#", 2)[0].equals(target.split("#", 2)[0])) return;
        Access previous;
        synchronized (this) {
            previous = access;
            if (source != browser || previous == null || previous.browser() != source || previous.generation() != generation) return;
            // A document boundary keeps the pinned permission, but has its own cancellation identity.
            access = new Access(source, generation, previous.root(), previous.provenance(), previous.notice(), previous.owner(), previous.terminal());
        }
        FilePages.cancel(previous);
    }

    /** A new browser at target becomes the current one. Its access is in place before it makes any request. */
    private void start(String target) {
        browser = Chromium.open(this, target, pixelWidth, pixelHeight);
        title = "";
        browserBack = browserForward = pageReady = false;
        loading = true;
        crashed = popupShown = editable = false;
        live = true;
        authorize();
        history();
        browser.createImmediately();
    }

    /** Detaches access immediately and saves only addresses. No old native browser survives restoration. */
    private void leave(LinkedList<Entry> stack, boolean back) {
        var old = browser;
        String oldTitle = title;
        var oldGrant = access == null ? null : new Grant(access.root(), access.provenance(), access.notice(), access.owner(), access.terminal());
        ScreenInput.pageLoading(this);
        failCommands("The page moved to another browser before DevTools answered. The outcome is unknown; check the page before acting again.");
        setAccess(null);
        generation++;
        browser = null;
        departing = old;
        browserBack = browserForward = false;
        old.devTools("Page.getNavigationHistory", "{}").orTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .whenComplete((answer, failure) -> onRender(() -> {
                if (departing != old) return;
                var entries = new LinkedList<Entry>();
                try {
                    if (failure == null) {
                        var result = com.google.gson.JsonParser.parseString(answer).getAsJsonObject();
                        var nativeEntries = result.getAsJsonArray("entries");
                        int current = result.get("currentIndex").getAsInt();
                        // Push farthest first, leaving the page we just left nearest the top.
                        int first = back ? nativeEntries.size() - 1 : old.firstEntry();
                        int step = back ? -1 : 1;
                        for (int i = first; back ? i >= current : i <= current; i += step) {
                            var item = nativeEntries.get(i).getAsJsonObject();
                            String address = item.get("url").getAsString();
                            if (Chromium.place(address) != Chromium.Place.NOWHERE)
                                entries.push(new Entry(address, item.get("title").getAsString(), oldGrant));
                        }
                    }
                } catch (RuntimeException ignored) {
                    entries.clear();
                }
                if (entries.isEmpty() && Chromium.place(old.getURL()) != Chromium.Place.NOWHERE)
                    entries.push(new Entry(old.getURL(), oldTitle, oldGrant));
                stack.addAll(0, entries);
                while (stack.size() > HISTORY) stack.removeLast();
                Chromium.close(old);
                departing = null;
                if (picking()) showPicker();
                else if (!url.isBlank() && !FilePages.picker(url)) start(url);
            }));
    }

    private void drop(LinkedList<Entry> stack) { stack.clear(); }

    /** Returns to an address outside this browser's history, as the player's own navigation, with its pinned grant. */
    private void restore(LinkedList<Entry> from, boolean back) {
        var entry = from.pop();
        var current = picker == null ? null : new Entry(url, title, fileGrant);
        cancelGrant();
        fileGrant = entry.grant();
        url = entry.url();
        fileError = "";
        playerNavigating = false;
        String owner = fileGrant == null ? "" : fileGrant.owner();
        String restoredUrl = url;
        Screens.edit(this, record -> new Screen(record.id(), record.name(), restoredUrl, owner));
        if (current == null) {
            leave(back ? ahead : behind, back);
            return;
        }
        push(back ? ahead : behind, current);
        if (!picking()) closePicker();
        showLocation();
    }

    /** Chromium's index controls traversal; the bootstrap page never becomes a Back destination. */
    private void readHistory(ScreenBrowser source) {
        if (source != browser || !source.ready()) return;
        long reading = ++historyReading;
        source.devTools("Page.getNavigationHistory", "{}").thenAccept(answer -> onRender(() -> {
            if (source != browser || reading != historyReading || crashed) return;
            var result = com.google.gson.JsonParser.parseString(answer).getAsJsonObject();
            int length = result.getAsJsonArray("entries").size(), current = result.get("currentIndex").getAsInt();
            browserBack = current > source.firstEntry();
            browserForward = current < length - 1;
            // Restored browsers start with one real entry. A second is a push, including a hash change;
            // replaceState leaves one and therefore preserves the external forward stack.
            if (length - source.firstEntry() > 1) drop(ahead);
            history();
        })).exceptionally(failure -> null);
    }

    private void history() {
        canGoBack = browserBack || !behind.isEmpty();
        canGoForward = browserForward || !ahead.isEmpty();
    }

    private void failCommands(String message) {
        var failure = new ScreenTools.ScreenError("unavailable", message);
        for (var command : commands) command.completeExceptionally(failure);
    }

    /**
     * The player reloading a web page takes it over, like navigating. A files location without file access retries its
     * grant on the same terms as a restore, keeping its owner; a pinned grant is kept as it is.
     */
    void reload() {
        crashed = false;
        if (picker != null) { picker.reload(); return; }
        if ((browser != null || FilePages.picker(url)) && FilePages.location(url) && (fileGrant == null || fileGrant.root() == null)) {
            openFiles(url, false, entry.screen().openedBy());
            return;
        }
        if (TerminalPages.location(url) && (fileGrant == null || fileGrant.terminal().isEmpty())) {
            openTerminal(url, false, entry.screen().openedBy());
            return;
        }
        authorize();
        if (browser != null) browser.reload();
        if (browser == null || !browser.builtin) Screens.edit(this, record -> new Screen(record.id(), record.name(), record.url(), ""));
    }

    void back() {
        if (!awake() || !canGoBack) return;
        playerNavigating = true;
        if (browser != null && browserBack) browser.goBack();
        else restore(behind, true);
    }

    void forward() {
        if (!awake() || !canGoForward) return;
        playerNavigating = true;
        if (browser != null && browserForward) browser.goForward();
        else restore(ahead, false);
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
        crashed = loading = canGoBack = canGoForward = browserBack = browserForward = editable = false;
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

    /** An agent shows a page here, with files already granted for a files location. Completes once that page has loaded. */
    CompletableFuture<Void> open(String target, String owner, FilePages.Opened files) {
        agentInput();
        leavePicker();
        cancelGrant();
        fileGrant = new Grant(files == null ? null : files.root(), Provenance.AGENT, files == null ? "" : files.notice(), owner, TerminalPages.location(target) ? TerminalPages.id(target) : "");
        grantAttempt = target;
        fileError = "";
        if (opening != null) opening.completeExceptionally(new ScreenTools.ScreenError("unavailable", "Another page was opened on this screen."));
        // Ready before Chromium is asked, in case it answers at once.
        var waiter = track(new CompletableFuture<Void>());
        opening = waiter;
        openingStarted = openingFinished = false;
        superseded = browser == null ? "" : url;
        generation++;
        url = target;
        title = "";
        crashed = false;
        if (browser != null) load(target);
        else Chromium.start();
        return waiter;
    }

    /** Physical input uses Chromium's complete keyboard mapping, while retaining player navigation ownership. */
    void playerInput(String method, com.google.gson.JsonObject params) {
        if (browser == null || crashed) return;
        playerInput();
        var source = browser;
        // Keep surrogate pairs escaped in JSON: JCEF's JNI string conversion otherwise corrupts emoji.
        var json = new StringBuilder();
        for (char c : params.toString().toCharArray()) {
            if (Character.isSurrogate(c)) json.append(String.format("\\u%04x", (int) c));
            else json.append(c);
        }
        browser.devTools(method, json.toString()).exceptionally(error -> {
            onRender(() -> { if (browser == source) ScreenInput.closing(this); });
            com.mojang.logging.LogUtils.getLogger().warn("Screen input failed", error);
            return null;
        });
    }

    /** Runs one DevTools method on the page. */
    CompletableFuture<String> devTools(String method, String params) {
        requirePage();
        if (picker != null) throw new ScreenTools.ScreenError("view_only", "The file picker is view-only. Use screen_screenshot to see it.");
        if (browser == null) throw new ScreenTools.ScreenError("unavailable", "Open a page on the screen first.");
        if (browser.builtin) throw new ScreenTools.ScreenError("view_only", "Built-in pages are view-only. Use screen_screenshot to see them.");
        ScreenTools.refuseUnsafe(method, com.google.gson.JsonParser.parseString(params));
        agentInput();
        var result = track(new CompletableFuture<String>());
        commands.add(result);
        result.whenComplete((value, failure) -> commands.remove(result));
        browser.devTools(method, params).whenComplete((value, failure) -> {
            if (failure != null) result.completeExceptionally(failure);
            else result.complete(value);
        });
        return result;
    }

    /** The page's last painted frame, exactly as painted. Owned by the caller. */
    NativeImage frame() { requirePage(); return picker != null ? picker.read() : page.read(); }

    CompletableFuture<Void> readyPage() {
        requirePage();
        if (url.isBlank()) return CompletableFuture.failedFuture(new ScreenTools.ScreenError("unavailable", "Open a page on the screen first."));
        if (picker != null ? picker.painted() : browser != null && pageReady) return CompletableFuture.completedFuture(null);
        if (waking == null || waking.isDone()) waking = track(new CompletableFuture<Void>());
        return waking;
    }

    CompletableFuture<Void> nextFrame() {
        requirePage();
        var ready = track(new CompletableFuture<Void>());
        frames.add(ready);
        ready.whenComplete((done, failure) -> frames.remove(ready));
        setLive(true);
        if (browser != null) browser.repaint();
        return ready;
    }

    void paintedFrame() { for (var ready : Set.copyOf(frames)) ready.complete(null); }

    private void requirePage() {
        if (crashed) throw new ScreenTools.ScreenError("unavailable", "The page crashed. Reload or open a page before using it again.");
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> call) {
        pending.add(call);
        call.whenComplete((value, failure) -> pending.remove(call));
        return call;
    }

    int pendingCalls() { return pending.size(); }

    /** Distant or unseen screens keep their last frame; their pages tick over slowly. */
    void setLive(boolean value) {
        value = value || !frames.isEmpty();
        if (live == value) return;
        live = value;
        if (browser == null) return;
        FileWatches.live(access, value);
        if (!value) FilePages.sleeping(access);
        browser.setWindowlessFrameRate(value ? Chromium.FPS : 1);
        if (value) browser.repaint();
    }

    boolean live() { return live; }
    boolean painted() { return picker != null ? picker.painted() : page.width > 0; }
    boolean popupVisible() { return popupShown && popupBounds != null && popup.width > 0; }
    ResourceLocation pageTexture() { return page.location; }
    ResourceLocation popupTexture() { return popup.location; }

    void close() {
        ScreenInput.closing(this);
        cancelGrant();
        var current = browser;
        var old = departing;
        browser = null;
        departing = null;
        closePicker();
        setAccess(null);
        fileGrant = null;
        grantAttempt = "";
        fileError = "";
        drop(behind);
        drop(ahead);
        pageReady = false;
        generation++;
        opening = null;
        title = "";
        page.release();
        popup.release();
        var failure = new ScreenTools.ScreenError("unavailable", "The screen's page was closed.");
        for (var call : pending) call.completeExceptionally(failure);
        // Native close completes raw DevTools futures synchronously; screen calls must fail first.
        if (old != null) Chromium.close(old);
        if (current != null) Chromium.close(current);
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
            if (source == browser) { target.upload(dirty, frame, width, height); if (!isPopup) paintedFrame(); }
            return;
        }
        // The frame is only valid during this call, so copy it before handing it over.
        var copy = MemoryUtil.memAlloc(width * height * 4);
        MemoryUtil.memCopy(MemoryUtil.memAddress(frame), MemoryUtil.memAddress(copy), (long) width * height * 4);
        Minecraft.getInstance().execute(() -> {
            if (source == browser) {
                target.upload(new Rectangle[] {new Rectangle(0, 0, width, height)}, copy, width, height);
                if (!isPopup) paintedFrame();
            }
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

    void loading(ScreenBrowser source, boolean value) {
        onRender(() -> {
            if (source != browser || crashed) return;
            loading = value;
            readHistory(source);
            if (value) return;
            playerNavigating = false;
            finishOpening();
        });
    }

    /** Once the requested navigation begins, a redirect may legitimately return to the previous page. */
    void browsing(ScreenBrowser source, String address) {
        onRender(() -> {
            if (source == browser && opening != null && !opening.isDone() && trimSlash(address).equals(trimSlash(url)))
                superseded = "";
        });
    }

    /** Stopping loading alone can mean the renderer died. Require the main frame to finish too. */
    void loadFinished(ScreenBrowser source, String address) {
        onRender(() -> {
            if (source == browser && !crashed) {
                pageReady = true;
                readHistory(source);
                if (waking != null) waking.complete(null);
            }
            if (source != browser || crashed || !openingStarted) return;
            String loaded = trimSlash(address);
            if (!loaded.equals(trimSlash(url)) && loaded.equals(trimSlash(superseded))) return;
            openingFinished = true;
            finishOpening();
        });
    }

    private void finishOpening() {
        if (loading || !openingFinished || opening == null || opening.isDone()) return;
        opening.complete(null);
        opening = null;
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
            if (loaded.equals(trimSlash(url)) || !loaded.equals(trimSlash(superseded))) {
                openingStarted = true;
                openingFinished = false;
            }
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
            if (source != browser) return;
            readHistory(source);
            if (address.equals(url) || Chromium.place(address) == Chromium.Place.NOWHERE) return;
            url = address;
            // Following a link the player clicked takes the page over; redirects and scripted changes don't.
            Screens.saveUrl(this, address, !source.builtin && (playerNavigating || recentInput()));
            playerNavigating = false;
        });
    }

    /** Same-page navigations skip the gesture check, so a click just before still counts. */
    boolean recentInput() { return playerInput && System.nanoTime() - inputAt < 1_000_000_000L; }

    void crashed(ScreenBrowser source) {
        onRender(() -> {
            if (source != browser || crashed) return;
            crashed = true;
            FilePages.cancel(access);
            generation++;
            loading = editable = popupShown = playerNavigating = false;
            popupBounds = null;
            cursor = 0;
            ScreenInput.forget(this);
            opening = null;
            var failure = new ScreenTools.ScreenError("unavailable",
                "The page crashed. The outcome is unknown; check the page before repeating any action.");
            for (var call : pending) call.completeExceptionally(failure);
        });
    }

    void editing(ScreenBrowser source, boolean value) {
        onRender(() -> {
            if (source != browser || crashed) return;
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
