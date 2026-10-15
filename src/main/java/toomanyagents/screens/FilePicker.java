package toomanyagents.screens;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The page a screen shows for a files location ending in ?pick: Back and the folder's path, the folder's contents with
 * folders first, then Show hidden files and Open this folder. It lists only the folder its pinned human grant names;
 * every choice goes back through the view's native grant and history. Drawn with Minecraft's font into one texture that
 * both the panel and screenshots use, redrawn only when what it shows changes. Render thread only, except listing.
 */
final class FilePicker {
    /** The most names one folder lists; the rest are reached by typing a path. */
    static final int MAX_NAMES = 50_000;
    /** The path field's longest text, which keeps measuring it cheap. */
    private static final int MAX_FIELD = 4096;
    private static final long REFRESH_NANOS = 2_000_000_000L, TYPE_AHEAD_NANOS = 1_000_000_000L;
    private static final String HIDDEN_LABEL = "Show hidden files", OPEN_LABEL = "Open this folder", GONE = "This folder no longer exists.";
    /** Sizes in the picker's own units, each scale() pixels of the page. Text is the font's 9 units tall. */
    private static final int BAR = 15, ROW = 12, MAX_WIDTH = 360, TRACK = 5;
    private static final int HOVER = 0xFF1B1E23, CHOSEN = 0xFF243447, BUTTON_HOVER = 0xFF30343B, FILE = 0xFFC3C8CF;
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 16));
    private static int nextId;

    enum Control { BACK, PATH, HIDDEN, OPEN_FOLDER }
    enum Focus { NONE, PATH, LIST }
    /** A link is one that leads outside this folder, so its kind is unknown here. */
    record Row(String name, boolean directory, boolean link) {}
    record Box(int x, int y, int width, int height) {
        boolean contains(double px, double py) { return px >= x && py >= y && px < x + width && py < y + height; }
    }
    record Layout(int scale, int width, int height, Box back, Box path, Box list, Box track, Box status, Box hidden, Box open, int rows) {
        Box control(Control control) {
            return switch (control) { case BACK -> back; case PATH -> path; case HIDDEN -> hidden; case OPEN_FOLDER -> open; };
        }
    }
    private record Listing(List<Row> rows, boolean truncated) {}
    /** Everything drawn; the texture is redrawn only when this changes. */
    private record Look(Control over, int overRow, Focus focus, boolean typing, boolean caretOn, boolean back, String fileError,
                        List<Row> shown, int scroll, int selected, String field, int caret, int anchor, boolean loading,
                        String error, String notice, boolean truncated, boolean hidden) {}

    private final ScreenView view;
    ConfinedFiles.Root root;
    String folder = "";
    boolean hidden, loading, truncated;
    private List<Row> all = List.of();
    List<Row> shown = List.of();
    String error = "", notice = "";
    int scroll, selected = -1;
    Focus focus = Focus.LIST;
    /** The path field, its caret, and the other end of its selection. */
    final StringBuilder field = new StringBuilder();
    int caret, anchor;
    private int fieldStart;
    private long loads, completions, listedAt, typedAt;
    private boolean refreshing, closed;
    private CompletableFuture<?> listing, completing;
    private String typeAhead = "";
    /** Where the scrollbar's thumb was grabbed, in units below its top, while it is dragged. */
    private double grabbed = -1;

    private final ResourceLocation location = ResourceLocation.fromNamespaceAndPath("too_many_agents", "screen/picker/" + nextId++);
    private TextureTarget target;
    private Look drawn;

    FilePicker(ScreenView view) { this.view = view; }

    /** Shows a folder the view has just granted, from its top, with the keyboard on its list. */
    void open(ConfinedFiles.Root root, boolean hidden) {
        this.root = root;
        this.hidden = hidden;
        folder = root.path();
        all = shown = List.of();
        scroll = 0;
        selected = -1;
        grabbed = -1;
        error = notice = "";
        truncated = false;
        focus = Focus.LIST;
        setField(folder.endsWith(java.io.File.separator) ? folder : folder + java.io.File.separator);
        load(false);
    }

    /** Lists the folder again, keeping the selection, scroll and focus where they still apply. */
    void reload() { load(true); }

    void close() {
        closed = true;
        loads++;
        completions++;
        if (listing != null) listing.cancel(true);
        if (completing != null) completing.cancel(true);
        releaseTexture();
    }

    private void load(boolean keep) {
        long id = ++loads;
        if (listing != null) listing.cancel(true);
        loading = !keep;
        refreshing = true;
        var granted = root;
        var task = FilePages.submit(() -> list(granted));
        listing = task;
        task.whenComplete((result, failure) -> onRender(() -> {
            if (id != loads || closed) return;
            loading = refreshing = false;
            listedAt = System.nanoTime();
            if (failure != null) {
                error = message(failure);
                // A failed refresh keeps the names already shown, unless the folder is gone.
                if (!keep || error.equals(GONE)) {
                    all = List.of();
                    truncated = false;
                }
            } else {
                error = "";
                all = result.rows();
                truncated = result.truncated();
            }
            filter();
        }));
    }

    private static Listing list(ConfinedFiles.Root root) throws IOException {
        var rows = new ArrayList<Row>();
        boolean truncated = false;
        try (var directory = ConfinedFiles.directory(root, "")) {
            while (true) {
                var batch = directory.next(1000);
                for (var entry : batch.entries()) {
                    if (rows.size() == MAX_NAMES) { truncated = true; break; }
                    rows.add(row(root, entry));
                }
                if (truncated || batch.done()) break;
            }
        }
        rows.sort(Comparator.comparing((Row row) -> !row.directory()).thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));
        return new Listing(rows, truncated);
    }

    /** A link within the folder lists as what it leads to. Others stay links, opened only if the player chooses them. */
    private static Row row(ConfinedFiles.Root root, ConfinedFiles.Entry entry) throws InterruptedIOException {
        if (entry.kind() != ConfinedFiles.Kind.LINK) return new Row(entry.name(), entry.kind() == ConfinedFiles.Kind.DIRECTORY, false);
        try { return new Row(entry.name(), ConfinedFiles.info(root, entry.name()).kind() == ConfinedFiles.Kind.DIRECTORY, false); }
        catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (IOException outside) { return new Row(entry.name(), false, true); }
    }

    /** Applies Show hidden files, keeping the chosen row if it is still shown. */
    private void filter() {
        String chosen = selected >= 0 && selected < shown.size() ? shown.get(selected).name() : null;
        shown = hidden ? all : all.stream().filter(row -> !row.name().startsWith(".")).toList();
        selected = -1;
        if (chosen != null) for (int i = 0; i < shown.size(); i++) if (shown.get(i).name().equals(chosen)) selected = i;
        scroll = Math.clamp(scroll, 0, maxScroll(layout()));
    }

    // What the player does.

    /** A press on the page, in blocks from its top-left. True when the path or list takes the keyboard. */
    boolean press(double x, double y) {
        var layout = layout();
        double[] at = units(x, y);
        view.fileError = "";
        notice = "";
        if (layout.back.contains(at[0], at[1])) view.back();
        else if (layout.path.contains(at[0], at[1])) {
            focus = Focus.PATH;
            caret = anchor = caretAt(layout, at[0]);
            return true;
        } else if (layout.hidden.contains(at[0], at[1])) toggleHidden();
        else if (layout.open.contains(at[0], at[1])) view.choose(Builtin.files(Path.of(folder)));
        else if (layout.track.contains(at[0], at[1]) && maxScroll(layout) > 0) {
            // The thumb drags; the track above or below it pages.
            var thumb = thumb(layout);
            if (at[1] < thumb.y()) scroll = Math.max(0, scroll - layout.rows);
            else if (at[1] >= thumb.y() + thumb.height()) scroll = Math.min(maxScroll(layout), scroll + layout.rows);
            else grabbed = at[1] - thumb.y();
        } else {
            int row = rowAt(layout, at[0], at[1]);
            if (row >= 0) {
                focus = Focus.LIST;
                selected = row;
                activate(row);
                return true;
            } else if (layout.list.contains(at[0], at[1])) {
                focus = Focus.LIST;
                return true;
            }
        }
        return false;
    }

    /** The held pointer moved, in blocks from the page's top-left, perhaps past its edge. */
    void drag(double x, double y) {
        if (grabbed < 0) return;
        var layout = layout();
        var thumb = thumb(layout);
        double travel = layout.list.height() - thumb.height();
        if (travel <= 0) return;
        double top = units(x, y)[1] - grabbed - layout.list.y();
        scroll = (int) Math.round(Math.clamp(top / travel, 0, 1) * maxScroll(layout));
    }

    void release() { grabbed = -1; }

    void scroll(double notches) {
        scroll = Math.clamp(scroll - Math.round(notches * 3), 0, maxScroll(layout()));
    }

    /** A key while the picker has the keyboard: the path field's editing keys, or the list's movement keys. */
    void key(int key, int mods) {
        view.fileError = "";
        if (focus == Focus.PATH) { fieldKey(key, mods); return; }
        int page = Math.max(1, layout().rows - 1);
        switch (key) {
            case GLFW.GLFW_KEY_UP -> { if (selected <= 0) focusPath(); else select(selected - 1); }
            case GLFW.GLFW_KEY_DOWN -> select(selected + 1);
            case GLFW.GLFW_KEY_PAGE_UP -> select(selected - page);
            case GLFW.GLFW_KEY_PAGE_DOWN -> select(Math.max(0, selected) + page);
            case GLFW.GLFW_KEY_HOME -> select(0);
            case GLFW.GLFW_KEY_END -> select(shown.size() - 1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { if (selected >= 0) activate(selected); }
            case GLFW.GLFW_KEY_BACKSPACE -> view.back();
            case GLFW.GLFW_KEY_TAB -> focusPath();
            default -> {}
        }
    }

    /** Typed text: into the path field, or in the list, a jump to the next name starting with what was typed. */
    void typed(int codePoint) {
        if (focus != Focus.PATH) {
            long now = System.nanoTime();
            typeAhead = (now - typedAt < TYPE_AHEAD_NANOS ? typeAhead : "") + new String(Character.toChars(codePoint));
            typedAt = now;
            for (int i = 0; i < shown.size(); i++)
                if (shown.get(i).name().regionMatches(true, 0, typeAhead, 0, typeAhead.length())) { select(i); return; }
            return;
        }
        insert(new String(Character.toChars(codePoint)));
    }

    private void fieldKey(int key, int mods) {
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        var keyboard = Minecraft.getInstance().keyboardHandler;
        if (ScreenInput.shortcut(mods)) {
            switch (key) {
                case GLFW.GLFW_KEY_A -> { anchor = 0; caret = field.length(); }
                case GLFW.GLFW_KEY_C -> keyboard.setClipboard(selection().isEmpty() ? field.toString() : selection());
                case GLFW.GLFW_KEY_X -> { keyboard.setClipboard(selection()); insert(""); }
                case GLFW.GLFW_KEY_V -> insert(keyboard.getClipboard().replaceAll("[\\r\\n]+", "").strip());
                case GLFW.GLFW_KEY_LEFT -> moveCaret(0, shift);
                case GLFW.GLFW_KEY_RIGHT -> moveCaret(field.length(), shift);
                default -> {}
            }
            return;
        }
        int low = Math.min(caret, anchor), high = Math.max(caret, anchor);
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> moveCaret(low != high && !shift ? low : step(caret, -1), shift);
            case GLFW.GLFW_KEY_RIGHT -> moveCaret(low != high && !shift ? high : step(caret, 1), shift);
            case GLFW.GLFW_KEY_HOME -> moveCaret(0, shift);
            case GLFW.GLFW_KEY_END -> moveCaret(field.length(), shift);
            case GLFW.GLFW_KEY_BACKSPACE -> { if (low == high) anchor = step(caret, -1); insert(""); }
            case GLFW.GLFW_KEY_DELETE -> { if (low == high) anchor = step(caret, 1); insert(""); }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> go();
            case GLFW.GLFW_KEY_TAB -> complete();
            case GLFW.GLFW_KEY_DOWN -> { focus = Focus.LIST; select(Math.max(selected, 0)); }
            default -> {}
        }
    }

    /** One whole code point from index, staying within the field. */
    private int step(int index, int by) {
        if (by < 0 ? index == 0 : index == field.length()) return index;
        return field.offsetByCodePoints(index, by);
    }

    private void moveCaret(int to, boolean extend) {
        caret = Math.clamp(to, 0, field.length());
        if (!extend) anchor = caret;
    }

    private String selection() { return field.substring(Math.min(caret, anchor), Math.max(caret, anchor)); }

    /** Replaces the selection with text, as much as fits. */
    private void insert(String text) {
        int low = Math.min(caret, anchor), high = Math.max(caret, anchor);
        int room = MAX_FIELD - (field.length() - (high - low));
        if (text.length() > room) text = text.substring(0, Character.isHighSurrogate(text.charAt(Math.max(0, room - 1))) ? Math.max(0, room - 1) : room);
        field.replace(low, high, text);
        caret = anchor = low + text.length();
        completions++;
        notice = "";
    }

    private void setField(String text) {
        field.setLength(0);
        field.append(text, 0, Math.min(text.length(), MAX_FIELD));
        caret = anchor = field.length();
        fieldStart = 0;
        completions++;
    }

    private void focusPath() {
        focus = Focus.PATH;
        anchor = 0;
        caret = field.length();
    }

    private void select(int index) {
        if (shown.isEmpty()) return;
        selected = Math.clamp(index, 0, shown.size() - 1);
        int rows = Math.max(1, layout().rows);
        if (selected < scroll) scroll = selected;
        else if (selected >= scroll + rows) scroll = selected - rows + 1;
    }

    private void toggleHidden() {
        hidden = !hidden;
        filter();
        view.pickerHidden(hidden);
    }

    /** A folder opens here; a file, or a link the player follows, opens through the same grant as a typed path. */
    private void activate(int index) {
        var row = shown.get(index);
        String target = Builtin.files(Path.of(folder).resolve(row.name()));
        view.choose(row.directory() || row.link() ? target + query() : target);
    }

    private String query() { return hidden ? "?pick&hidden" : "?pick"; }

    /** Opens the typed path: a folder here, a file in the viewer. */
    private void go() {
        var path = typedPath(field.toString());
        if (path == null) { notice = "Type a folder or file path."; return; }
        view.choose(Builtin.files(path) + query());
    }

    private Path typedPath(String text) {
        String value = text.strip();
        if (value.isEmpty()) return null;
        if (value.equals("~") || value.startsWith("~/") || value.startsWith("~" + java.io.File.separator))
            value = System.getProperty("user.home") + value.substring(1);
        try { return Path.of(folder).resolve(value).toAbsolutePath().normalize(); }
        catch (InvalidPathException invalid) { return null; }
    }

    /**
     * Completes the last name in the path field from that folder's listing. Hidden names complete when shown or when
     * the typed name starts with a dot. The player's own typing is the intent to look there.
     */
    private void complete() {
        String text = field.toString();
        int cut = Math.max(text.lastIndexOf('/'), java.io.File.separatorChar == '\\' ? text.lastIndexOf('\\') : -1);
        String head = text.substring(0, cut + 1), prefix = text.substring(cut + 1);
        var directory = typedPath(head.isEmpty() ? "." : head);
        if (directory == null) { notice = "Type a folder path first."; return; }
        long id = ++completions;
        boolean includeHidden = hidden || prefix.startsWith(".");
        if (completing != null) completing.cancel(true);
        notice = "Completing names";
        var task = FilePages.submit(() -> list(ConfinedFiles.grant(directory)));
        completing = task;
        task.whenComplete((result, failure) -> onRender(() -> {
            if (id != completions || closed) return;
            if (failure != null) { notice = message(failure); return; }
            String shared = null;
            Row only = null;
            int count = 0;
            for (var row : result.rows()) {
                if (!includeHidden && row.name().startsWith(".")) continue;
                if (!row.name().regionMatches(true, 0, prefix, 0, prefix.length())) continue;
                if (count++ == 0) { shared = row.name(); only = row; }
                else while (!row.name().startsWith(shared)) shared = shared.substring(0, shared.length() - 1);
            }
            if (count == 0) { notice = "No matching names"; return; }
            // A case-insensitive match with differing case may share no spelling; keep the typed name then.
            String name = shared.isEmpty() ? prefix : shared;
            setField(head + name + (count == 1 && only.directory() ? java.io.File.separator : ""));
            notice = count > 1 ? count + " matching names" : result.truncated() ? "Completion is incomplete - type more of the name" : "";
        }));
    }

    private static String message(Throwable failure) {
        var cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof NoSuchFileException) return GONE;
        if (cause instanceof AccessDeniedException) return "Permission denied.";
        if (cause instanceof TimeoutException) return "Listing this folder timed out.";
        return cause.getMessage() == null ? "Could not read this folder." : cause.getMessage();
    }

    // Layout, in the picker's units.

    /** Font pixels match the bar's text size, at a whole number of page pixels so the font stays crisp. */
    int scale() {
        double pixelsPerBlock = view.pixelHeight / view.pageHeight;
        return Math.max(1, (int) Math.round(view.barHeight * 0.4 * pixelsPerBlock / 8));
    }

    /** A centred column: Back and the path, the names, a status line, then the options, stacked when they don't fit side by side. */
    Layout layout() {
        int s = scale(), w = view.pixelWidth / s, h = view.pixelHeight / s;
        int margin = Math.clamp(h / 14, 4, 18), width = Math.min(w - 2 * margin, MAX_WIDTH), x = (w - width) / 2;
        var font = Minecraft.getInstance().font;
        int hiddenWidth = Math.min(width, 13 + font.width(HIDDEN_LABEL)), openWidth = Math.min(width, font.width(OPEN_LABEL) + 12);
        boolean stacked = hiddenWidth + openWidth + 8 > width;
        int last = h - margin - BAR, footer = stacked ? last - BAR - 2 : last;
        var back = new Box(x, margin, BAR, BAR);
        var path = new Box(x + BAR + 4, margin, width - BAR - 4, BAR);
        var hiddenBox = new Box(x, footer, hiddenWidth, BAR);
        var open = new Box(stacked ? x : x + width - openWidth, last, openWidth, BAR);
        var status = new Box(x, footer - 13, width, 11);
        int top = margin + BAR + 6;
        var list = new Box(x, top, width, Math.max(0, status.y() - 2 - top));
        var track = new Box(list.x() + list.width() - TRACK, list.y(), TRACK, list.height());
        return new Layout(s, w, h, back, path, list, track, status, hiddenBox, open, list.height() / ROW);
    }

    private int maxScroll(Layout layout) { return Math.max(0, shown.size() - layout.rows); }

    private Box thumb(Layout layout) {
        var list = layout.list;
        int height = Math.max(6, list.height() * layout.rows / Math.max(1, shown.size()));
        int y = list.y() + (list.height() - height) * scroll / Math.max(1, maxScroll(layout));
        return new Box(layout.track.x(), y, TRACK, height);
    }

    private double[] units(double x, double y) {
        int s = scale();
        return new double[] {x / view.pageWidth * view.pixelWidth / s, y / view.pageHeight * view.pixelHeight / s};
    }

    /** The shown row under a point in units, or -1. */
    private int rowAt(Layout layout, double x, double y) {
        if (!layout.list.contains(x, y) || x >= layout.track.x()) return -1;
        int row = scroll + (int) ((y - layout.list.y()) / ROW);
        return row < shown.size() && row < scroll + layout.rows ? row : -1;
    }

    /** A visible row's box, for drawing and checks. */
    Box rowBox(Layout layout, int row) {
        return new Box(layout.list.x(), layout.list.y() + (row - scroll) * ROW, layout.list.width() - TRACK - 2, ROW);
    }

    /** Converts a box in units to page blocks: left, top, width, height. */
    double[] blocks(Box box) {
        double s = scale(), sx = view.pageWidth / view.pixelWidth * s, sy = view.pageHeight / view.pixelHeight * s;
        return new double[] {box.x() * sx, box.y() * sy, box.width() * sx, box.height() * sy};
    }

    /** The player's pointer on this page, in units, or null. */
    private double[] pointer() {
        var at = ScreenInput.pagePoint(view);
        return at == null ? null : units(at[0], at[1]);
    }

    Control hovered() {
        var at = pointer();
        if (at == null) return null;
        var layout = layout();
        for (var control : Control.values()) if (layout.control(control).contains(at[0], at[1])) return control;
        return null;
    }

    int hoveredRow() {
        var at = pointer();
        return at == null ? -1 : rowAt(layout(), at[0], at[1]);
    }

    /** Whether the pointer is over something that acts when clicked. */
    boolean overAction() {
        var at = pointer();
        if (at == null) return false;
        var layout = layout();
        return hovered() != null || rowAt(layout, at[0], at[1]) >= 0 || (layout.track.contains(at[0], at[1]) && maxScroll(layout) > 0);
    }

    private int caretAt(Layout layout, double x) {
        var font = Minecraft.getInstance().font;
        double offset = x - layout.path.x() - 4, left = 0;
        for (int i = Math.min(fieldStart, field.length()); i < field.length(); ) {
            int next = field.offsetByCodePoints(i, 1);
            double width = font.width(field.substring(i, next));
            if (offset < left + width / 2) return i;
            left += width;
            i = next;
        }
        return field.length();
    }

    // Drawing.

    boolean painted() { return drawn != null && target != null; }
    ResourceLocation texture() { return location; }

    /**
     * Draws the picker into its texture when what it shows has changed, or when forced for a screenshot. Called before
     * the world renders, while the main target is bound. Folders are listed again every few seconds while watched.
     */
    boolean render(boolean force) {
        if (closed) return false;
        if (!refreshing && view.live() && System.nanoTime() - listedAt > REFRESH_NANOS) load(true);
        boolean typing = ScreenInput.pageTyping(view);
        var look = new Look(hovered(), hoveredRow(), focus, typing, typing && focus == Focus.PATH && Util.getMillis() / 530 % 2 == 0,
            view.canGoBack, view.fileError, shown, scroll, selected, field.toString(), caret, anchor, loading, error, notice, truncated, hidden);
        if (!force && look.equals(drawn) && target != null) {
            // Keep the latest list reference so an unchanged refresh is compared only once.
            drawn = look;
            return false;
        }
        var mc = Minecraft.getInstance();
        int w = view.pixelWidth, h = view.pixelHeight;
        if (target == null) {
            target = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            mc.getTextureManager().register(location, new Canvas(target.getColorTextureId()));
        }
        target.setClearColor(0, 0, 0, 0);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, w, h, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.translation(0, 0, -11000);
        RenderSystem.applyModelViewMatrix();
        try {
            var graphics = new GuiGraphics(mc, BUFFERS);
            var layout = layout();
            graphics.pose().scale(layout.scale, layout.scale, 1);
            draw(graphics, layout, look);
            graphics.flush();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            mc.getMainRenderTarget().bindWrite(true);
        }
        GlStateManager._bindTexture(target.getColorTextureId());
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        drawn = look;
        return true;
    }

    /** The last drawn picker, top row first. Owned by the caller. */
    NativeImage read() {
        var image = new NativeImage(target.width, target.height, false);
        GlStateManager._bindTexture(target.getColorTextureId());
        image.downloadTexture(0, false);
        image.flipY();
        return image;
    }

    private void releaseTexture() {
        if (target == null) return;
        Minecraft.getInstance().getTextureManager().release(location);
        target.destroyBuffers();
        target = null;
        drawn = null;
    }

    private void draw(GuiGraphics g, Layout l, Look look) {
        var font = Minecraft.getInstance().font;
        g.fill(0, 0, l.width, l.height, ScreenRenderer.SURFACE);
        var over = look.over();

        // Back walks the screen's own history, the same as the bar's.
        if (look.back() && over == Control.BACK) fill(g, l.back, BUTTON_HOVER);
        var arrow = ScreenRenderer.BAR_ICONS[0];
        int arrowColor = look.back() ? ScreenRenderer.TEXT : ScreenRenderer.DIM;
        for (int j = 0; j < arrow.length; j++) for (int i = 0; i < arrow[j].length(); i++)
            if (arrow[j].charAt(i) == '#') g.fill(l.back.x() + 3 + i, l.back.y() + 3 + j, l.back.x() + 4 + i, l.back.y() + 4 + j, arrowColor);

        // The path field: while it has the keyboard, the caret stays in view; at rest it shows the path's end.
        var p = l.path;
        fill(g, p, ScreenRenderer.FIELD);
        String text = look.field();
        int room = p.width() - 8;
        boolean editing = look.typing() && focus == Focus.PATH;
        if (editing) {
            fieldStart = Math.min(fieldStart, caret);
            if (font.width(text.substring(fieldStart, caret)) > room)
                fieldStart = caret - font.plainSubstrByWidth(text.substring(0, caret), room, true).length();
        } else fieldStart = text.length() - font.plainSubstrByWidth(text, room, true).length();
        String visible = font.plainSubstrByWidth(text.substring(fieldStart), room);
        int textX = p.x() + 4, textY = p.y() + 4, end = fieldStart + visible.length();
        int low = Math.clamp(Math.min(caret, anchor), fieldStart, end), high = Math.clamp(Math.max(caret, anchor), fieldStart, end);
        if (editing && low < high)
            g.fill(textX + font.width(text.substring(fieldStart, low)), textY - 1, textX + font.width(text.substring(fieldStart, high)), textY + 9, ScreenRenderer.SELECTION);
        g.drawString(font, visible, textX, textY, ScreenRenderer.TEXT, false);
        if (look.caretOn() && caret >= fieldStart && caret <= end) {
            int at = textX + font.width(text.substring(fieldStart, caret));
            g.fill(at, textY - 1, at + 1, textY + 9, ScreenRenderer.TEXT);
        }

        // The folder's names, left-aligned in the centred column.
        for (int row = scroll; row < shown.size() && row < scroll + l.rows; row++) {
            var box = rowBox(l, row);
            var entry = shown.get(row);
            if (row == selected) fill(g, box, look.typing() && focus == Focus.LIST ? ScreenRenderer.SELECTION : CHOSEN);
            else if (row == look.overRow()) fill(g, box, HOVER);
            String name = entry.directory() ? entry.name() + "/" : entry.link() ? entry.name() + " - link" : entry.name();
            int width = box.width() - 6;
            if (font.width(name) > width) name = font.plainSubstrByWidth(name, width - font.width("...")) + "...";
            int color = entry.directory() ? ScreenRenderer.TEXT : entry.link() ? ScreenRenderer.DIM : FILE;
            g.drawString(font, name, box.x() + 3, box.y() + 2, color, false);
        }
        if (maxScroll(l) > 0 && l.rows > 0) {
            var thumb = thumb(l);
            g.fill(thumb.x() + 2, thumb.y(), thumb.x() + thumb.width(), thumb.y() + thumb.height(), grabbed >= 0 ? ScreenRenderer.STATUS : ScreenRenderer.DIM);
        }
        String empty = loading ? "Loading" : !error.isEmpty() ? "" : all.isEmpty() ? "This folder is empty" : shown.isEmpty() ? "Only hidden files here" : "";
        if (!empty.isEmpty() && l.rows > 0) g.drawString(font, empty, l.list.x() + 3, l.list.y() + 2, ScreenRenderer.DIM, false);

        String status = !look.fileError().isEmpty() ? look.fileError() : !error.isEmpty() ? error : notice;
        int statusColor = !look.fileError().isEmpty() || !error.isEmpty() ? ScreenRenderer.WARN : ScreenRenderer.STATUS;
        if (status.isEmpty() && truncated) {
            status = "Showing the first " + String.format("%,d", MAX_NAMES) + " names - type a path to reach others";
            statusColor = ScreenRenderer.DIM;
        }
        g.drawString(font, font.plainSubstrByWidth(status, l.status.width()), l.status.x(), l.status.y() + 2, statusColor, false);

        // Show hidden files, and Open this folder.
        var box = l.hidden;
        int check = over == Control.HIDDEN ? ScreenRenderer.TEXT : ScreenRenderer.STATUS;
        int cx = box.x(), cy = box.y() + 3;
        g.fill(cx, cy, cx + 9, cy + 9, check);
        g.fill(cx + 1, cy + 1, cx + 8, cy + 8, ScreenRenderer.SURFACE);
        if (hidden) g.fill(cx + 2, cy + 2, cx + 7, cy + 7, ScreenRenderer.TEXT);
        g.drawString(font, font.plainSubstrByWidth(HIDDEN_LABEL, box.width() - 13), cx + 13, box.y() + 4, check, false);
        var open = l.open;
        fill(g, open, over == Control.OPEN_FOLDER ? BUTTON_HOVER : ScreenRenderer.FIELD);
        g.drawString(font, font.plainSubstrByWidth(OPEN_LABEL, open.width() - 12), open.x() + 6, open.y() + 4, ScreenRenderer.TEXT, false);
    }

    private static void fill(GuiGraphics g, Box box, int color) { g.fill(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), color); }

    private static void onRender(Runnable task) {
        if (RenderSystem.isOnRenderThread()) task.run();
        else Minecraft.getInstance().execute(task);
    }

    /** Lets the panel's render type sample the target's colour texture, which the target owns and deletes. */
    private static final class Canvas extends AbstractTexture {
        Canvas(int id) { this.id = id; }
        @Override public void load(ResourceManager manager) {}
        @Override public void releaseId() { id = -1; }
    }
}
