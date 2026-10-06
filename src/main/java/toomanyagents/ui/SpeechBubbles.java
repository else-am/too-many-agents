package toomanyagents.ui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.EntityAttachment;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;

/** A speech bubble above each embodied agent's name badge, showing the speech the plugin publishes. */
public final class SpeechBubbles {
    private static final int WRAP = 160, MAX_LINES = 6, PAD_X = 5, PAD_Y = 4, LINE = 10;
    // The bubble sits just above the agent's head, below its overhead card.
    // Same scale as the overhead card, so the text matches the card's name.
    private static final float UNIT = 0.025F * OverheadAgentCards.SCALE;
    private static final int BOTTOM = 12;
    // Ink and border on the same translucent black as the overhead cards.
    private static final int INK = 0xFFFFFFFF, BORDER = 0xFFFFFFFF;

    private record Bubble(List<String> lines, boolean dots) {}

    private final Supplier<? extends AgentUiAccess> access;
    private Map<UUID, Bubble> bubbles = Map.of();
    private Map<String, Bubble> layouts = Map.of();

    public SpeechBubbles(Supplier<? extends AgentUiAccess> access) {
        this.access = access;
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
    }

    /** Snapshot once per tick so rendering never touches agent state. */
    private void tick(ClientTickEvent.Post event) {
        var current = access.get();
        var next = new HashMap<UUID, Bubble>();
        var nextLayouts = new HashMap<String, Bubble>();
        if (Minecraft.getInstance().level != null && current != null)
            for (var item : current.worldAgents()) {
                var agent = item.getAsJsonObject();
                var speech = AgentModels.object(agent, "speech");
                String kind = AgentModels.text(speech, "kind"), text = AgentModels.text(speech, "text");
                String body = AgentModels.text(AgentModels.object(agent, "body"), "entityUuid");
                if (kind.isBlank() || body.isBlank() || AgentModels.text(agent, "status").equals("disconnected")) continue;
                String key = kind + "\n" + text;
                var bubble = layouts.containsKey(key) ? layouts.get(key) : layout(kind, text);
                nextLayouts.put(key, bubble);
                try { next.put(UUID.fromString(body), bubble); } catch (IllegalArgumentException ignored) {}
            }
        bubbles = next;
        layouts = nextLayouts;
    }

    private static Bubble layout(String kind, String text) {
        boolean dots = kind.equals("working");
        if (text.isBlank() && kind.equals("approval")) text = "Approval needed";
        if (text.isBlank() && kind.equals("question")) text = "Question";
        return new Bubble(text.isBlank() ? List.of() : wrap(text, dots ? WRAP - dotsWidth() : WRAP), dots);
    }

    private static List<String> wrap(String text, int width) {
        var font = Minecraft.getInstance().font;
        var split = font.getSplitter().splitLines(text, width, Style.EMPTY).stream().map(FormattedText::getString).map(String::strip).toList();
        var lines = new ArrayList<>(split.subList(0, Math.min(MAX_LINES, split.size())));
        if (split.size() > MAX_LINES) {
            int last = lines.size() - 1;
            lines.set(last, font.plainSubstrByWidth(lines.get(last), width - font.width("…")).stripTrailing() + "…");
        }
        return lines;
    }

    private static int height(Bubble bubble) {
        return Math.max(1, bubble.lines().size()) * LINE - 1 + 2 * PAD_Y;
    }

    /** How far, in blocks, the overhead card rises to sit above this agent's bubble. */
    float lift(UUID entity) {
        var bubble = bubbles.get(entity);
        // The card's bottom edge starts about level with the bubble's bottom; clear the bubble plus a small gap.
        return bubble == null ? 0 : (height(bubble) + 2) * UNIT;
    }

    private static int dotsWidth() { return Minecraft.getInstance().font.width("..."); }

