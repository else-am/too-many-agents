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
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import toomanyagents.AgentColor;

/**
 * Shows project boxes and stations as outlines and draws new ones from two clicked corners.
 * Rendering and clicks only; the world is never changed and edits go through projectCommand.
 */
public final class SurveyMode {
    private static final double RANGE = 64;
    static final int ACCENT = 0xE2D4A7;
    private static SurveyMode instance;
    static String lastLabel = "";
    // Lines and faces draw straight to the main target so they also show under Fabulous graphics.
    private static final RenderType LINES = lines("too_many_agents_survey_lines", RenderStateShard.LEQUAL_DEPTH_TEST);
    private static final RenderType GHOST_LINES = lines("too_many_agents_survey_ghost_lines", RenderStateShard.NO_DEPTH_TEST);
    private static final RenderType FACES = RenderType.create("too_many_agents_survey_faces", DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS, 1536, false, true, RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER).setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING).setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL).createCompositeState(false));

    record Box(String kind, JsonObject row, String projectId, String dimension, BlockPos min, BlockPos max) {
        AABB area() { return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1); }
        boolean contains(BlockPos pos) { return area().contains(Vec3.atCenterOf(pos)); }
        long volume() { return (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1); }
        String id() { return kind.equals("station") ? AgentModels.text(row, "id") : projectId; }
    }

    private final Supplier<AgentUiAccess> access;
    public final KeyMapping key = new KeyMapping("Survey project boxes", KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "Too Many Agents");
    private boolean on, attackWasDown, useWasDown;
    private String focusProject = "";
    private BlockPos first, lastTarget;
    // The box being redrawn, and the box and corners shown in the open editor.
    private Box redraw, editing;
    private AABB draft;
    private JsonObject data = new JsonObject();
    private List<Box> boxes = List.of();
    private Map<String, JsonObject> agents = Map.of();
    private int refreshTicks;
    private String message = "";
    private long messageUntil;

    public SurveyMode(Supplier<AgentUiAccess> access) {
        this.access = access;
        instance = this;
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::click);
        NeoForge.EVENT_BUS.addListener(this::escape);
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

    /** Enter survey mode from the project screen to draw that project's box. */
    public static void start(String projectId) {
        if (instance == null) return;
        instance.focusProject = projectId == null ? "" : projectId;
        instance.setOn(true);
        for (var box : instance.boxes) if (box.kind().equals("bounds") && box.projectId().equals(projectId)) instance.redraw = box;
        Minecraft.getInstance().setScreen(null);
    }

    public boolean on() { return on; }


    public void setOn(boolean value) {
        on = value;
        first = null;
        redraw = null;
        if (on) refresh();
    }

    // ---- state ----

    void refresh() {
        var service = access.get();
        if (service == null) return;
        data = service.projects();
        var rows = new ArrayList<Box>();
        var world = data.has("world") ? data.getAsJsonObject("world") : new JsonObject();
        for (var item : AgentModels.array(world, "bounds")) rows.add(box("bounds", item.getAsJsonObject()));
        for (var item : AgentModels.array(world, "stations")) rows.add(box("station", item.getAsJsonObject()));
        boxes = rows;
        var named = new HashMap<String, JsonObject>();
        for (var item : service.worldAgents()) named.put(AgentModels.text(item.getAsJsonObject(), "id"), item.getAsJsonObject());
        agents = named;
        refreshTicks = 0;
    }

    private static Box box(String kind, JsonObject row) {
        return new Box(kind, row, AgentModels.text(row, "projectId"), AgentModels.text(row, "dimension"), pos(row.getAsJsonArray("min")), pos(row.getAsJsonArray("max")));
    }

    static BlockPos pos(JsonArray xyz) { return new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()); }

    String projectName(String id) {
        for (var item : AgentModels.array(data, "projects"))
            if (AgentModels.text(item.getAsJsonObject(), "id").equals(id)) return AgentModels.text(item.getAsJsonObject(), "name");
        return "Project";
    }

    static int color(String projectId) { return AgentColor.rgb(AgentColor.forId(projectId)); }

    private List<Box> visible() {
        var level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        String dimension = level.dimension().location().toString();
        return boxes.stream().filter(box -> box.dimension().equals(dimension)).toList();
    }

    /** The block under the crosshair, reaching further than the player's hand. */
    private BlockPos aim(float partialTick) {
        var client = Minecraft.getInstance();
        var camera = client.getCameraEntity();
        if (camera == null) return null;
        HitResult hit = camera.pick(RANGE, partialTick, false);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block.getBlockPos() : null;
    }

    /** The box a right-click would edit: the smallest one holding the aimed block, else the nearest one the view ray enters. */
    private Box pointed(float partialTick) {
        var client = Minecraft.getInstance();
        var camera = client.getCameraEntity();
        if (camera == null) return null;
        BlockPos target = aim(partialTick);
        Box best = null;
        if (target != null) for (var box : visible()) if (box.contains(target) && (best == null || box.volume() < best.volume())) best = box;
        if (best != null) return best;
        Vec3 eye = camera.getEyePosition(partialTick), end = eye.add(camera.getViewVector(partialTick).scale(RANGE));
        double nearest = Double.MAX_VALUE;
        for (var box : visible()) {
            var area = box.area();
            if (area.contains(eye)) continue;
            var hit = area.clip(eye, end);
            if (hit.isPresent() && hit.get().distanceToSqr(eye) < nearest) { nearest = hit.get().distanceToSqr(eye); best = box; }
        }
        return best;
    }

    // ---- input ----

    private void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        while (key.consumeClick()) if (client.screen == null && client.level != null) {
            setOn(!on);
            say(on ? "" : "Survey off");
        }
        if (client.level == null && on) setOn(false);
        if (on && ++refreshTicks >= 40) refresh();
        attackWasDown = client.options.keyAttack.isDown();
        useWasDown = client.options.keyUse.isDown();
    }

    /** Survey clicks select instead of breaking, placing, or touching entities. Held buttons repeat nothing. */
    private void click(InputEvent.InteractionKeyMappingTriggered event) {
        var client = Minecraft.getInstance();
        if (!on || client.screen != null) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        float partial = client.getTimer().getGameTimeDeltaPartialTick(true);
        if (event.isAttack() && !attackWasDown) {
            attackWasDown = true;
            BlockPos target = aim(partial);
            if (target != null) corner(target);
        } else if (event.isUseItem() && !useWasDown) {
            useWasDown = true;
            if (first != null) { first = null; return; }
            redraw = null;
            var box = pointed(partial);
            if (box != null) edit(box);
        }
    }

    /** Escape steps back out of a half-drawn box instead of pausing. */
    private void escape(ScreenEvent.Opening event) {
        var client = Minecraft.getInstance();
        if (!on || first == null && redraw == null || !(event.getNewScreen() instanceof PauseScreen) || client.screen != null) return;
        if (!client.isWindowActive() && client.options.pauseOnLostFocus) return;
        event.setCanceled(true);
        if (first != null) first = null;
        else redraw = null;
    }

    /** First click starts a box; the second finishes it. Clicking the same block twice makes a 1×1 box. */
    public void corner(BlockPos pos) {
        if (first == null) { first = pos; return; }
        var min = BlockPos.min(first, pos);
        var max = BlockPos.max(first, pos);
        first = null;
        var service = access.get();
        var level = Minecraft.getInstance().level;
        if (service == null || level == null) return;
        String dimension = level.dimension().location().toString();
        draft = new AABB(Vec3.atLowerCornerOf(min), Vec3.atLowerCornerOf(max).add(1, 1, 1));
        if (redraw != null) { var box = redraw; redraw = null; open(new SurveyScreen(this, service, box, min, max, dimension, "", ""), box); return; }
        // Inside a project box the new box is a station of that project; elsewhere it is a project box.
        Box holder = null;
        for (var box : visible())
            if (box.kind().equals("bounds") && box.contains(min) && box.contains(max)
                && (holder == null || box.projectId().equals(focusProject) || box.volume() < holder.volume() && !holder.projectId().equals(focusProject))) holder = box;
        String project = holder != null ? holder.projectId() : !focusProject.isBlank() ? focusProject : firstProject();
        open(new SurveyScreen(this, service, null, min, max, dimension, holder != null ? "station" : "bounds", project), null);
    }

    public void edit(Box box) {
        var service = access.get();
        if (service == null) return;
        draft = null;
        open(new SurveyScreen(this, service, box, null, null, box.dimension(), "", ""), box);
    }

    private void open(SurveyScreen screen, Box box) {
        Minecraft.getInstance().setScreen(screen);
        editing = box;
        if (box != null) focusProject = box.projectId();
    }

    /** Start redrawing a box; the next two corners reopen its editor. */
    void redraw(Box box) {
        redraw = box;
        first = null;
    }

    void closed() {
        editing = null;
        draft = null;
    }

    private String firstProject() {
        var projects = AgentModels.array(data, "projects");
        return projects.isEmpty() ? "" : AgentModels.text(projects.get(0).getAsJsonObject(), "id");
    }

    /** Select the box holding a block, as a right-click there would. */
    public boolean editAt(BlockPos pos) {
        Box best = null;
        for (var box : visible()) if (box.contains(pos) && (best == null || box.volume() < best.volume())) best = box;
        if (best != null) edit(best);
        return best != null;
    }

    void say(String text) { message = text; messageUntil = System.currentTimeMillis() + 2000; }

    static String size(BlockPos a, BlockPos b) {
        var min = BlockPos.min(a, b);
        var max = BlockPos.max(a, b);
        return (max.getX() - min.getX() + 1) + " × " + (max.getY() - min.getY() + 1) + " × " + (max.getZ() - min.getZ() + 1);
    }

    // ---- rendering ----

    private void renderLevel(RenderLevelStageEvent event) {
        var client = Minecraft.getInstance();
        if (!on || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || client.level == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 camera = event.getCamera().getPosition();
        var pose = new PoseStack();
        pose.mulPose(event.getModelViewMatrix());
        var buffers = client.renderBuffers().bufferSource();
        var shown = visible();
        BlockPos target = aim(partial);
        if (target != null) lastTarget = target;
        Box hovered = editing != null ? editing : first == null && client.screen == null ? pointed(partial) : null;
        AABB preview = draft != null ? draft : first == null ? null : new AABB(Vec3.atLowerCornerOf(BlockPos.min(first, lastTarget == null ? first : lastTarget)),
            Vec3.atLowerCornerOf(BlockPos.max(first, lastTarget == null ? first : lastTarget)).add(1, 1, 1));

        // Occupied stations and the box under the cursor get a soft fill; free ones stay as outlines.
        var faces = buffers.getBuffer(FACES);
        for (var box : shown) {
            boolean occupied = box.kind().equals("station") && !AgentModels.text(box.row(), "agentId").isBlank();
            float alpha = box == hovered ? 0.14F : occupied ? 0.10F : 0;
            if (alpha > 0) faces(pose, faces, box.area().inflate(0.004).move(camera.reverse()), color(box.projectId()), alpha);
        }
        if (preview != null) faces(pose, faces, preview.inflate(0.006).move(camera.reverse()), ACCENT, 0.12F);
        buffers.endBatch(FACES);

        var lines = buffers.getBuffer(LINES);
        for (var box : shown) outline(pose, lines, box.area().inflate(0.004).move(camera.reverse()), color(box.projectId()), box == hovered ? 1F : 0.85F);
        if (preview != null) outline(pose, lines, preview.inflate(0.008).move(camera.reverse()), ACCENT, 1F);
        else if (target != null && client.screen == null) outline(pose, lines, new AABB(target).inflate(0.006).move(camera.reverse()), ACCENT, 0.6F);
        buffers.endBatch(LINES);

        // Faint copies through blocks keep boxes readable behind walls and floors.
        var ghosts = buffers.getBuffer(GHOST_LINES);
        for (var box : shown) outline(pose, ghosts, box.area().inflate(0.004).move(camera.reverse()), color(box.projectId()), 0.18F);
        if (preview != null) outline(pose, ghosts, preview.inflate(0.008).move(camera.reverse()), ACCENT, 0.3F);
        buffers.endBatch(GHOST_LINES);

        // Labels stay out of the way while the editor panel is open; the outlines are context enough.
        if (!(client.screen instanceof SurveyScreen)) {
            for (var box : shown) {
                var area = box.area();
                var at = new Vec3(area.getCenter().x, area.maxY + 0.3, area.getCenter().z);
                // Station labels fade out with distance so rows of small desks stay readable.
                float alpha = box.kind().equals("station") ? (float) Math.clamp((28 - at.distanceTo(camera)) / 8, 0, 1) : 1;
                if (alpha > 0) label(pose, buffers, event, camera, at, title(box), alpha);
            }
            if (preview != null)
                label(pose, buffers, event, camera, new Vec3(preview.getCenter().x, preview.maxY + 0.3, preview.getCenter().z),
                    Component.literal(size(BlockPos.containing(preview.minX, preview.minY, preview.minZ), BlockPos.containing(preview.maxX - 1, preview.maxY - 1, preview.maxZ - 1))).withColor(ACCENT), 1);
        }
        buffers.endBatch();
    }

    private Component title(Box box) {
        int color = color(box.projectId());
        if (!box.kind().equals("station")) return Component.literal(projectName(box.projectId())).withColor(color);
        MutableComponent text = Component.literal(AgentModels.text(box.row(), "label")).withColor(color);
        if (box.row().has("outsideProject") && box.row().get("outsideProject").getAsBoolean()) text.append(Component.literal(" - outside").withColor(0xE07A6A));
        var agent = agents.get(AgentModels.text(box.row(), "agentId"));
        if (agent != null) text.append(Component.literal(" - ").withColor(0x9A9A9A)).append(AgentColor.name(AgentModels.text(agent, "name"), AgentModels.text(agent, "color")));
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
        boolean showMessage = System.currentTimeMillis() < messageUntil && !message.isBlank();
        if (client.screen != null || client.options.hideGui || !on && !showMessage) return;
        var g = event.getGuiGraphics();
        var font = client.font;
        // One short line; a status briefly replaces the hint.
        String hint;
        if (showMessage) hint = message;
        else if (first != null) hint = size(first, lastTarget == null ? first : lastTarget).replace(" ", "") + " - opposite corner";
        else if (redraw != null) hint = "redraw " + (redraw.kind().equals("station") ? AgentModels.text(redraw.row(), "label") : projectName(redraw.projectId()));
        else hint = "click corners";
        String mark = on ? "◇ Survey" : "◇";
        int gap = 8, room = g.guiWidth() - 32 - font.width(mark) - gap;
        hint = font.plainSubstrByWidth(hint, Math.max(0, room));
        int width = font.width(mark) + gap + font.width(hint), x = (g.guiWidth() - width) / 2, y = g.guiHeight() - 78;
        g.fill(x - 6, y - 4, x + width + 6, y + 12, 0xD0101010);
        g.fill(x - 6, y + 12, x + width + 6, y + 13, 0xFF000000 | ACCENT);
        g.drawString(font, mark, x, y, ACCENT, false);
        g.drawString(font, hint, x + font.width(mark) + gap, y, 0xD8D8D8, false);
    }

    /** Survey state for local UI diagnostics. */
    public JsonObject diagnostics() {
        var result = new JsonObject();
        result.addProperty("on", on);
        result.addProperty("focusProject", focusProject);
        if (first != null) result.add("first", json(first));
        if (redraw != null) result.addProperty("redraw", redraw.kind() + ":" + redraw.id());
        if (editing != null) result.addProperty("editing", editing.kind() + ":" + editing.id());
        var client = Minecraft.getInstance();
        var target = client.level == null ? null : aim(1);
        if (target != null) {
            var aimed = json(target);
            aimed.addProperty("block", BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(target).getBlock()).toString());
            result.add("target", aimed);
        }
        var pointed = client.level == null ? null : pointed(1);
        if (pointed != null) result.addProperty("pointed", pointed.kind() + ":" + pointed.id());
        result.addProperty("visibleBoxes", visible().size());
        result.addProperty("message", message);
        return result;
    }

    static JsonObject json(BlockPos pos) {
        var result = new JsonObject();
        result.addProperty("x", pos.getX());
        result.addProperty("y", pos.getY());
        result.addProperty("z", pos.getZ());
        return result;
    }
}
