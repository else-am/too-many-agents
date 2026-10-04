package toomanyagents;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.GlStateBackup;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** One bounded Minecraft world render from a loaded body's eyes. The displayed player camera is never changed. */
public final class PovCapture {
    private static final int RANGE = 48;
    public record Settings(int width, int height, float verticalFov) {
        public Settings {
            // Keep the view inside the existing bounded terrain scan; wider views need a renderer change.
            if (width < 64 || width > 1920 || height < 64 || height > 1080
                || !Float.isFinite(verticalFov) || verticalFov < 30 || verticalFov > 70
                || Math.tan(Math.toRadians(verticalFov / 2)) * width / height > Math.tan(Math.toRadians(35)) * 16 / 9 + 1e-6)
                throw new IllegalArgumentException("Unsupported POV dimensions or field of view");
        }
    }
    private final Supplier<String> worldSession;
    private final AtomicReference<Request> pending = new AtomicReference<>();
    private boolean rendering;

    private static final class Request {
        final UUID body;
        final String session;
        final Settings settings;
        final CompletableFuture<JsonObject> result = new CompletableFuture<>();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        ClientLevel level;
        boolean encoding;
        Request(UUID body, String session, Settings settings) { this.body = body; this.session = session; this.settings = settings; }
    }

    public PovCapture(Supplier<String> worldSession) {
        this.worldSession = Objects.requireNonNull(worldSession);
        NeoForge.EVENT_BUS.addListener(this::frame);
        NeoForge.EVENT_BUS.addListener(this::afterWeather);
    }

