package toomanyagents.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/**
 * Placing a screen takes two right-clicks on a block face. The first sets one corner; a preview then follows your aim along
 * that plane, up to 16x16, marking cells that can't hold a screen. The second places it. Switching items or Esc cancels.
 * Render thread only, apart from the placement itself, which runs on the integrated server.
 */
final class ScreenPlacement {
    private static final int VALID = 0xE2D4A7, INVALID = 0xE0705A;
    /** A placement that hasn't started on the server by then is dropped. */
    private static final long EXPIRY_NANOS = 2_000_000_000L;
    private static final RenderType FACES = RenderType.create("too_many_agents_screen_preview", DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS, 1536, false, true, RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER).setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING).setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL).createCompositeState(false));
    private static final RenderType LINES = RenderType.create("too_many_agents_screen_preview_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
        VertexFormat.Mode.LINES, 256, false, false, RenderType.CompositeState.builder().setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
            .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING).setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE).setCullState(RenderStateShard.NO_CULL).createCompositeState(false));

    /** The first corner: the cell in front of the clicked surface, which faces out of it. */
    private record Corner(ClientLevel level, BlockPos cell, Direction facing, Direction up, int slot) {}
    private static Corner corner;
    /** The far corner, in cells right and up from the first. */
    private static int farRight, farUp;
    private static final List<BlockPos> invalid = new ArrayList<>();
    /** Holding the use key repeats it; only a fresh press places. */
    private static boolean released;

    private ScreenPlacement() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(ScreenPlacement::use);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener(ScreenPlacement::render);
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> hint(event.getGuiGraphics()));
    }

    private static void use(InputEvent.InteractionKeyMappingTriggered event) {
        var mc = Minecraft.getInstance();
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND || mc.player == null || mc.level == null) return;
        if (!(mc.player.getMainHandItem().getItem() instanceof ScreenItem)) return;
        if (corner == null) {
            if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
            event.setCanceled(true);
            var facing = hit.getDirection();
            var up = Direction.UP;
            if (facing.getAxis() == Direction.Axis.Y)
                up = facing == Direction.UP ? mc.player.getDirection() : mc.player.getDirection().getOpposite();
            corner = new Corner(mc.level, hit.getBlockPos().relative(facing), facing, up, mc.player.getInventory().selected);
            farRight = farUp = 0;
            released = false;
            check();
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        if (!released || !invalid.isEmpty()) return;
        event.setSwingHand(true);
        confirm();
    }

    private static void tick() {
        if (corner == null) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level != corner.level || mc.screen != null || mc.player.getInventory().selected != corner.slot
            || !(mc.player.getMainHandItem().getItem() instanceof ScreenItem)) {
            cancel();
            return;
        }
        if (!mc.options.keyUse.isDown()) released = true;
        check();
    }

    /** Esc cancels placing. Called from the keyboard handler before Minecraft sees a key. */
    static boolean keyPress(int key, int action) {
        if (corner == null || key != GLFW.GLFW_KEY_ESCAPE || Minecraft.getInstance().screen != null) return false;
        if (action == GLFW.GLFW_PRESS) cancel();
        return true;
    }

    static void cancel() {
        corner = null;
        invalid.clear();
    }

    private static Vec3 rightVector() { return Vec3.atLowerCornerOf(ScreenEntity.right(corner.facing, corner.up).getNormal()); }

    /** Follows the aim along the surface's plane, and checks each cell with the same rule a placed screen uses. */
    private static void check() {
        var mc = Minecraft.getInstance();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(true);
        Vec3 eye = mc.player.getEyePosition(partial), look = mc.player.getViewVector(partial);
        Vec3 normal = Vec3.atLowerCornerOf(corner.facing.getNormal()), origin = Vec3.atCenterOf(corner.cell);
        double toward = look.dot(normal);
        if (toward < -1e-4) {
            double distance = origin.relative(corner.facing, -0.5).subtract(eye).dot(normal) / toward;
            if (distance > 0) {
                Vec3 offset = eye.add(look.scale(distance)).subtract(origin);
                int limit = ScreenEntity.MAX_SIZE - 1;
                farRight = Math.clamp(Math.round(offset.dot(rightVector())), -limit, limit);
                farUp = Math.clamp(Math.round(offset.dot(Vec3.atLowerCornerOf(corner.up.getNormal()))), -limit, limit);
            }
        }
        invalid.clear();
        for (var cell : cells())
            if (!new ScreenEntity(mc.level, cell, corner.facing, corner.up, 1, 1, Screen.BLANK).survives()) invalid.add(cell);
    }

    private static BlockPos bottomLeft() {
        return corner.cell.relative(ScreenEntity.right(corner.facing, corner.up), Math.min(0, farRight)).relative(corner.up, Math.min(0, farUp));
    }
    private static int width() { return Math.abs(farRight) + 1; }
    private static int height() { return Math.abs(farUp) + 1; }

    private static List<BlockPos> cells() {
        var cells = new ArrayList<BlockPos>();
        var right = ScreenEntity.right(corner.facing, corner.up);
        for (int r = 0; r < width(); r++)
            for (int y = 0; y < height(); y++) cells.add(bottomLeft().relative(right, r).relative(corner.up, y));
        return cells;
    }

    private static void confirm() {
        var mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            // Placement reaches the integrated server directly; there is no multiplayer transport.
            cancel();
            return;
        }
        UUID player = mc.player.getUUID();
        ResourceKey<Level> dimension = corner.level.dimension();
        BlockPos pos = bottomLeft();
        Direction facing = corner.facing, up = corner.up;
        int width = width(), height = height(), slot = corner.slot;
        long deadline = System.nanoTime() + EXPIRY_NANOS;
        cancel();
        server.execute(() -> place(server, deadline, player, dimension, pos, facing, up, width, height, slot));
    }

    /** On the server thread: re-checks everything and hangs the screen, restoring the item's record. */
    private static void place(MinecraftServer server, long deadline, UUID playerId, ResourceKey<Level> dimension, BlockPos pos,
                              Direction facing, Direction up, int width, int height, int slot) {
        if (System.nanoTime() > deadline || !server.isRunning()) return;
        var player = server.getPlayerList().getPlayer(playerId);
        if (player == null || player.level().dimension() != dimension || player.getInventory().selected != slot) return;
        var stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ScreenItem) || !player.mayUseItemAt(pos, facing, stack)) return;
        var level = player.serverLevel();
        var screen = new ScreenEntity(level, pos, facing, up, width, height, ScreenItem.record(stack));
        if (!screen.survives()) return;
        screen.playPlacementSound();
        level.gameEvent(player, GameEvent.ENTITY_PLACE, screen.position());
        level.addFreshEntity(screen);
        if (!player.hasInfiniteMaterials()) stack.shrink(1);
        // Building a screen yourself is consent to load pages in this world.
        Trust.allow(server);
    }

    private static void render(RenderLevelStageEvent event) {
        if (corner == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        var mc = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        var pose = new PoseStack();
        pose.mulPose(event.getModelViewMatrix());
        pose.translate(-camera.x, -camera.y, -camera.z);
        var buffers = mc.renderBuffers().bufferSource();
        var right = rightVector();
        Vec3 out = Vec3.atLowerCornerOf(corner.facing.getNormal());
        var faces = buffers.getBuffer(FACES);
        var matrix = pose.last().pose();
        for (var cell : cells()) {
            // Each cell's face on the surface, inset a little so the grid reads.
            Vec3 center = Vec3.atCenterOf(cell).add(out.scale(-0.5 + 0.01));
            quad(faces, matrix, center, right, Vec3.atLowerCornerOf(corner.up.getNormal()), 0.47, invalid.contains(cell) ? INVALID : VALID, invalid.contains(cell) ? 0.45F : 0.22F);
        }
        buffers.endBatch(FACES);
        var slab = ScreenEntity.bounds(bottomLeft(), corner.facing, corner.up, width(), height()).inflate(0.004);
        int color = invalid.isEmpty() ? VALID : INVALID;
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(LINES), slab, (color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F, 0.9F);
        buffers.endBatch(LINES);
    }

    private static void quad(VertexConsumer out, Matrix4f m, Vec3 center, Vec3 right, Vec3 up, double half, int rgb, float alpha) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, a = (int) (alpha * 255);
        Vec3[] corners = {center.add(right.scale(-half)).add(up.scale(-half)), center.add(right.scale(half)).add(up.scale(-half)),
            center.add(right.scale(half)).add(up.scale(half)), center.add(right.scale(-half)).add(up.scale(half))};
        for (var c : corners) out.addVertex(m, (float) c.x, (float) c.y, (float) c.z).setColor(r, g, b, a);
    }

    private static void hint(GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        if (corner == null || mc.screen != null || mc.options.hideGui) return;
        String text = width() + " × " + height() + (invalid.isEmpty() ? ": right-click to place" : ": something is in the way") + ", Esc to cancel";
        float scale = mc.getWindow().getGuiScale() >= 3 ? 0.5f : 1f;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(graphics.guiWidth() / 2f, graphics.guiHeight() - 52, 0);
        pose.scale(scale, scale, 1);
        int x = -mc.font.width(text) / 2;
        graphics.fill(x - 5, -4, x + mc.font.width(text) + 5, 12, 0xB0101114);
        graphics.drawString(mc.font, text, x, 0, 0xFFE6E8EB, false);
        pose.popPose();
    }

    /** Status for development checks. */
    static JsonObject state() {
        var result = new JsonObject();
        result.addProperty("active", corner != null);
        if (corner == null) return result;
        result.addProperty("corner", corner.cell.toShortString());
        result.addProperty("facing", corner.facing.getName());
        result.addProperty("up", corner.up.getName());
        result.addProperty("pos", bottomLeft().toShortString());
        result.addProperty("size", width() + "x" + height());
        result.addProperty("released", released);
        var cells = new JsonArray();
        invalid.forEach(cell -> cells.add(cell.toShortString()));
        result.add("invalid", cells);
        return result;
    }
}
