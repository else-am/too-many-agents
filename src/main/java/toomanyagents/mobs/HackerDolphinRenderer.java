package toomanyagents.mobs;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.DolphinModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.DolphinRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Dolphin;

/** A vanilla dolphin wearing Jones's rig from Johnny Mnemonic. */
final class HackerDolphinRenderer extends DolphinRenderer {
    static final ModelLayerLocation GEAR = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath("too_many_agents", "hacker_dolphin"), "gear");
    private static final ResourceLocation GEAR_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("too_many_agents", "textures/entity/hacker_dolphin_gear.png");

    HackerDolphinRenderer(EntityRendererProvider.Context context) {
        super(context);
        var gear = new DolphinModel<Dolphin>(context.bakeLayer(GEAR));
        addLayer(new RenderLayer<>(this) {
            @Override
            public void render(PoseStack pose, MultiBufferSource buffers, int light, Dolphin dolphin, float limbSwing,
                               float limbSwingAmount, float partialTick, float age, float headYaw, float headPitch) {
                coloredCutoutModelCopyLayerRender(getParentModel(), gear, GEAR_TEXTURE, pose, buffers, light, dolphin,
                    limbSwing, limbSwingAmount, age, headYaw, headPitch, partialTick, -1);
            }
        });
    }

    /** Gear only. Its parts share the dolphin's names and pivots, so DolphinModel animates it in step with the body. */
    static LayerDefinition gear() {
        var mesh = new MeshDefinition();
        // +X is the dolphin's left side, where its wiring runs from the eye socket back into the rig.
        var body = mesh.getRoot().addOrReplaceChild("body", CubeListBuilder.create()
            .texOffs(0, 28).addBox(-4, -7, 2, 8, 7, 8, new CubeDeformation(0.4F))  // saddle wrapped around the body
            .texOffs(0, 0).addBox(-3, -9.4F, 3, 6, 2, 6)                          // deck
            .texOffs(0, 10).addBox(-2.5F, -12.4F, 4.5F, 2, 3, 2)                        // twin canisters, either side of the fin
            .texOffs(0, 10).addBox(0.5F, -12.4F, 4.5F, 2, 3, 2)
            .texOffs(46, 8).addBox(4.4F, -5, 3, 1, 3, 4)                             // flank modules
            .texOffs(46, 8).mirror().addBox(-5.4F, -5, 3, 1, 3, 4).mirror(false)
            .texOffs(30, 24).addBox(-3, -7.6F, 10, 6, 1, 3)                          // spine plate toward the tail
            .texOffs(46, 0).addBox(4.3F, -9.4F, 2.5F, 1, 5, 1)                      // cable rising to the deck
            .texOffs(52, 0).addBox(3, -9.4F, 2.5F, 2, 1, 1),
            PartPose.offset(0, 22, -5));
        var head = body.addOrReplaceChild("head", CubeListBuilder.create()
            .texOffs(0, 16).addBox(-4, -3, -3, 8, 5, 6, new CubeDeformation(0.3F))  // head plate
            .texOffs(30, 0).addBox(4.3F, -1.5F, -1.5F, 1, 2, 2)                     // eye socket
            .texOffs(30, 6).addBox(4.3F, -1, 0.5F, 1, 1, 6),                        // cable
            PartPose.offset(0, -4, -3));
        head.addOrReplaceChild("nose", CubeListBuilder.create()
            .texOffs(30, 16).addBox(-1, 2, -7, 2, 2, 4, new CubeDeformation(0.3F)), PartPose.ZERO);  // nose sheath
        var tail = body.addOrReplaceChild("tail", CubeListBuilder.create()
            .texOffs(44, 16).addBox(-2, -4.4F, 1.5F, 4, 2, 4)                        // spine plates stepping down the tail
            .texOffs(48, 24).addBox(-1.5F, -3.6F, 5.5F, 3, 1, 3),
            PartPose.offsetAndRotation(0, -2.5F, 11, -0.10471976F, 0, 0));
        tail.addOrReplaceChild("tail_fin", CubeListBuilder.create(), PartPose.offset(0, 0, 9));
        return LayerDefinition.create(mesh, 64, 64);
    }
}
