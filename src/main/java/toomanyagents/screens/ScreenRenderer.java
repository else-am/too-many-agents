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
    private static final int PANEL = 0xFF08090B, SURFACE = 0xFF0E1013, BAR = 0xFF15171B, FIELD = 0xFF24272D, SELECTION = 0xFF35618F;
    private static final int TEXT = 0xFFE6E8EB, STATUS = 0xFF9AA0A9, DIM = 0xFF6B717B, ID = 0xFFE2D4A7, MENU = 0xFF1C1F24, WARN = 0xFFE8A27C;
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
        Vec3 r = view.rightVector, n = view.normal;
        pose.mulPose(new Matrix4f((float) r.x, (float) r.y, (float) r.z, 0, 0, -1, 0, 0, (float) -n.x, (float) -n.y, (float) -n.z, 0, 0, 0, 0, 1));
        var matrix = pose.last().pose();
        double w = view.pageWidth, h = view.pageHeight;
        boolean trusted = Trust.allowedHere();
        panel(buffers, matrix, view, light);

        if (trusted && view.painted() && view.browser != null && !view.crashed) texture(buffers, matrix, view.pageTexture(), 0, 0, w, h, 0);
        else rect(buffers, matrix, 0, 0, w, h, 0, SURFACE);
        if (trusted && view.popupVisible()) {
            var b = view.popupBounds;
            double sx = w / view.pixelWidth, sy = h / view.pixelHeight;
            texture(buffers, matrix, view.popupTexture(), b.x * sx, b.y * sy, (b.x + b.width) * sx, (b.y + b.height) * sy, -0.001);
        }

        String status = status(view, trusted);
        if (status != null) {
            // Scales with the screen, narrowed to fit its width.
            double size = Math.min(Math.clamp(h * 0.05, 0.08, 0.2), w * 0.8 * 9 / Minecraft.getInstance().font.width(status));
            text(pose, buffers, status, w / 2, h / 2 - size / 2, size, STATUS, true, -0.002);
        }
        bar(pose, buffers, view);
        if (ScreenInput.menuOpen(view)) menu(pose, buffers, view);
        pose.popPose();
    }

    /** The thin black panel behind the page: its bezel, edges and back, lit like the world around it. */
    private static void panel(MultiBufferSource buffers, Matrix4f m, ScreenView view, int light) {
        double margin = ScreenView.MARGIN, x0 = -margin, x1 = view.pageWidth + margin;
        double y0 = -view.barHeight - margin, y1 = view.pageHeight + margin, front = 0.002, back = front + ScreenEntity.DEPTH;
        var out = buffers.getBuffer(RenderType.textBackground());
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
            for (int i = 0; i < 12; i += 3) out.addVertex(m, (float) f[i], (float) f[i + 1], (float) f[i + 2]).setColor(PANEL).setLight(light);
            if (f != faces[0]) for (int i = 9; i >= 0; i -= 3) out.addVertex(m, (float) f[i], (float) f[i + 1], (float) f[i + 2]).setColor(PANEL).setLight(light);
        }
    }

    private static String status(ScreenView view, boolean trusted) {
        if (view.url.isBlank()) return ScreenInput.fieldText(view, ScreenView.Part.ADDRESS) != null ? null : "Click to enter an address";
        if (!trusted) return "Click to load pages in this world";
        if (view.crashed) return "Page crashed · click to reload";
        if (Chromium.phase() == Chromium.Phase.FAILED) return "The browser failed to start";
        if (Chromium.phase() != Chromium.Phase.READY) return Chromium.progress();
        if (!view.painted()) return "Loading";
        return null;
    }

    private static void bar(PoseStack pose, MultiBufferSource buffers, ScreenView view) {
        pose.pushPose();
        pose.translate(0, -view.barHeight, 0);
        var matrix = pose.last().pose();
        double bar = view.barHeight, pad = bar * 0.16;
        rect(buffers, matrix, 0, 0, view.pageWidth, bar, -0.002, BAR);
        var font = Minecraft.getInstance().font;

        // The screen's id, then its name: click to rename.
        var record = view.screen.record();
        double nameX = view.start(ScreenView.Part.NAME), nameW = view.end(ScreenView.Part.NAME) - nameX;
        String naming = ScreenInput.fieldText(view, ScreenView.Part.NAME);
        if (naming != null) field(pose, buffers, nameX + pad, nameW - pad, bar, pad, naming, "", DIM);
        else {
            double size = bar * 0.42, y = (bar - size) / 2, x = nameX + pad * 1.5;
            int room = (int) ((nameW - pad * 2.5) / (size / 9));
            text(pose, buffers, record.id(), x, y, size, ID, false, -0.003);
            int idWidth = font.width(record.id() + " ");
            // While an agent holds the screen's lease, its name follows the screen's.
            String holder = view.screen.holder(), label = holder.isEmpty() ? record.name() : record.name().isEmpty() ? holder : record.name() + " · " + holder;
            if (!label.isEmpty() && room > idWidth)
                text(pose, buffers, font.plainSubstrByWidth(label, room - idWidth), x + idWidth * size / 9, y, size, TEXT, false, -0.003);
        }

        double glyph = Math.min(bar * 0.5, view.unit * 0.7);
        var buttons = new ScreenView.Part[] {ScreenView.Part.BACK, ScreenView.Part.FORWARD, ScreenView.Part.RELOAD};
        String[] glyphs = {"←", "→", "⟳"};
        boolean[] enabled = {view.canGoBack, view.canGoForward, view.browser != null};
        for (int i = 0; i < 3; i++) {
            double center = (view.start(buttons[i]) + view.end(buttons[i])) / 2;
            text(pose, buffers, glyphs[i], center, (bar - glyph) / 2, glyph, enabled[i] ? TEXT : DIM, true, -0.003);
        }

        double addressX = view.start(ScreenView.Part.ADDRESS), addressW = view.end(ScreenView.Part.ADDRESS) - addressX;
        String typed = ScreenInput.fieldText(view, ScreenView.Part.ADDRESS);
        if (view.url.isBlank()) field(pose, buffers, addressX, addressW, bar, pad, typed, "Enter an address", DIM);
        else field(pose, buffers, addressX, addressW, bar, pad, typed, view.url, TEXT);
        // ⋯ opens the menu.
        double cx = (view.start(ScreenView.Part.MENU) + view.end(ScreenView.Part.MENU)) / 2, dot = Math.min(bar, view.unit) * 0.09;
        for (int i = -1; i <= 1; i++)
            rect(buffers, matrix, cx + i * dot * 2.6 - dot / 2, bar / 2 - dot / 2, cx + i * dot * 2.6 + dot / 2, bar / 2 + dot / 2, -0.003,
                ScreenInput.menuOpen(view) ? ID : TEXT);
        pose.popPose();
    }

    /** The open menu, hanging from the bar's right end over the page: an icon and a label per row. */
    private static void menu(PoseStack pose, MultiBufferSource buffers, ScreenView view) {
        var m = pose.last().pose();
        var font = Minecraft.getInstance().font;
        double x0 = view.menuX, row = view.menuRow, width = view.menuWidth, z = -0.006;
        var actions = ScreenView.Action.values();
        rect(buffers, m, x0, 0, x0 + width, row * actions.length, z, MENU);
        var hovered = ScreenInput.menuHovered(view);
        boolean confirming = ScreenInput.confirmingReset();
        // One text size for every row, narrowed so the longest label fits.
        double icon = row * 0.36, labelX = row * 0.95, room = width - labelX - row * 0.25;
        int longest = 0;
        for (var action : actions) longest = Math.max(longest, font.width(action.label));
        double size = Math.min(row * 0.38, room * 9 / longest);
        for (var action : actions) {
            double top = action.ordinal() * row, cx = x0 + row * 0.5, cy = top + row / 2;
            if (action == hovered) rect(buffers, m, x0, top, x0 + width, top + row, z - 0.001, FIELD);
            boolean warn = action == ScreenView.Action.RESET && confirming;
            int color = warn ? WARN : TEXT;
            switch (action) {
                case OFF -> power(buffers, m, cx, cy, icon, color);
                case PICKUP -> pickUp(buffers, m, cx, cy, icon, color);
                case RESET -> reset(buffers, m, cx, cy, icon, color);
            }
            String label = warn ? "Click again to reset" : action.label;
            text(pose, buffers, font.plainSubstrByWidth(label, (int) (room * 9 / size)), x0 + labelX, cy - size / 2, size, color, false, z - 0.002);
        }
    }

    /** A bar text field: the text being typed with its caret, or else what it shows at rest. */
    private static void field(PoseStack pose, MultiBufferSource buffers, double fieldX, double fieldW, double bar, double pad, String editing, String resting, int restingColor) {
        var matrix = pose.last().pose();
        rect(buffers, matrix, fieldX, pad, fieldX + fieldW, bar - pad, -0.003, FIELD);
        double size = bar * 0.42, inset = pad * 1.5, textY = (bar - size) / 2;
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

    /** The power symbol: a ring open at the top with a stroke through the gap. */
    private static void power(MultiBufferSource buffers, Matrix4f m, double cx, double cy, double r, int color) {
        double t = r * 0.28, z = -0.008;
        // Angles run clockwise from the right, since y points down; -90° is straight up.
        arc(buffers, m, cx, cy, r, t, -55, 235, z, color);
        rect(buffers, m, cx - t / 2, cy - r * 1.1, cx + t / 2, cy + r * 0.1, z, color);
    }

    /** A circular arrow: most of a ring, with its head at the end. */
    private static void reset(MultiBufferSource buffers, Matrix4f m, double cx, double cy, double r, int color) {
        double t = r * 0.28, z = -0.008, end = Math.toRadians(250);
        arc(buffers, m, cx, cy, r, t, -30, 250, z, color);
        // The head points along the ring at its end: d is the way round, n points outward.
        double px = cx + (r - t / 2) * Math.cos(end), py = cy + (r - t / 2) * Math.sin(end);
        double dx = -Math.sin(end), dy = Math.cos(end), nx = Math.cos(end), ny = Math.sin(end), w = t * 1.8, h = t * 2.2;
        quad(buffers, m, new double[] {px + nx * w, py + ny * w, px - nx * w, py - ny * w, px + dx * h, py + dy * h, px + dx * h, py + dy * h}, z, color);
    }

    /** A ring's stroke from one angle to another, in degrees, t thick inside radius r. */
    private static void arc(MultiBufferSource buffers, Matrix4f m, double cx, double cy, double r, double t, double from, double to, double z, int color) {
        int steps = 24;
        for (int i = 0; i < steps; i++) {
            double a = Math.toRadians(from + (to - from) * i / steps), b = Math.toRadians(from + (to - from) * (i + 1) / steps), in = r - t;
            quad(buffers, m, new double[] {cx + r * Math.cos(a), cy + r * Math.sin(a), cx + in * Math.cos(a), cy + in * Math.sin(a),
                cx + in * Math.cos(b), cy + in * Math.sin(b), cx + r * Math.cos(b), cy + r * Math.sin(b)}, z, color);
        }
    }

    /** Any four corners, drawn both ways round so their order doesn't matter. */
    private static void quad(MultiBufferSource buffers, Matrix4f m, double[] xy, double z, int color) {
        var out = buffers.getBuffer(RenderType.textBackground());
        for (int i = 0; i < 8; i += 2) out.addVertex(m, (float) xy[i], (float) xy[i + 1], (float) z).setColor(color).setLight(LIGHT);
        for (int i = 6; i >= 0; i -= 2) out.addVertex(m, (float) xy[i], (float) xy[i + 1], (float) z).setColor(color).setLight(LIGHT);
    }

    /** An arrow rising out of a tray. */
    private static void pickUp(MultiBufferSource buffers, Matrix4f m, double cx, double cy, double r, int color) {
        double t = r * 0.28, z = -0.008;
        rect(buffers, m, cx - r, cy + r - t, cx + r, cy + r, z, color);
        rect(buffers, m, cx - r, cy + r * 0.3, cx - r + t, cy + r, z, color);
        rect(buffers, m, cx + r - t, cy + r * 0.3, cx + r, cy + r, z, color);
        rect(buffers, m, cx - t / 2, cy - r * 0.7, cx + t / 2, cy + r * 0.5, z, color);
        // The head, in steps narrowing to its point.
        for (int i = 0; i < 3; i++) {
            double half = r * (0.55 - i * 0.2), y = cy - r * 0.55 - i * t;
            rect(buffers, m, cx - half, y - t, cx + half, y, z, color);
        }
    }

    private static void texture(MultiBufferSource buffers, Matrix4f m, ResourceLocation texture, double x0, double y0, double x1, double y1, double z) {
        var out = buffers.getBuffer(PAGE.apply(texture));
        // Counter-clockwise as seen from the front.
        out.addVertex(m, (float) x0, (float) y0, (float) z).setColor(-1).setUv(0, 0).setLight(LIGHT);
        out.addVertex(m, (float) x0, (float) y1, (float) z).setColor(-1).setUv(0, 1).setLight(LIGHT);
        out.addVertex(m, (float) x1, (float) y1, (float) z).setColor(-1).setUv(1, 1).setLight(LIGHT);
        out.addVertex(m, (float) x1, (float) y0, (float) z).setColor(-1).setUv(1, 0).setLight(LIGHT);
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