    public CompletableFuture<JsonObject> capture(UUID bodyUuid, String expectedWorldSession, Settings settings) {
        if (bodyUuid == null || expectedWorldSession == null || expectedWorldSession.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("A body and current world session are required."));
        }
        var request = new Request(bodyUuid, expectedWorldSession, Objects.requireNonNull(settings));
        if (!pending.compareAndSet(null, request)) {
            return CompletableFuture.failedFuture(new IllegalStateException("An NPC image is already pending."));
        }
        request.result.orTimeout(5, TimeUnit.SECONDS).whenComplete((result, failure) -> pending.compareAndSet(request, null));
        Minecraft.getInstance().execute(() -> {
            if (request.result.isDone()) return;
            try {
                request.level = Minecraft.getInstance().level;
                body(request);
            } catch (Exception failure) { request.result.completeExceptionally(failure); }
        });
        return request.result;
    }

    private Entity body(Request request) {
        var client = Minecraft.getInstance();
        if (!request.session.equals(worldSession.get()) || request.level == null || client.level != request.level) {
            throw new IllegalStateException("World changed before the NPC image was captured.");
        }
        if (!client.hasSingleplayerServer() || client.player == null || client.noRender || client.getOverlay() != null) {
            throw new IllegalStateException("An active local world is required for an NPC image.");
        }
        Entity body = request.level.getEntities().get(request.body);
        if (body == null || body.isRemoved() || !body.isAlive()) {
            throw new IllegalStateException("The NPC body is not loaded in the player's current dimension.");
        }
        return body;
    }

    private void frame(RenderFrameEvent.Pre event) {
        Request request = pending.get();
        if (request == null || request.result.isDone() || request.encoding || request.level == null) return;
        try {
            if (System.nanoTime() > request.deadline) throw new IllegalStateException("NPC image expired while waiting for loaded terrain.");
            Entity body = body(request);
            var client = Minecraft.getInstance();
            var renderer = client.levelRenderer;
            // Let the ordinary frame finish renderer rebuilds or reposition the player's chunk cache first.
            if (renderer.viewArea == null || renderer.sectionRenderDispatcher == null
                    || renderer.lastViewDistance != client.options.getEffectiveRenderDistance()
                    || renderer.lastCameraSectionX != SectionPos.posToSectionCoord(client.player.getX())
                    || renderer.lastCameraSectionY != SectionPos.posToSectionCoord(client.player.getY())
                    || renderer.lastCameraSectionZ != SectionPos.posToSectionCoord(client.player.getZ())) return;
            var camera = new BodyCamera(request.level, body);
            var projection = new Matrix4f().perspective((float)Math.toRadians(request.settings.verticalFov()), (float)request.settings.width() / request.settings.height(), 0.05F, RANGE);
            var view = new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf()));
            var frustum = new Frustum(view, projection);
            var eye = camera.getPosition();
            frustum.prepare(eye.x, eye.y, eye.z);
            List<SectionRenderDispatcher.RenderSection> sections = sections(renderer, camera, frustum);
            if (sections == null) return;
            NativeImage image = render(client, camera, projection, view, frustum, sections, request.settings);
            request.encoding = true;
            var metadata = new JsonObject();
            metadata.addProperty("ok", true);
            metadata.addProperty("bodyUuid", request.body.toString());
            metadata.addProperty("session", request.session);
            metadata.addProperty("dimension", request.level.dimension().location().toString());
            metadata.add("eye", Observations.position(eye));
            metadata.addProperty("yaw", camera.getYRot());
            metadata.addProperty("pitch", camera.getXRot());
            metadata.addProperty("width", request.settings.width());
            metadata.addProperty("height", request.settings.height());
            metadata.addProperty("verticalFov", request.settings.verticalFov());
            metadata.addProperty("renderDistanceBlocks", RANGE);
            metadata.addProperty("capturedAtMs", System.currentTimeMillis());
            metadata.addProperty("viewpoint", "npc_eyes");
            metadata.addProperty("renderer", "minecraft_world");
            Util.ioPool().execute(() -> encode(request, image, metadata));
        } catch (Exception failure) { request.result.completeExceptionally(failure); }
    }

    /** Use real compiled meshes independently of the player's visibility/occlusion graph. */
    private List<SectionRenderDispatcher.RenderSection> sections(LevelRenderer renderer, Camera camera, Frustum frustum) {
        var client = Minecraft.getInstance();
        var center = SectionPos.of(camera.getBlockPosition());
        var sections = new ArrayList<SectionRenderDispatcher.RenderSection>();
        var regionCache = new RenderRegionCache();
        boolean waiting = false;
        int scheduled = 0;
        // The frustum's far corners fit inside this 11-by-11-by-11 cube; no chunk loads or unbounded scans.
        for (int x = -5; x <= 5; x++) for (int y = -5; y <= 5; y++) for (int z = -5; z <= 5; z++) {
            var origin = new BlockPos((center.x() + x) * 16, (center.y() + y) * 16, (center.z() + z) * 16);
            if (client.level.isOutsideBuildHeight(origin)) continue;
            var section = renderer.viewArea.getRenderSectionAt(origin);
            var box = new net.minecraft.world.phys.AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 16, origin.getY() + 16, origin.getZ() + 16);
            if (!frustum.isVisible(box)) continue;
            if (section == null || !section.getOrigin().equals(origin) || !client.level.hasChunkAt(origin)) {
                throw new IllegalStateException("Terrain around the NPC is outside the client's loaded render area. Move closer to the NPC.");
            }
            if (section.isDirty() || section.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                waiting = true;
                if (section.isDirty() && scheduled++ < 8) {
                    section.rebuildSectionAsync(renderer.sectionRenderDispatcher, regionCache);
                    section.setNotDirty();
                }
            } else sections.add(section);
        }
        sections.sort(java.util.Comparator.comparingDouble(section -> section.getBoundingBox().getCenter().distanceToSqr(camera.getPosition())));
        return waiting ? null : sections;
    }

    private void afterWeather(RenderLevelStageEvent event) {
        // The fixed frustum is only a visibility lock; suppress vanilla's captured-frustum debug mesh.
        if (rendering && event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            event.getLevelRenderer().capturedFrustum = null;
        }
    }

    private NativeImage render(Minecraft client, Camera camera, Matrix4f projection, Matrix4f view, Frustum frustum,
                               List<SectionRenderDispatcher.RenderSection> sections, Settings settings) {
        // The isolated target also bounds GPU readback. No normal framebuffer pixels are overwritten.
        NativeImage image = null;
        try (var state = new RenderState(client)) {
            state.target = new TextureTarget(settings.width(), settings.height(), true, Minecraft.ON_OSX);
            client.mainRenderTarget = state.target;
            state.target.bindWrite(true);
            client.profiler = InactiveProfiler.INSTANCE;
            client.hitResult = null;
            client.crosshairPickEntity = null;
            client.gameRenderer.setPanoramicMode(true); // Vanilla's capture mode suppresses hand/outline postprocessing.
            client.gameRenderer.renderDistance = RANGE;
            client.gameRenderer.mainCamera = camera;
            var renderer = client.levelRenderer;
            renderer.cloudBuffer = null;
            renderer.generateClouds = true;
            renderer.transparencyChain = null;
            renderer.itemEntityTarget = null;
            renderer.translucentTarget = null;
            renderer.weatherTarget = null;
            renderer.capturedFrustum = frustum;
            renderer.captureFrustum = false;
            renderer.frustumPos.set(camera.getPosition().x, camera.getPosition().y, camera.getPosition().z);
            renderer.visibleSections.clear();
            renderer.visibleSections.addAll(sections);
            // Avoid changing or asynchronously rebuilding the player's occlusion/transparency caches.
            renderer.prevCamX = Math.floor(camera.getPosition().x / 8);
            renderer.prevCamY = Math.floor(camera.getPosition().y / 8);
            renderer.prevCamZ = Math.floor(camera.getPosition().z / 8);
            renderer.xTransparentOld = camera.getPosition().x;
            renderer.yTransparentOld = camera.getPosition().y;
            renderer.zTransparentOld = camera.getPosition().z;
            RenderSystem.modelViewStack = new Matrix4fStack(16);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            rendering = true;
            renderer.renderLevel(DeltaTracker.ONE, false, camera, client.gameRenderer, client.gameRenderer.lightTexture(), view, projection);
            image = Screenshot.takeScreenshot(state.target);
            return image;
        } catch (RuntimeException failure) {
            if (image != null) image.close();
            throw failure;
        } finally { rendering = false; }
    }

    private void encode(Request request, NativeImage image, JsonObject metadata) {
        try (image) {
            if (request.result.isDone()) return;
            byte[] png = image.asByteArray();
            metadata.addProperty("imageDataUrl", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            Minecraft.getInstance().execute(() -> {
                if (request.result.isDone()) return;
                try { body(request); request.result.complete(metadata); }
                catch (Exception failure) { request.result.completeExceptionally(failure); }
            });
        } catch (Exception failure) { request.result.completeExceptionally(failure); }
    }

    private static final class BodyCamera extends Camera {
        BodyCamera(ClientLevel level, Entity body) {
            setup(level, body, false, false, 1);
            setPosition(body.getEyePosition());
            setRotation(body.getViewYRot(1), body.getViewXRot(1), 0);
        }
    }

    /** Snapshot mutable render state before calling vanilla or third-party render hooks. */
    private static final class RenderState implements AutoCloseable {
        final Minecraft client;
        final List<Runnable> restore = new ArrayList<>();
        TextureTarget target;

        RenderState(Minecraft client) {
            this.client = client;
            var renderer = client.levelRenderer;
            RenderTarget main = client.getMainRenderTarget();
            var profiler = client.profiler;
            var hit = client.hitResult;
            var crosshair = client.crosshairPickEntity;
            boolean panoramic = client.gameRenderer.isPanoramicMode();
            float distance = client.gameRenderer.renderDistance;
            var mainCamera = client.gameRenderer.getMainCamera();
            restore.add(() -> {
                client.mainRenderTarget = main;
                client.profiler = profiler;
                client.hitResult = hit;
                client.crosshairPickEntity = crosshair;
                client.gameRenderer.setPanoramicMode(panoramic);
                client.gameRenderer.renderDistance = distance;
                client.gameRenderer.mainCamera = mainCamera;
            });
            var visible = new ArrayList<>(renderer.visibleSections);
            var frustum = renderer.capturedFrustum;
            var frustumPosition = new Vector3d(renderer.frustumPos);
            boolean captureFrustum = renderer.captureFrustum;
            var sectionCamera = renderer.sectionRenderDispatcher.getCameraPosition();
            double px = renderer.prevCamX, py = renderer.prevCamY, pz = renderer.prevCamZ;
            double tx = renderer.xTransparentOld, ty = renderer.yTransparentOld, tz = renderer.zTransparentOld;
            var chain = renderer.transparencyChain;
            var items = renderer.itemEntityTarget;
            var translucent = renderer.translucentTarget;
            var weather = renderer.weatherTarget;
            restore.add(() -> {
                renderer.visibleSections.clear();
                renderer.visibleSections.addAll(visible);
                renderer.capturedFrustum = frustum;
                renderer.captureFrustum = captureFrustum;
                renderer.frustumPos.set(frustumPosition);
                renderer.sectionRenderDispatcher.setCamera(sectionCamera);
                renderer.prevCamX = px; renderer.prevCamY = py; renderer.prevCamZ = pz;
                renderer.xTransparentOld = tx; renderer.yTransparentOld = ty; renderer.zTransparentOld = tz;
                renderer.transparencyChain = chain;
                renderer.itemEntityTarget = items;
                renderer.translucentTarget = translucent;
                renderer.weatherTarget = weather;
            });
            var cloudBuffer = renderer.cloudBuffer;
            boolean generateClouds = renderer.generateClouds;
            int cloudX = renderer.prevCloudX, cloudY = renderer.prevCloudY, cloudZ = renderer.prevCloudZ;
            var cloudColor = renderer.prevCloudColor;
            var cloudsType = renderer.prevCloudsType;
            int renderedEntities = renderer.renderedEntities, culledEntities = renderer.culledEntities;
            boolean outlineRequested = renderer.outlineEffectRequested;
            restore.add(() -> {
                var temporaryCloudBuffer = renderer.cloudBuffer;
                renderer.cloudBuffer = cloudBuffer;
                renderer.generateClouds = generateClouds;
                renderer.prevCloudX = cloudX; renderer.prevCloudY = cloudY; renderer.prevCloudZ = cloudZ;
                renderer.prevCloudColor = cloudColor; renderer.prevCloudsType = cloudsType;
                renderer.renderedEntities = renderedEntities; renderer.culledEntities = culledEntities;
                renderer.outlineEffectRequested = outlineRequested;
                if (temporaryCloudBuffer != null && temporaryCloudBuffer != cloudBuffer) temporaryCloudBuffer.close();
            });
            var entities = client.getEntityRenderDispatcher();
            var entityCamera = entities.camera;
            var entityCrosshair = entities.crosshairPickEntity;
            var orientation = entities.cameraOrientation();
            var blocks = client.getBlockEntityRenderDispatcher();
            var blockCamera = blocks.camera;
            var blockHit = blocks.cameraHitResult;
            restore.add(() -> {
                entities.camera = entityCamera;
                entities.crosshairPickEntity = entityCrosshair;
                entities.overrideCameraOrientation(orientation);
                blocks.camera = blockCamera;
                blocks.cameraHitResult = blockHit;
            });
            float red = FogRenderer.fogRed, green = FogRenderer.fogGreen, blue = FogRenderer.fogBlue;
            int targetFog = FogRenderer.targetBiomeFog, previousFog = FogRenderer.previousBiomeFog;
            long biomeTime = FogRenderer.biomeChangedTime;
            restore.add(() -> {
                FogRenderer.fogRed = red; FogRenderer.fogGreen = green; FogRenderer.fogBlue = blue;
                FogRenderer.targetBiomeFog = targetFog; FogRenderer.previousBiomeFog = previousFog;
                FogRenderer.biomeChangedTime = biomeTime;
            });
            var gl = new GlStateBackup();
            RenderSystem.backupGlState(gl);
            var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
            var sorting = RenderSystem.getVertexSorting();
            var matrixStack = RenderSystem.getModelViewStack();
            var texture = new Matrix4f(RenderSystem.getTextureMatrix());
            var shader = RenderSystem.getShader();
            var color = RenderSystem.getShaderColor().clone();
            var fogColor = RenderSystem.getShaderFogColor().clone();
            float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
            var fogShape = RenderSystem.getShaderFogShape();
            float gameTime = RenderSystem.getShaderGameTime(), lineWidth = RenderSystem.getShaderLineWidth();
            var lights = RenderSystem.shaderLightDirections.clone();
            int[] textures = new int[12];
            for (int i = 0; i < textures.length; i++) textures[i] = RenderSystem.getShaderTexture(i);
            int[] viewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            float[] clearColor = new float[4];
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
            int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int[] boundTextures = new int[12];
            for (int i = 0; i < boundTextures.length; i++) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                boundTextures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            RenderSystem.activeTexture(activeTexture);
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            restore.add(() -> {
                RenderSystem.restoreGlState(gl);
                RenderSystem.setProjectionMatrix(projection, sorting);
                RenderSystem.modelViewStack = matrixStack;
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setTextureMatrix(texture);
                RenderSystem.setShader(() -> shader);
                RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
                RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
                RenderSystem.setShaderFogStart(fogStart); RenderSystem.setShaderFogEnd(fogEnd);
                RenderSystem.setShaderFogShape(fogShape);
                RenderSystem.shaderGameTime = gameTime;
                RenderSystem.lineWidth(lineWidth);
                RenderSystem.shaderLightDirections[0] = lights[0]; RenderSystem.shaderLightDirections[1] = lights[1];
                for (int i = 0; i < textures.length; i++) RenderSystem.setShaderTexture(i, textures[i]);
                for (int i = 0; i < boundTextures.length; i++) {
                    RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                    RenderSystem.bindTexture(boundTextures[i]);
                }
                RenderSystem.activeTexture(activeTexture);
                GlStateManager._glUseProgram(program);
                GlStateManager._glBindVertexArray(vertexArray);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
                RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            });
        }

        @Override public void close() {
            // A failed entity renderer may leave a batch or matrix push unfinished.
            try { client.renderBuffers().bufferSource().endBatch(); } catch (RuntimeException ignored) {}
            try { client.renderBuffers().crumblingBufferSource().endBatch(); } catch (RuntimeException ignored) {}
            try { client.renderBuffers().outlineBufferSource().endOutlineBatch(); } catch (RuntimeException ignored) {}
            RuntimeException failure = null;
            try { if (target != null) target.destroyBuffers(); } catch (RuntimeException e) { failure = e; }
            for (Runnable step : restore) {
                try { step.run(); }
                catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (failure != null) throw failure;
        }
    }
}