    private void render(RenderNameTagEvent event) {
        var client = Minecraft.getInstance();
        var entity = event.getEntity();
        var bubble = bubbles.get(entity.getUUID());
        if (bubble == null || event.canRender().isFalse() || !Minecraft.renderNames()) return;
        if (client.screen instanceof AgentInventoryScreen inventory && inventory.isRenderingPreview()) return;
        var dispatcher = client.getEntityRenderDispatcher();
        if (!ClientHooks.isNameplateInRenderDistance(entity, dispatcher.distanceToSqr(entity))) return;
        var anchor = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(event.getPartialTick()));
        if (anchor == null) return;

        var font = client.font;
        var pose = event.getPoseStack();
        pose.pushPose();
        // The same frame as the vanilla name badge, so the bubble sits just above it.
        pose.translate(anchor.x, anchor.y + 0.5, anchor.z);
        pose.mulPose(dispatcher.cameraOrientation());
        pose.scale(UNIT, -UNIT, UNIT);
        Matrix4f matrix = pose.last().pose();
        // World-lit like the overhead cards and vanilla name tags, so the whites match.
        int light = event.getPackedLight();

        int width = 0;
        for (int i = 0; i < bubble.lines().size(); i++)
            width = Math.max(width, font.width(bubble.lines().get(i)) + (bubble.dots() && i == bubble.lines().size() - 1 ? dotsWidth() : 0));
        if (bubble.lines().isEmpty() && bubble.dots()) width = Math.max(width, font.width("..."));
        int x0 = -width / 2 - PAD_X, x1 = x0 + width + 2 * PAD_X;
        int y1 = BOTTOM, y0 = y1 - height(bubble);

        var fill = event.getMultiBufferSource().getBuffer(RenderType.textBackground());
        quad(matrix, fill, x0, y0, x1, y1, AgentCard.background(), light);
        // Border strips skip the corner pixels, which rounds the bubble.
        quad(matrix, fill, x0, y0 - 1, x1, y0, BORDER, light);
        quad(matrix, fill, x0, y1, x1, y1 + 1, BORDER, light);
        quad(matrix, fill, x0 - 1, y0, x0, y1, BORDER, light);
        quad(matrix, fill, x1, y0, x1 + 1, y1, BORDER, light);
        // A stepped pixel tail pointing down at the name badge.
        quad(matrix, fill, -4, y1 + 1, 2, y1 + 3, BORDER, light);
        quad(matrix, fill, -4, y1 + 3, 0, y1 + 5, BORDER, light);
        quad(matrix, fill, -4, y1 + 5, -2, y1 + 7, BORDER, light);

        var buffers = event.getMultiBufferSource();
        int y = y0 + PAD_Y;
        int x = x0 + PAD_X;
        for (var line : bubble.lines()) { text(font, line, x0 + PAD_X, y, INK, matrix, buffers, light); x = x0 + PAD_X + font.width(line); y += LINE; }
        if (bubble.dots()) {
            // "" -> . -> .. -> ... directly after the text; the bubble keeps the full width so it does not jitter.
            String dots = ".".repeat((int) (System.currentTimeMillis() / 400 % 4));
            if (bubble.lines().isEmpty()) text(font, dots, x0 + PAD_X, y, INK, matrix, buffers, light);
            else text(font, dots, x, y - LINE, INK, matrix, buffers, light);
        }
        pose.popPose();
    }

    private static void text(Font font, String text, int x, int y, int color, Matrix4f matrix, net.minecraft.client.renderer.MultiBufferSource buffers, int light) {
        font.drawInBatch(text, x, y, color, false, matrix, buffers, Font.DisplayMode.NORMAL, 0, light);
    }

    /** A flat quad just behind the text plane (+z faces the camera here). */
    private static void quad(Matrix4f matrix, VertexConsumer out, float x0, float y0, float x1, float y1, int argb, int light) {
        out.addVertex(matrix, x0, y1, -0.01F).setColor(argb).setLight(light);
        out.addVertex(matrix, x1, y1, -0.01F).setColor(argb).setLight(light);
        out.addVertex(matrix, x1, y0, -0.01F).setColor(argb).setLight(light);
        out.addVertex(matrix, x0, y0, -0.01F).setColor(argb).setLight(light);
    }
}
