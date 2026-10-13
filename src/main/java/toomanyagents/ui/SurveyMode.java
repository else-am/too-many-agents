package toomanyagents.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import toomanyagents.ProjectColor;

/** Shows every station's area as an outline, with its name and occupant. View only; nothing is ever edited here. */
public final class SurveyMode {
    static final int ACCENT = 0xE2D4A7;
    private static SurveyMode instance;
    // Lines and faces draw straight to the main target so they also show under Fabulous graphics.
    private static final RenderType LINES = lines("too_many_agents_survey_lines", RenderStateShard.LEQUAL_DEPTH_TEST);
    private static final RenderType GHOST_LINES = lines("too_many_agents_survey_ghost_lines", RenderStateShard.NO_DEPTH_TEST);
    private static final RenderType FACES = RenderType.create("too_many_agents_survey_faces", DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS, 1536, false, true, RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER).setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING).setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL).createCompositeState(false));

    record Box(JsonObject row, String projectId, String dimension, BlockPos min, BlockPos max) {
        AABB area() { return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1); }
    }

    private final Supplier<AgentUiAccess> access;
    public final KeyMapping key = new KeyMapping("Show station areas", KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "Too Many Agents");
    private boolean on;
    private JsonObject data = new JsonObject();
    private List<Box> boxes = List.of();
    private Map<String, JsonObject> agents = Map.of();
    private int refreshTicks;

    public SurveyMode(Supplier<AgentUiAccess> access) {
        this.access = access;
        instance = this;
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::renderLevel);
        NeoForge.EVENT_BUS.addListener(this::renderHud);
    }

    private static RenderType lines(String name, RenderStateShard.DepthTestStateShard depth) {
        return RenderType.create(name, DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1536, false, false,
            RenderType.CompositeState.builder().setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING).setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE).setCullState(RenderStateShard.NO_CULL).setDepthTestState(depth)
                .createCompositeState(false));
    }

    /** Shows station areas, from the project screen. */
    public static void start() {
        if (instance == null) return;
        instance.setOn(true);
        Minecraft.getInstance().setScreen(null);
    }

    public boolean on() { return on; }

    public static String keyLabel() { return instance==null ? "Unbound" : instance.key.getTranslatedKeyMessage().getString(); }

    public void setOn(boolean value) {
        on = value;
        if (on) refresh();
    }

    private void refresh() {
        var service = access.get();
        if (service == null) return;
        data = service.projects();
        var rows = new ArrayList<Box>();
        var world = data.has("world") ? data.getAsJsonObject("world") : new JsonObject();
        for (var item : AgentModels.array(world, "stations")) {
            var row = item.getAsJsonObject();
            rows.add(new Box(row, AgentModels.text(row, "projectId"), AgentModels.text(row, "dimension"), pos(row.getAsJsonArray("min")), pos(row.getAsJsonArray("max"))));
        }
        boxes = rows;
        var named = new HashMap<String, JsonObject>();
        for (var item : service.worldAgents()) named.put(AgentModels.text(item.getAsJsonObject(), "id"), item.getAsJsonObject());
        agents = named;
        refreshTicks = 0;
    }

    private static BlockPos pos(JsonArray xyz) { return new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()); }

    private int color(String projectId) {
        if (projectId.isBlank()) return 0x9A9A9A;
        for (var item : AgentModels.array(data, "projects")) {
            var project = item.getAsJsonObject();
            if (AgentModels.text(project, "id").equals(projectId)) return ProjectColor.rgb(ProjectColor.of(project));
        }
        return ProjectColor.rgb(ProjectColor.forId(projectId));
    }

    private List<Box> visible() {
        var level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        String dimension = level.dimension().location().toString();
        var shown=new ArrayList<Box>();
        if(on)shown.addAll(boxes.stream().filter(box -> box.dimension().equals(dimension)).toList());
        if(Minecraft.getInstance().screen instanceof StationScreen screen) {
            var row=screen.outline();
            shown.removeIf(box->AgentModels.text(box.row(),"id").equals(AgentModels.text(row,"id")));
            if(AgentModels.text(row,"dimension").equals(dimension))shown.add(new Box(row,AgentModels.text(row,"projectId"),dimension,pos(row.getAsJsonArray("min")),pos(row.getAsJsonArray("max"))));
        }
        return shown;
    }

    private void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        while (key.consumeClick()) if (client.screen == null && client.level != null) setOn(!on);
        if (client.level == null && on) setOn(false);
        if ((on || client.screen instanceof StationScreen) && ++refreshTicks >= 10) refresh();
    }

    private void renderLevel(RenderLevelStageEvent event) {
        var client = Minecraft.getInstance();
        if ((!on && !(client.screen instanceof StationScreen)) || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || client.level == null) return;
        Vec3 camera = event.getCamera().getPosition();
        var pose = new PoseStack();
        pose.mulPose(event.getModelViewMatrix());
        var buffers = client.renderBuffers().bufferSource();
        var shown = visible();

        // Occupied stations get a soft fill; free ones stay as outlines.
        var faces = buffers.getBuffer(FACES);
        for (var box : shown)
            if (!AgentModels.text(box.row(), "agentId").isBlank()) faces(pose, faces, box.area().inflate(0.004).move(camera.reverse()), color(box.projectId()), 0.10F);
        buffers.endBatch(FACES);

        var lines = buffers.getBuffer(LINES);
        for (var box : shown) outline(pose, lines, box.area().inflate(0.004).move(camera.reverse()), color(box.projectId()), 0.85F);
        linkedScreens(pose,lines,camera,shown);
        buffers.endBatch(LINES);

        // Faint copies through blocks keep areas readable behind walls and floors.
        var ghosts = buffers.getBuffer(GHOST_LINES);
        for (var box : shown) outline(pose, ghosts, box.area().inflate(0.004).move(camera.reverse()), color(box.projectId()), 0.18F);
        buffers.endBatch(GHOST_LINES);

        for (var box : shown) {
            var area = box.area();
            var at = new Vec3(area.getCenter().x, area.maxY + 0.3, area.getCenter().z);
            // Labels fade out with distance so rows of small desks stay readable.
            float alpha = (float) Math.clamp((28 - at.distanceTo(camera)) / 8, 0, 1);
            if (alpha > 0) label(pose, buffers, event, camera, at, title(box), alpha);
        }
        buffers.endBatch();
    }

    /** Uses the same published links as ScreenTools, never a second geometric linking rule. */
    private void linkedScreens(PoseStack pose,VertexConsumer lines,Vec3 camera,List<Box> shown) {
        var level=Minecraft.getInstance().level;
        if(level==null)return;
        var links=AgentModels.array(AgentModels.object(data,"world"),"screenLinks");
        for(var entity:level.entitiesForRendering()) {
            if(!(entity instanceof toomanyagents.screens.ScreenEntity screen))continue;
            for(var value:links) {
                var link=value.getAsJsonObject();if(!AgentModels.text(link,"screenId").equals(screen.record().id()))continue;
                for(var station:shown) {
                    if(!AgentModels.text(link,"stationId").equals(AgentModels.text(station.row(),"id")))continue;
                    int color=color(station.projectId());
                    outline(pose,lines,screen.getBoundingBox().inflate(.025).move(camera.reverse()),color,1);
                    var from=station.area().getCenter().subtract(camera);var to=screen.getBoundingBox().getCenter().subtract(camera);
                    var normal=to.subtract(from).normalize();var matrix=pose.last();
                    for(var point:List.of(from,to))lines.addVertex(matrix,(float)point.x,(float)point.y,(float)point.z)
                        .setColor((color>>16&255)/255F,(color>>8&255)/255F,(color&255)/255F,.75F)
                        .setNormal(matrix,(float)normal.x,(float)normal.y,(float)normal.z);
                }
            }
        }
    }

    private Component title(Box box) {
        MutableComponent text = Component.literal(AgentModels.text(box.row(), "name")).withColor(color(box.projectId()));
        var agent = agents.get(AgentModels.text(box.row(), "agentId"));
        if (agent != null) text.append(Component.literal(" - ").withColor(0x9A9A9A)).append(Component.literal(AgentModels.text(agent, "name")).withColor(0xDDDDDD));
        return text;
    }

    private static void outline(PoseStack pose, VertexConsumer lines, AABB box, int rgb, float alpha) {
        LevelRenderer.renderLineBox(pose, lines, box, (rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, alpha);
    }

    private static void faces(PoseStack pose, VertexConsumer out, AABB b, int rgb, float alpha) {
        var m = pose.last().pose();
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, bl = rgb & 255, a = (int) (alpha * 255);
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ, x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        float[][] quads = {
            {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1}, {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0},
            {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1},
            {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0}, {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1}};
        for (var q : quads) for (int i = 0; i < 12; i += 3) out.addVertex(m, q[i], q[i + 1], q[i + 2]).setColor(r, g, bl, a);
    }

    /** A billboard label that keeps a readable size at distance and shows faintly through blocks. */
    private static void label(PoseStack pose, MultiBufferSource buffers, RenderLevelStageEvent event, Vec3 camera, Vec3 at, Component text, float alpha) {
        var font = Minecraft.getInstance().font;
        double distance = at.distanceTo(camera);
        float scale = 0.018F * (float) Math.max(1, distance / 10);
        pose.pushPose();
        pose.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        pose.mulPose(event.getCamera().rotation());
        pose.scale(scale, -scale, scale);
        float x = -font.width(text) / 2F;
        int faint = (int) (0x50 * alpha) << 24 | 0xFFFFFF, solid = Math.max(4, (int) (255 * alpha)) << 24 | 0xFFFFFF;
        font.drawInBatch(text, x, 0, faint, false, pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, (int) (0x55 * alpha) << 24, LightTexture.FULL_BRIGHT);
        font.drawInBatch(text, x, 0, solid, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }

    private void renderHud(RenderGuiEvent.Post event) {
        var client = Minecraft.getInstance();
        if (client.screen != null || client.options.hideGui || !on) return;
        var g = event.getGuiGraphics();
        var font = client.font;
        String mark = "◇ Station areas", hint = "press " + keyLabel() + " to hide";
        int gap = 8, width = font.width(mark) + gap + font.width(hint), x = (g.guiWidth() - width) / 2, y = g.guiHeight() - 78;
        g.fill(x - 6, y - 4, x + width + 6, y + 12, 0xD0101010);
        g.fill(x - 6, y + 12, x + width + 6, y + 13, 0xFF000000 | ACCENT);
        g.drawString(font, mark, x, y, ACCENT, false);
        g.drawString(font, hint, x + font.width(mark) + gap, y, 0xD8D8D8, false);
    }

    /** Survey state for local UI diagnostics. */
    public JsonObject diagnostics() {
        var result = new JsonObject();
        result.addProperty("on", on);
        result.addProperty("visibleBoxes", visible().size());
        result.add("screenLinks", AgentModels.array(AgentModels.object(data,"world"),"screenLinks").deepCopy());
        return result;
    }
}
