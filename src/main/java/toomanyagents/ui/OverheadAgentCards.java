package toomanyagents.ui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityAttachment;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import org.joml.Matrix4f;
import toomanyagents.ProjectColor;

/** Replaces the vanilla badge for embodied agents with a compact card, raised above any speech bubble. */
public final class OverheadAgentCards {
    static final float SCALE = 2F / 3F;
    private static final int TITLE_Y = 11, MAX_WIDTH = 110;
    private static final float TITLE_SCALE = 0.65F;
    private final Supplier<? extends AgentUiAccess> access;
    private final SpeechBubbles speech;
    private Map<UUID, JsonObject> agents = Map.of();

    public OverheadAgentCards(Supplier<? extends AgentUiAccess> access, SpeechBubbles speech) {
        this.access = access;
        this.speech = speech;
        NeoForge.EVENT_BUS.addListener(this::tick);
        // Speech observes the original event first; suppress vanilla only after it has drawn.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::render);
    }

    private void tick(ClientTickEvent.Post event) {
        var next = new HashMap<UUID, JsonObject>();
        var current = access.get();
        if (Minecraft.getInstance().level != null && current != null) {
            for (var item : current.worldAgents()) {
                var agent = item.getAsJsonObject();
                String body = AgentModels.text(AgentModels.object(agent, "body"), "entityUuid");
                try { next.put(UUID.fromString(body), agent); } catch (IllegalArgumentException ignored) {}
            }
        }
        agents = next;
    }

    private void render(RenderNameTagEvent event) {
        var client = Minecraft.getInstance();
        var entity = event.getEntity();
        var agent = agents.get(entity.getUUID());
        if (agent == null) return;
        boolean hidden = event.canRender().isFalse();
        event.setCanRender(TriState.FALSE);
        if (hidden || !Minecraft.renderNames() || entity == client.getCameraEntity()
                || entity.isVehicle() || entity.isInvisibleTo(client.player)) return;
        if (client.screen instanceof AgentInventoryScreen inventory && inventory.isRenderingPreview()) return;
        var dispatcher = client.getEntityRenderDispatcher();
        if (!ClientHooks.isNameplateInRenderDistance(entity, dispatcher.distanceToSqr(entity))) return;
        var anchor = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(event.getPartialTick()));
        if (anchor == null) return;
        var pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(anchor.x, anchor.y + 0.5, anchor.z);
        pose.mulPose(dispatcher.cameraOrientation());
        pose.translate(0, speech.lift(entity.getUUID()), 0);
        pose.scale(0.025F * SCALE, -0.025F * SCALE, 0.025F * SCALE);
        var matrix = pose.last().pose();
        var buffers = event.getMultiBufferSource();
        int light = event.getPackedLight();
        boolean seeThrough = !entity.isDiscrete();

        var header = header(agent);
        String title = AgentModels.text(agent, "taskTitle").replace('\n', ' ');
        int width = Math.min(MAX_WIDTH, Math.max(header.width(), (int)Math.ceil(client.font.width(title) * TITLE_SCALE)));
        // The card's bottom edge stays where the vanilla badge sits.
        int top = title.isBlank() ? 0 : -TITLE_Y, left = -width / 2;
        quad(matrix, buffers.getBuffer(seeThrough ? RenderType.textBackgroundSeeThrough() : RenderType.textBackground()),
            left - 3, top - 3, left + width + 3, title.isBlank() ? 11 : top + TITLE_Y + 9, -0.01F, AgentCard.background(), light);
        header.draw(matrix, buffers, left, top, width, light, seeThrough);
        if (!title.isBlank()) {
            String shown = AgentCard.ellipsis(title, (int)(width / TITLE_SCALE));
            text(shown, -client.font.width(shown) * TITLE_SCALE / 2, top + TITLE_Y, 0xFF000000 | header.color(), TITLE_SCALE,
                matrix, buffers, light, seeThrough);
        }
        pose.popPose();
    }

    /** One row: the agent's name and provider icon, centered together. */
    record Header(String name, ResourceLocation provider, int color) {
        private static final int ICON = 13;

        int width() { return Minecraft.getInstance().font.width(name) + (provider == null ? 0 : ICON); }

        void draw(Matrix4f matrix, MultiBufferSource buffers, int x, int y, int width, int light, boolean seeThrough) {
            var font = Minecraft.getInstance().font;
            int icon = provider == null ? 0 : ICON;
            String shown = AgentCard.ellipsis(name, width - icon);
            float nameX = x + (width - font.width(shown) - icon) / 2F;
            text(shown, nameX, y, 0xFFFFFFFF, 1, matrix, buffers, light, seeThrough);
            if (provider != null) icon(matrix, buffers, provider, (int)(nameX + font.width(shown) + 4), y - 1, light);
        }
    }

    static Header header(JsonObject agent) {
        String color = AgentModels.text(agent, "projectColor");
        if (!ProjectColor.valid(color)) color = ProjectColor.forId(AgentModels.text(agent, "projectId"));
        return new Header(AgentModels.text(agent, "name").replace('\n', ' '),
            ProviderIcon.textureFor(AgentModels.text(agent, "providerId")), ProjectColor.rgb(color));
    }

    private static void text(String text, float x, float y, int color, float scale, Matrix4f matrix, MultiBufferSource buffers, int light, boolean seeThrough) {
        var font = Minecraft.getInstance().font;
        var textMatrix = new Matrix4f(matrix).translate(x, y, 0).scale(scale, scale, 1);
        if (seeThrough) font.drawInBatch(text, 0, 0, (color & 0xFFFFFF) | 0x20000000, false, textMatrix, buffers, Font.DisplayMode.SEE_THROUGH, 0, light);
        font.drawInBatch(text, 0, 0, color, false, textMatrix, buffers, Font.DisplayMode.NORMAL, 0, light);
    }

    private static void icon(Matrix4f matrix, MultiBufferSource buffers, ResourceLocation texture, int x, int y, int light) {
        var out = buffers.getBuffer(RenderType.text(texture));
        out.addVertex(matrix, x, y + 9, 0).setColor(-1).setUv(0, 1).setLight(light);
        out.addVertex(matrix, x + 9, y + 9, 0).setColor(-1).setUv(1, 1).setLight(light);
        out.addVertex(matrix, x + 9, y, 0).setColor(-1).setUv(1, 0).setLight(light);
        out.addVertex(matrix, x, y, 0).setColor(-1).setUv(0, 0).setLight(light);
    }

    private static void quad(Matrix4f matrix, VertexConsumer out, float x0, float y0, float x1, float y1, float z, int color, int light) {
        out.addVertex(matrix, x0, y1, z).setColor(color).setLight(light);
        out.addVertex(matrix, x1, y1, z).setColor(color).setLight(light);
        out.addVertex(matrix, x1, y0, z).setColor(color).setLight(light);
        out.addVertex(matrix, x0, y0, z).setColor(color).setLight(light);
    }
}
