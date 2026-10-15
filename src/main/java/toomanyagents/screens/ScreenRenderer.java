package toomanyagents.screens;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.function.Function;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Draws a screen: its panel, the page, any open dropdown, status, and the navigation bar. */
final class ScreenRenderer extends EntityRenderer<ScreenEntity> {
    static final int PANEL = 0xFF08090B, SURFACE = 0xFF0E1013, BAR = 0xFF15171B, FIELD = 0xFF24272D, SELECTION = 0xFF35618F;
    private static final int BEZEL_HOVER = 0xFF383838, BEZEL_TYPING = 0xFF909090;
    static final int TEXT = 0xFFE6E8EB, STATUS = 0xFF9AA0A9, DIM = 0xFF6B717B, ID = 0xFFE2D4A7, MENU = 0xFF1C1F24, WARN = 0xFFE8A27C;
    private static final int LIGHT = LightTexture.FULL_BRIGHT;
    /** Like vanilla's text type, but filtered smoothly with mipmaps so distant pages don't shimmer. */
    private static final Function<ResourceLocation, RenderType> PAGE = Util.memoize(texture -> RenderType.create(
        "too_many_agents_screen_page", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS, 256, false, true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_TEXT_SHADER)
            .setTextureState(new RenderStateShard.TextureStateShard(texture, true, true))
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .createCompositeState(false)));

    ScreenRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override public ResourceLocation getTextureLocation(ScreenEntity screen) { return null; }

    @Override public void render(ScreenEntity screen, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        var view = Screens.view(screen);
        view.prepare();
        pose.pushPose();
        // Page space: x right and y down across the page in blocks from its top-left corner; -z comes toward the viewer.
        var origin = view.topLeft.subtract(screen.position());
        pose.translate(origin.x, origin.y, origin.z);
        Vec3 r = view.rightVector, n = view.normal, u = view.upVector;
        pose.mulPose(new Matrix4f(
            (float) r.x, (float) r.y, (float) r.z, 0,
            (float) -u.x, (float) -u.y, (float) -u.z, 0,
            (float) -n.x, (float) -n.y, (float) -n.z, 0,
            0, 0, 0, 1));
        var matrix = pose.last().pose();
        double w = view.pageWidth, h = view.pageHeight;
        boolean trusted = Trust.allowedHere();
        panel(buffers, matrix, view, light);

        if (trusted && view.picker != null && view.picker.painted()) texture(buffers, matrix, view.picker.texture(), 0, 0, w, h, 0, true);
        else if (trusted && view.picker == null && view.painted() && view.browser != null && !view.crashed) texture(buffers, matrix, view.pageTexture(), 0, 0, w, h, 0, false);
        else rect(buffers, matrix, 0, 0, w, h, 0, SURFACE);
        if (trusted && view.popupVisible()) {
            var b = view.popupBounds;
            double sx = w / view.pixelWidth, sy = h / view.pixelHeight;
            texture(buffers, matrix, view.popupTexture(), b.x * sx, b.y * sy, (b.x + b.width) * sx, (b.y + b.height) * sy, -0.001, false);
        }

        String status = status(view, trusted);
        if (status != null) {
            // Scales with the screen, narrowed to fit its width.
            double size = Math.min(Math.clamp(h * 0.05, 0.08, 0.2), w * 0.8 * 9 / Minecraft.getInstance().font.width(status));
            double y = h / 2 - size / 2;
            if (view.url.isBlank()) {
                // Below the blank page's buttons, kept inside the page.
                var lower = view.blankBounds(ScreenView.BlankAction.ADDRESS);
                double below = lower.y() + lower.height(), gap = lower.height() * 0.25;
                size = Math.min(size, Math.max(0, h - below - gap * 2));
                y = below + gap;
            }
            text(pose, buffers, status, w / 2, y, size, STATUS, true, -0.002);
        }
        if (view.url.isBlank()) blankButtons(pose, buffers, view);
        bar(pose, buffers, view);
        if (ScreenInput.menuOpen(view)) menu(pose, buffers, view);
        if (ScreenInput.capturedCursor(view)) {
            double sx = w / view.pixelWidth, sy = h / view.pixelHeight;
            double cx = ScreenInput.cursorX(), cy = ScreenInput.cursorY();
            CursorArt.pixels(ScreenInput.cursorShape(view), (x, y, color) -> rect(buffers, matrix,
                (cx + x * 2) * sx, (cy + y * 2) * sy, (cx + x * 2 + 2) * sx, (cy + y * 2 + 2) * sy, -0.010, color));
        }
        pose.popPose();
    }

    /** The front bezel shows input focus; the edges and back stay black and lit by the world. */
    private static void panel(MultiBufferSource buffers, Matrix4f m, ScreenView view, int light) {
        double margin = ScreenView.MARGIN, x0 = -margin, x1 = view.pageWidth + margin;
        double y0 = -margin, y1 = view.pageHeight + view.barHeight + margin, front = 0.002, back = front + ScreenEntity.DEPTH;
        var out = buffers.getBuffer(RenderType.textBackground());
        int bezel = ScreenInput.typing(view) ? BEZEL_TYPING : ScreenInput.pointing(view) ? BEZEL_HOVER : PANEL;
        double[][] faces = {
            {x0, y0, front, x0, y1, front, x1, y1, front, x1, y0, front},
            {x0, y0, front, x1, y0, front, x1, y0, back, x0, y0, back},
            {x0, y1, front, x0, y1, back, x1, y1, back, x1, y1, front},
            {x0, y0, front, x0, y0, back, x0, y1, back, x0, y1, front},
            {x1, y0, front, x1, y1, front, x1, y1, back, x1, y0, back},
            // The back, so nothing shows through from behind, e.g. a screen hung on glass.
            {x0, y0, back, x1, y0, back, x1, y1, back, x0, y1, back}};
        for (var f : faces) {
            // Edges are drawn both ways round so they show whichever way culling sees them.
            int color = f == faces[0] ? bezel : PANEL;
            int faceLight = f == faces[0] && bezel != PANEL ? LIGHT : light;
            for (int i = 0; i < 12; i += 3) out.addVertex(m, (float) f[i], (float) f[i + 1], (float) f[i + 2]).setColor(color).setLight(faceLight);
            if (f != faces[0]) for (int i = 9; i >= 0; i -= 3) out.addVertex(m, (float) f[i], (float) f[i + 1], (float) f[i + 2]).setColor(PANEL).setLight(light);
        }
    }

    private static String status(ScreenView view, boolean trusted) {
        // The picker shows its own errors and needs no browser.
        if (trusted && view.picker != null) return null;
        if (!view.fileError.isEmpty()) return view.fileError;
        if (view.url.isBlank()) return null;
        if (!trusted) return "Click to load pages in this world";
        if (view.crashed) return "Page crashed, click to reload";
        if (Chromium.phase() == Chromium.Phase.FAILED) return "The browser failed to start";
        if (Chromium.phase() != Chromium.Phase.READY) return Chromium.progress();
        if (!view.painted()) return "Loading";
        return null;
    }

    private static void blankButtons(PoseStack pose, MultiBufferSource buffers, ScreenView view) {
        var font = Minecraft.getInstance().font;
        var actions = ScreenView.BlankAction.values();
        // One text size for all buttons, narrowed so the longest label fits.
        var first = view.blankBounds(actions[0]);
        double size = first.height() * 0.36;
        for (var action : actions) size = Math.min(size, first.width() * 0.85 * 9 / font.width(blankLabel(action)));
        for (var action : actions) {
            var b = view.blankBounds(action);
            rect(buffers, pose.last().pose(), b.x(), b.y(), b.x() + b.width(), b.y() + b.height(), -0.002, FIELD);
            text(pose, buffers, blankLabel(action), b.x() + b.width() / 2, b.y() + (b.height() - size) / 2, size, TEXT, true, -0.003);
        }
    }

    private static String blankLabel(ScreenView.BlankAction action) {
        return switch (action) {
            case FILES -> "Open a file or folder";
            case TERMINAL -> "Open a terminal";
            case ADDRESS -> "Open a URL or search";
        };
    }

    private static void bar(PoseStack pose, MultiBufferSource buffers, ScreenView view) {
        pose.pushPose();
        pose.translate(0, view.pageHeight, 0);
        var matrix = pose.last().pose();
        double bar = view.barHeight, pad = bar * 0.16, size = bar * TEXT_SIZE;
        rect(buffers, matrix, 0, 0, view.pageWidth, bar, -0.002, BAR);
        var font = Minecraft.getInstance().font;

        // The screen's unique name: click to rename. Its UUID is only for agent references.
        var record = view.screen.record();
        double nameX = view.start(ScreenView.Part.NAME), nameW = view.end(ScreenView.Part.NAME) - nameX;
        String naming = ScreenInput.fieldText(view, ScreenView.Part.NAME);
        if (naming != null) field(pose, buffers, nameX + pad, nameW - pad, bar, pad, naming, "", DIM);
        else {
            double y = (bar - size) / 2, x = nameX + pad * 1.5;
            int room = (int) ((nameW - pad * 2.5) / (size / 9));
            // While an agent holds the screen's lease, its name follows the screen's.
            String holder = view.screen.holder(), label = holder.isEmpty() ? record.name() : record.name().isEmpty() ? holder : record.name() + " - " + holder;
            text(pose, buffers, font.plainSubstrByWidth(label, room), x, y, size, TEXT, false, -0.003);
        }

        // Toolbar icons are BAR_ICON pixels square, centred in their buttons.
        double pixel = Math.min(bar * 0.5, view.unit * 0.6) / BAR_ICON, iconY = (bar - BAR_ICON * pixel) / 2;
        var buttons = new ScreenView.Part[] {ScreenView.Part.BACK, ScreenView.Part.FORWARD, ScreenView.Part.RELOAD, ScreenView.Part.MENU};
        boolean[] enabled = {view.canGoBack, view.canGoForward, view.awake(), true};
        for (int i = 0; i < buttons.length; i++) {
            double center = (view.start(buttons[i]) + view.end(buttons[i])) / 2;
            int color = buttons[i] == ScreenView.Part.MENU && ScreenInput.menuOpen(view) ? ID : enabled[i] ? TEXT : DIM;
            icon(buffers, matrix, BAR_ICONS[i], center - BAR_ICON * pixel / 2, iconY, pixel, color, -0.003);
        }

        double addressX = view.start(ScreenView.Part.ADDRESS), addressW = view.end(ScreenView.Part.ADDRESS) - addressX;
        String typed = ScreenInput.fieldText(view, ScreenView.Part.ADDRESS);
        if (view.url.isBlank()) field(pose, buffers, addressX, addressW, bar, pad, typed, "Enter an address", DIM);
        else field(pose, buffers, addressX, addressW, bar, pad, typed, view.url, TEXT);
        pose.popPose();
    }

    /** The open menu, rising from the bar's right end over the page: an icon and a label per row. */
    private static void menu(PoseStack pose, MultiBufferSource buffers, ScreenView view) {
        var m = pose.last().pose();
        var font = Minecraft.getInstance().font;
        double x0 = view.menuX, row = view.menuRow, width = view.menuWidth, pad = row * 0.25, z = -0.006;
        var actions = ScreenView.Action.values();
        rect(buffers, m, x0, view.menuTop, x0 + width, view.pageHeight, z, MENU);
        var hovered = ScreenInput.menuHovered(view);
        boolean confirming = ScreenInput.confirmingReset();
        // One text size for every row, narrowed so the longest label and its icon fit. Icons are drawn in the font's
        // own pixels, ICON wide with a gap after.
        int longest = 0;
        for (var action : actions) longest = Math.max(longest, font.width(action.label));
        double size = Math.min(row * 0.38, (width - pad * 2) * 9 / (longest + ICON + 4)), pixel = size / 9;
        for (var action : actions) {
            double top = view.menuTop + action.ordinal() * row, textY = top + (row - size) / 2;
            if (action == hovered) rect(buffers, m, x0, top, x0 + width, top + row, z - 0.001, FIELD);
            boolean warn = action == ScreenView.Action.RESET && confirming;
            int color = warn ? WARN : TEXT;
            // Glyphs' capitals span font pixels 1 to 8, so the icon sits on the same rows.
            icon(buffers, m, ICONS[action.ordinal()], x0 + pad, textY + pixel, pixel, color, z - 0.002);
            String label = warn ? "Click again to reset" : action.label;
            double labelX = x0 + pad + (ICON + 4) * pixel;
            text(pose, buffers, font.plainSubstrByWidth(label, (int) ((x0 + width - pad - labelX) / pixel)), labelX, textY, size, color, false, z - 0.002);
        }
    }

    /** Bar text's capital height, as a share of the bar. */
    private static final double TEXT_SIZE = 0.4;

    /** The bar's icons as pixel art, in button order: back, forward, reload, and the menu's three dots. */
    private static final int BAR_ICON = 9;
    static final String[][] BAR_ICONS = {
        {".........",
         "....#....",
         "...#.....",
         "..#......",
         ".#######.",
         "..#......",
         "...#.....",
         "....#....",
         "........."},
        {".........",
         "....#....",
         ".....#...",
         "......#..",
         ".#######.",
         "......#..",
         ".....#...",
         "....#....",
         "........."},
        {".........",
         "...###.#.",
         "..#...##.",
         ".#...###.",
         ".#.......",
         ".#.....#.",
         "..#...#..",
         "...###...",
         "........."},
        {".........",
         ".........",
         ".........",
         ".........",
         "#...#...#",
         ".........",
         ".........",
         ".........",
         "........."}};

    /** The menu's icons as pixel art, in the menu's row order: power, an arrow rising out of a tray, a circular arrow. */
    private static final int ICON = 7;
    private static final String[][] ICONS = {
        {"...#...",
         ".#.#.#.",
         "#..#..#",
         "#.....#",
         "#.....#",
         ".#...#.",
         "..###.."},
        {"...#...",
         "..###..",
         ".#.#.#.",
         "...#...",
         "#..#..#",
         "#.....#",
         "#######"},
        {".####.#",
         "#....##",
         "#...###",
         "#......",
         "#.....#",
         ".#...#.",
         "..###.."}};

    private static void icon(MultiBufferSource buffers, Matrix4f m, String[] mask, double x, double y, double pixel, int color, double z) {
        for (int j = 0; j < mask.length; j++)
            for (int i = 0; i < mask[j].length(); i++)
                if (mask[j].charAt(i) == '#') rect(buffers, m, x + i * pixel, y + j * pixel, x + (i + 1) * pixel, y + (j + 1) * pixel, z, color);
    }

    /** A bar text field: the text being typed with its caret, or else what it shows at rest. */
    private static void field(PoseStack pose, MultiBufferSource buffers, double fieldX, double fieldW, double bar, double pad, String editing, String resting, int restingColor) {
        var matrix = pose.last().pose();
        rect(buffers, matrix, fieldX, pad, fieldX + fieldW, bar - pad, -0.003, FIELD);
        double size = bar * TEXT_SIZE, inset = pad * 1.5, textY = (bar - size) / 2;
        var font = Minecraft.getInstance().font;
        int room = Math.max(0, (int) ((fieldW - 2 * inset) / (size / 9)));
        if (editing != null) {
            String shown = font.plainSubstrByWidth(editing, room, true);
            double end = fieldX + inset + font.width(shown) * size / 9;
            if (ScreenInput.fieldSelected() && !shown.isEmpty())
                rect(buffers, matrix, fieldX + inset, textY - size * 0.1, end, textY + size * 1.05, -0.004, SELECTION);
            text(pose, buffers, shown, fieldX + inset, textY, size, TEXT, false, -0.005);
            if (Util.getMillis() / 530 % 2 == 0) rect(buffers, matrix, end + size * 0.05, textY - size * 0.1, end + size * 0.15, textY + size * 1.05, -0.005, TEXT);
        } else {
            text(pose, buffers, font.plainSubstrByWidth(resting, room), fieldX + inset, textY, size, restingColor, false, -0.004);
        }
    }

    /** flipped is for textures drawn by OpenGL, whose first row is the bottom; Chromium's frames start at the top. */
    private static void texture(MultiBufferSource buffers, Matrix4f m, ResourceLocation texture, double x0, double y0, double x1, double y1, double z, boolean flipped) {
        var out = buffers.getBuffer(PAGE.apply(texture));
        float top = flipped ? 1 : 0, bottom = 1 - top;
        // Counter-clockwise as seen from the front.
        out.addVertex(m, (float) x0, (float) y0, (float) z).setColor(-1).setUv(0, top).setLight(LIGHT);
        out.addVertex(m, (float) x0, (float) y1, (float) z).setColor(-1).setUv(0, bottom).setLight(LIGHT);
        out.addVertex(m, (float) x1, (float) y1, (float) z).setColor(-1).setUv(1, bottom).setLight(LIGHT);
        out.addVertex(m, (float) x1, (float) y0, (float) z).setColor(-1).setUv(1, top).setLight(LIGHT);
    }

    private static void rect(MultiBufferSource buffers, Matrix4f m, double x0, double y0, double x1, double y1, double z, int color) {
        var out = buffers.getBuffer(RenderType.textBackground());
        out.addVertex(m, (float) x0, (float) y0, (float) z).setColor(color).setLight(LIGHT);
        out.addVertex(m, (float) x0, (float) y1, (float) z).setColor(color).setLight(LIGHT);
        out.addVertex(m, (float) x1, (float) y1, (float) z).setColor(color).setLight(LIGHT);
        out.addVertex(m, (float) x1, (float) y0, (float) z).setColor(color).setLight(LIGHT);
    }

    /** Text whose capital letters are about size blocks tall, left-aligned or centred on x. */
    private static void text(PoseStack pose, MultiBufferSource buffers, String text, double x, double y, double size, int color, boolean centred, double z) {
        var font = Minecraft.getInstance().font;
        float scale = (float) (size / 9);
        pose.pushPose();
        pose.translate(x, y, z);
        pose.scale(scale, scale, scale);
        float left = centred ? -font.width(text) / 2f : 0;
        font.drawInBatch(text, left, 1, color, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LIGHT);
        pose.popPose();
    }

}
