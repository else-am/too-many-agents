package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import me.friwi.jcefmaven.CefAppBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.ScreenBrowser;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

/** Spike: embedded Chromium drawn on quads in the world. Throwaway code to answer go/no-go questions. */
final class ScreenSpike {
    static final int DEBUG_PORT = 9333;
    private static final AtomicReference<String> state = new AtomicReference<>("idle");
    private static volatile String failure = "";
    private static volatile CefApp app;
    private static volatile CefClient cefClient;
    private static final List<Wall> walls = new ArrayList<>();
    private static Matrix4f lastProjection = new Matrix4f(), lastModelView = new Matrix4f();
    private static Vec3 lastCamera = Vec3.ZERO;

    static void register() {
        NeoForge.EVENT_BUS.addListener(ScreenSpike::renderLevel);
        NeoForge.EVENT_BUS.addListener(ScreenSpike::use);
    }

    /** A browser showing on one quad. Its texture is only touched on the render thread. */
    static final class Wall {
        final int id;
        final Vec3 center, right, normal;
        final double width, height;
        final ResourceLocation location;
        final AbstractTexture texture = new AbstractTexture() { @Override public void load(ResourceManager manager) {} };
        ScreenBrowser browser;
        int texWidth, texHeight;
        long paints, pixels, uploadNanos, offThreadPaints, since = System.nanoTime();
        String paintThread = "";

        Wall(int id, Vec3 center, Vec3 right, double width, double height) {
            this.id = id; this.center = center; this.right = right; this.width = width; this.height = height;
            this.normal = right.cross(new Vec3(0, 1, 0)).normalize().reverse();
            this.location = ResourceLocation.fromNamespaceAndPath("too_many_agents", "screen_spike/" + id);
        }

