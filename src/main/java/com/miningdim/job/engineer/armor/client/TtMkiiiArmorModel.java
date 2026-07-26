package com.miningdim.job.engineer.armor.client;

import com.miningdim.core.MiningConstants;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/** TT MKIII with thick shoulder straps, three flat pouches and asymmetric radio/tool modules. */
public final class TtMkiiiArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_tt_mkiii_coyote"), "main");

    private static final CubeDeformation CARRIER_DEFORMATION = new CubeDeformation(0.08F);

    public TtMkiiiArmorModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("body", createBody(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        CubeListBuilder body = CubeListBuilder.create()
                .texOffs(0, 0)
                .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, CARRIER_DEFORMATION)
                .texOffs(25, 0)
                .addBox(-3.45F, 0.75F, -2.64F, 6.90F, 8.70F, 0.55F)
                .texOffs(42, 0)
                .addBox(-3.45F, 0.75F, 2.09F, 6.90F, 8.70F, 0.55F)
                .texOffs(59, 0)
                .addBox(-3.96F, 4.35F, -2.00F, 0.36F, 6.00F, 4.00F)
                .texOffs(59, 0)
                .addBox(3.60F, 4.35F, -2.00F, 0.36F, 6.00F, 4.00F)

                // Thick padding remains on the torso and leaves the arm bones completely empty.
                .texOffs(69, 0)
                .addBox(-3.70F, -0.25F, -2.92F, 1.60F, 3.60F, 0.65F)
                .texOffs(69, 0)
                .addBox(2.10F, -0.25F, -2.92F, 1.60F, 3.60F, 0.65F)
                .texOffs(75, 0)
                .addBox(-3.70F, -0.25F, 2.27F, 1.60F, 3.60F, 0.65F)
                .texOffs(75, 0)
                .addBox(2.10F, -0.25F, 2.27F, 1.60F, 3.60F, 0.65F)
                .texOffs(81, 0)
                .addBox(-3.60F, -0.48F, -2.20F, 1.40F, 0.65F, 4.40F)
                .texOffs(81, 0)
                .addBox(2.20F, -0.48F, -2.20F, 1.40F, 0.65F, 4.40F)
                .texOffs(93, 0)
                .addBox(-2.80F, 1.55F, -2.96F, 5.60F, 1.05F, 0.22F)

                // Left radio/cylinder carrier and right tool module stay forward of the arms.
                .texOffs(0, 20)
                .addBox(-5.28F, 5.20F, -3.62F, 2.00F, 4.60F, 1.35F)
                .texOffs(8, 20)
                .addBox(-5.38F, 4.92F, -3.75F, 2.20F, 1.00F, 1.50F)
                .texOffs(23, 20)
                .addBox(3.28F, 5.35F, -3.57F, 1.75F, 4.20F, 1.25F)
                .texOffs(30, 20)
                .addBox(3.18F, 5.08F, -3.70F, 1.95F, 1.00F, 1.40F)
                .texOffs(38, 20)
                .addBox(4.15F, 5.70F, -3.88F, 0.45F, 3.60F, 0.45F)
                .texOffs(41, 20)
                .addBox(4.08F, 7.05F, -4.02F, 0.44F, 0.40F, 0.18F)
                .texOffs(56, 20)
                .addBox(2.90F, 2.10F, -3.24F, 0.20F, 2.80F, 0.20F)
                .texOffs(119, 0)
                .addBox(-3.15F, 2.78F, -3.12F, 0.60F, 0.65F, 0.22F)
                .texOffs(119, 0)
                .addBox(2.55F, 2.78F, -3.12F, 0.60F, 0.65F, 0.22F);

        for (int row = 0; row < 4; row++) {
            body.texOffs(105, 0).addBox(-3.00F, 3.10F + row * 0.72F, -3.02F,
                    6.00F, 0.18F, 0.42F);
        }
        for (int cylinder = 0; cylinder < 2; cylinder++) {
            float x = -5.05F + cylinder * 0.72F;
            body.texOffs(17, 20).addBox(x, 5.72F, -3.95F, 0.45F, 3.80F, 0.45F);
            body.texOffs(20, 20).addBox(x - 0.03F, 7.05F, -4.08F, 0.50F, 0.35F, 0.18F);
        }
        for (int column = 0; column < 3; column++) {
            float x = -2.80F + column * 2.00F;
            body.texOffs(44, 20).addBox(x, 5.55F, -3.20F, 1.75F, 3.00F, 0.62F);
            body.texOffs(50, 20).addBox(x - 0.05F, 5.28F, -3.34F, 1.85F, 0.75F, 0.22F);
        }
        body.texOffs(4, 40).addBox(-3.38F, 8.75F, -3.75F, 0.42F, 0.42F, 0.25F);
        return body;
    }
}
