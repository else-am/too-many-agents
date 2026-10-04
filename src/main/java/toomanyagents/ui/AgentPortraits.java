package toomanyagents.ui;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.GlStateBackup;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Still profile images, generated once per body type without reading any agent's world entity. */
final class AgentPortraits implements AutoCloseable {
    private static final int SIZE = 96;
    private final Map<String, ResourceLocation> images = new HashMap<>();

    void render(GuiGraphics graphics, String body, int x, int y, int size) {
        var image = images.get(body);
        if (image == null) {
            graphics.flush();
            NativeImage pixels;
            try { pixels = capture(body); }
            catch (RuntimeException failure) {
                LogUtils.getLogger().warn("Could not create profile image for {}", body, failure);
                pixels = placeholder();
            }
            image = Minecraft.getInstance().getTextureManager().register("agent_profile", new DynamicTexture(pixels));
            images.put(body, image);
        }
        graphics.blit(image, x, y, size, size, 0, 0, SIZE, SIZE, SIZE, SIZE);
    }

    private NativeImage capture(String body) {
        var client = Minecraft.getInstance();
        var id = ResourceLocation.tryParse(body);
        var type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (client.level == null || type == null || !(type.create(client.level) instanceof LivingEntity model))
            return placeholder();
        // This temporary model is never spawned or ticked. Only its image is retained.
        model.yBodyRot = model.yHeadRot = model.yHeadRotO = 180;
        model.setYRot(180);
        var state = new GlStateBackup();
        RenderSystem.backupGlState(state);
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var lights = RenderSystem.shaderLightDirections.clone();
        var camera = new Quaternionf(client.getEntityRenderDispatcher().cameraOrientation());
        var matrices = RenderSystem.getModelViewStack();
        matrices.pushMatrix();
        TextureTarget target = null;
        try {
            RenderSystem.disableScissor();
            target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
            target.setClearColor(17 / 255F, 23 / 255F, 25 / 255F, 1);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            matrices.identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, SIZE, SIZE, 0, -1000, 1000), VertexSorting.ORTHOGRAPHIC_Z);
            var graphics = new GuiGraphics(client, client.renderBuffers().bufferSource());
            float scale = SIZE * 0.85F / Math.max(0.5F, model.getBbWidth());
            InventoryScreen.renderEntityInInventory(graphics, SIZE / 2F, SIZE * 0.42F, scale,
                new Vector3f(0, model.getEyeHeight(), 0), new Quaternionf().rotateZ((float)Math.PI), new Quaternionf(), model);
            return Screenshot.takeScreenshot(target);
        } finally {
            if (target != null) target.destroyBuffers();
            client.getMainRenderTarget().bindWrite(true);
            matrices.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShaderLights(lights[0], lights[1]);
            client.getEntityRenderDispatcher().overrideCameraOrientation(camera);
            client.getEntityRenderDispatcher().setRenderShadow(true);
            RenderSystem.restoreGlState(state);
        }
    }

    private static NativeImage placeholder() {
        var image = new NativeImage(SIZE, SIZE, false);
        image.fillRect(0, 0, SIZE, SIZE, 0xFF191711);
        image.fillRect(34, 18, 28, 28, 0xFF9C9E92);
        image.fillRect(22, 54, 52, 30, 0xFF9C9E92);
        return image;
    }

    @Override public void close() {
        var textures = Minecraft.getInstance().getTextureManager();
        images.values().forEach(textures::release);
        images.clear();
    }
}