        void paint(Rectangle[] dirty, ByteBuffer frame, int w, int h) {
            paintThread = Thread.currentThread().getName();
            if (!RenderSystem.isOnRenderThread()) { offThreadPaints++; return; }
            long start = System.nanoTime();
            GlStateManager._bindTexture(texture.getId());
            if (w != texWidth || h != texHeight) {
                GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (IntBuffer) null);
                texture.setFilter(true, false);
                texWidth = w; texHeight = h;
                dirty = new Rectangle[] {new Rectangle(0, 0, w, h)};
            }
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, w);
            for (var r : dirty) {
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, r.x);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, r.y);
                GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, r.x, r.y, r.width, r.height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(frame));
                pixels += (long) r.width * r.height;
            }
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            paints++;
            uploadNanos += System.nanoTime() - start;
        }

        /** Page pixel under a world ray, or null when it misses. */
        int[] hit(Vec3 origin, Vec3 direction) {
            double facing = direction.dot(normal);
            if (Math.abs(facing) < 1e-6) return null;
            double t = center.subtract(origin).dot(normal) / facing;
            if (t <= 0) return null;
            Vec3 offset = origin.add(direction.scale(t)).subtract(center);
            double u = offset.dot(right) / width + 0.5, v = 0.5 - offset.y / height;
            if (u < 0 || u > 1 || v < 0 || v > 1) return null;
            return new int[] {(int) (u * texWidth), (int) (v * texHeight)};
        }

        JsonObject stats() {
            var o = new JsonObject();
            double seconds = (System.nanoTime() - since) / 1e9;
            o.addProperty("id", id);
            o.addProperty("url", browser == null ? "" : browser.getURL());
            o.addProperty("size", texWidth + "x" + texHeight);
            o.addProperty("paintsPerSecond", paints / seconds);
            o.addProperty("megapixelsPerSecond", pixels / seconds / 1e6);
            o.addProperty("uploadMsPerPaint", paints == 0 ? 0 : uploadNanos / 1e6 / paints);
            o.addProperty("uploadMsPerSecond", uploadNanos / 1e6 / seconds);
            o.addProperty("offThreadPaints", offThreadPaints);
            o.addProperty("paintThread", paintThread);
            return o;
        }
    }

    static JsonObject control(JsonObject request) {
        String op = JsonState.text(request, "op");
        var client = Minecraft.getInstance();
        switch (op) {
            case "start" -> start(client.gameDirectory);
            case "open" -> {
                if (cefClient == null) throw new IllegalStateException("Chromium is " + state.get() + " " + failure);
                int pxWidth = request.has("width") ? request.get("width").getAsInt() : 1280;
                int pxHeight = request.has("height") ? request.get("height").getAsInt() : 720;
                double blocks = request.has("blocks") ? request.get("blocks").getAsDouble() : 4;
                double distance = request.has("distance") ? request.get("distance").getAsDouble() : 3.5;
                var player = client.player;
                player.setXRot(0);
                Vec3 look = Vec3.directionFromRotation(0, player.getYRot());
                Vec3 center = player.getEyePosition().add(look.scale(distance));
                Vec3 right = new Vec3(-look.z, 0, look.x);
                var wall = new Wall(walls.size() + 1, center, right, blocks, blocks * pxHeight / pxWidth);
                client.getTextureManager().register(wall.location, wall.texture);
                int fps = request.has("fps") ? request.get("fps").getAsInt() : 60;
                wall.browser = new ScreenBrowser(cefClient, JsonState.text(request, "url"), pxWidth, pxHeight, fps, wall::paint);
                wall.browser.setCloseAllowed();
                wall.browser.createImmediately();
                walls.add(wall);
            }
            case "load" -> wall(request).browser.loadURL(JsonState.text(request, "url"));
            case "focus" -> client.setScreen(new Focus(wall(request)));
            case "reset" -> walls.forEach(w -> { w.paints = w.pixels = w.uploadNanos = w.offThreadPaints = 0; w.since = System.nanoTime(); });
            case "drag" -> drag(wall(request), request.getAsJsonArray("points"), request.has("stepMs") ? request.get("stepMs").getAsInt() : 16);
            case "click" -> {
                var wall = wall(request);
                int x = request.get("x").getAsInt(), y = request.get("y").getAsInt();
                wall.browser.mouse(MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK, 1);
                wall.browser.mouse(MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON1, 0, 1);
            }
            case "close" -> {
                var wall = wall(request);
                wall.browser.close(true);
                walls.remove(wall);
                client.getTextureManager().release(wall.location);
            }
            case "status" -> {}
            default -> throw new IllegalArgumentException("Unknown screen spike op " + op);
        }
        return status();
    }

    private static Wall wall(JsonObject request) {
        int id = request.has("id") ? request.get("id").getAsInt() : walls.size();
        return walls.stream().filter(w -> w.id == id).findFirst().orElseThrow(() -> new IllegalArgumentException("No screen " + id));
    }

    private static void start(File gameDirectory) {
        if (!state.compareAndSet("idle", "starting")) return;
        // CefApp initializes through the AWT event thread, which then waits on the macOS main thread; the render
        // thread keeps pumping that, so this must not run on the render thread.
        var thread = new Thread(() -> {
            try {
                var builder = new CefAppBuilder();
                builder.setInstallDir(new File(gameDirectory, "too-many-agents/cef"));
                builder.setProgressHandler((progress, percent) -> state.set("starting: " + progress + (percent >= 0 ? " " + Math.round(percent) + "%" : "")));
                var settings = builder.getCefSettings();
                settings.windowless_rendering_enabled = true;
                settings.remote_debugging_port = DEBUG_PORT;
                settings.root_cache_path = new File(gameDirectory, "too-many-agents/cef-profile").getAbsolutePath();
                settings.cache_path = settings.root_cache_path;
                long start = System.nanoTime();
                app = builder.build();
                cefClient = app.createClient();
                state.set("ready in " + (System.nanoTime() - start) / 1_000_000 + " ms, " + app.getVersion());
            } catch (Throwable e) {
                failure = e.toString();
                state.set("failed");
                com.mojang.logging.LogUtils.getLogger().error("Screen spike failed to start Chromium", e);
            }
        }, "screen-spike-cef");
        thread.setDaemon(true);
        thread.start();
    }

    private static JsonObject status() {
        var o = new JsonObject();
        o.addProperty("state", state.get());
        o.addProperty("failure", failure);
        o.addProperty("fps", Minecraft.getInstance().getFps());
        var screens = new JsonArray();
        walls.forEach(w -> screens.add(w.stats()));
        o.add("screens", screens);
        var runtime = Runtime.getRuntime();
        o.addProperty("jvmHeapMb", (runtime.totalMemory() - runtime.freeMemory()) >> 20);
        var helpers = new StringBuilder();
        ProcessHandle.current().descendants().forEach(p -> helpers.append(p.pid()).append(','));
        o.addProperty("helperPids", helpers.toString());
        o.addProperty("pid", ProcessHandle.current().pid());
        return o;
    }

    private static void drag(Wall wall, JsonArray points, int stepMs) {
        var thread = new Thread(() -> {
            var client = Minecraft.getInstance();
            for (int i = 0; i < points.size(); i++) {
                var p = points.get(i).getAsJsonArray();
                int x = p.get(0).getAsInt(), y = p.get(1).getAsInt();
                int event = i == 0 ? MouseEvent.MOUSE_PRESSED : i == points.size() - 1 ? MouseEvent.MOUSE_RELEASED : MouseEvent.MOUSE_DRAGGED;
                int mask = event == MouseEvent.MOUSE_RELEASED ? 0 : InputEvent.BUTTON1_DOWN_MASK;
                client.execute(() -> wall.browser.mouse(event, x, y, MouseEvent.BUTTON1, mask, 1));
                try { Thread.sleep(stepMs); } catch (InterruptedException e) { return; }
            }
        }, "screen-spike-drag");
        thread.setDaemon(true);
        thread.start();
    }

    private static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || walls.isEmpty()) return;
        lastProjection = new Matrix4f(event.getProjectionMatrix());
        lastModelView = new Matrix4f(event.getModelViewMatrix());
        Vec3 camera = event.getCamera().getPosition();
        lastCamera = camera;
        var pose = new PoseStack();
        pose.mulPose(event.getModelViewMatrix());
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        for (var wall : walls) {
            if (wall.texWidth == 0) continue;
            var out = buffers.getBuffer(RenderType.text(wall.location));
            var m = pose.last().pose();
            Vec3 r = wall.right.scale(wall.width / 2), u = new Vec3(0, wall.height / 2, 0), c = wall.center.subtract(camera);
            Vec3[] corners = {c.subtract(r).add(u), c.subtract(r).subtract(u), c.add(r).subtract(u), c.add(r).add(u)};
            float[][] uv = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
            // Both windings, so the screen shows whichever way culling treats it.
            for (int pass = 0; pass < 2; pass++)
                for (int k = 0; k < 4; k++) {
                    int i = pass == 0 ? k : 3 - k;
                    out.addVertex(m, (float) corners[i].x, (float) corners[i].y, (float) corners[i].z)
                        .setColor(255, 255, 255, 255).setUv(uv[i][0], uv[i][1]).setLight(LightTexture.FULL_BRIGHT);
                }
        }
        buffers.endBatch();
    }

    /** Right-clicking a screen focuses it. */
    private static void use(InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || walls.isEmpty()) return;
        var player = Minecraft.getInstance().player;
        Vec3 eye = player.getEyePosition(), look = player.getLookAngle();
        for (var wall : walls)
            if (wall.hit(eye, look) != null) {
                Minecraft.getInstance().setScreen(new Focus(wall));
                event.setCanceled(true);
                event.setSwingHand(false);
                return;
            }
    }

    /** The world stays visible; the mouse drives the screen under the cursor. Esc leaves. */
    static final class Focus extends Screen {
        private final Wall wall;
        private boolean down;

        Focus(Wall wall) { super(Component.literal("Screen")); this.wall = wall; }

        @Override public boolean isPauseScreen() { return false; }
        @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partial) {}

        private int[] page(double mouseX, double mouseY) {
            var window = Minecraft.getInstance().getWindow();
            float x = (float) (2 * mouseX / window.getGuiScaledWidth() - 1), y = (float) (1 - 2 * mouseY / window.getGuiScaledHeight());
            var inverse = new Matrix4f(lastProjection).mul(lastModelView).invert();
            var near = inverse.transform(new Vector4f(x, y, -1, 1)); near.div(near.w);
            var far = inverse.transform(new Vector4f(x, y, 1, 1)); far.div(far.w);
            Vec3 origin = lastCamera.add(near.x, near.y, near.z);
            return wall.hit(origin, new Vec3(far.x - near.x, far.y - near.y, far.z - near.z).normalize());
        }

        private void send(int id, double mouseX, double mouseY, int mask) {
            int[] p = page(mouseX, mouseY);
            if (p != null) wall.browser.mouse(id, p[0], p[1], MouseEvent.BUTTON1, mask, 1);
        }

        @Override public void mouseMoved(double x, double y) { if (!down) send(MouseEvent.MOUSE_MOVED, x, y, 0); }
        @Override public boolean mouseClicked(double x, double y, int button) {
            if (button != 0) return false;
            down = true; send(MouseEvent.MOUSE_PRESSED, x, y, InputEvent.BUTTON1_DOWN_MASK); return true;
        }
        @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
            if (button == 0) send(MouseEvent.MOUSE_DRAGGED, x, y, InputEvent.BUTTON1_DOWN_MASK);
            return true;
        }
        @Override public boolean mouseReleased(double x, double y, int button) {
            if (button == 0) { down = false; send(MouseEvent.MOUSE_RELEASED, x, y, 0); }
            return true;
        }
        @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
            int[] p = page(x, y);
            if (p != null) wall.browser.wheel(p[0], p[1], (int) Math.round(-scrollY * 3));
            return true;
        }
    }
}
