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
    private static final int TEXT = 0xFFE6E8EB, STATUS = 0xFF9AA0A9, DIM = 0xFF6B717B;
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
        pose.popPose();
    }

    /** The thin black panel behind the page: its bezel and edges, lit like the world around it. */
    private static void panel(MultiBufferSource buffers, Matrix4f m, ScreenView view, int light) {
        double margin = ScreenView.MARGIN, x0 = -margin, x1 = view.pageWidth + margin;
        double y0 = -view.barHeight - margin, y1 = view.pageHeight + margin, front = 0.002, back = front + ScreenEntity.DEPTH;
        var out = buffers.getBuffer(RenderType.textBackground());
        double[][] faces = {
            {x0, y0, front, x0, y1, front, x1, y1, front, x1, y0, front},
            {x0, y0, front, x1, y0, front, x1, y0, back, x0, y0, back},
            {x0, y1, front, x0, y1, back, x1, y1, back, x1, y1, front},
            {x0, y0, front, x0, y0, back, x0, y1, back, x0, y1, front},
            {x1, y0, front, x1, y1, front, x1, y1, back, x1, y0, back}};
        for (var f : faces) {
            // Edges are drawn both ways round so they show whichever way culling sees them.
            for (int i = 0; i < 12; i += 3) out.addVertex(m, (float) f[i], (float) f[i + 1], (float) f[i + 2]).setColor(PANEL).setLight(light);
            if (f != faces[0]) for (int i = 9; i >= 0; i -= 3) out.addVertex(m, (float) f[i], (float) f[i + 1], (float) f[i + 2]).setColor(PANEL).setLight(light);
        }
    }

    private static String status(ScreenView view, boolean trusted) {
        if (view.url.isBlank()) return ScreenInput.editingAddress(view) ? null : "Click to enter an address";
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
        double w = view.pageWidth, bar = view.barHeight, pad = bar * 0.16;
        rect(buffers, matrix, 0, 0, w, bar, -0.002, BAR);
        double glyph = bar * 0.5;
        String[] buttons = {"←", "→", "⟳"};
        boolean[] enabled = {view.canGoBack, view.canGoForward, view.browser != null};
        for (int i = 0; i < 3; i++)
            text(pose, buffers, buttons[i], bar * (i + 0.5), (bar - glyph) / 2, glyph, enabled[i] ? TEXT : DIM, true, -0.003);

        double fieldX = bar * 3, fieldW = w - fieldX - pad;
        if (fieldW > 0) field(pose, buffers, view, fieldX, fieldW, bar, pad);
        pose.popPose();
    }

    private static void field(PoseStack pose, MultiBufferSource buffers, ScreenView view, double fieldX, double fieldW, double bar, double pad) {
        var matrix = pose.last().pose();
        rect(buffers, matrix, fieldX, pad, fieldX + fieldW, bar - pad, -0.003, FIELD);
        double size = bar * 0.42, inset = pad * 1.5, textY = (bar - size) / 2;
        var font = Minecraft.getInstance().font;
        int room = (int) ((fieldW - 2 * inset) / (size / 9));
        String editing = ScreenInput.addressText(view);
        if (editing != null) {
            String shown = font.plainSubstrByWidth(editing, room, true);
            double end = fieldX + inset + font.width(shown) * size / 9;
            if (ScreenInput.addressSelected() && !shown.isEmpty())
                rect(buffers, matrix, fieldX + inset, textY - size * 0.1, end, textY + size * 1.05, -0.004, SELECTION);
            text(pose, buffers, shown, fieldX + inset, textY, size, TEXT, false, -0.005);
            if (Util.getMillis() / 530 % 2 == 0) rect(buffers, matrix, end + size * 0.05, textY - size * 0.1, end + size * 0.15, textY + size * 1.05, -0.005, TEXT);
        } else {
            boolean empty = view.url.isBlank();
            String shown = font.plainSubstrByWidth(empty ? "Enter an address" : view.url, room);
            text(pose, buffers, shown, fieldX + inset, textY, size, empty ? DIM : TEXT, false, -0.004);
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
