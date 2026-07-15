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

/** Medium-weight blue MF-UNTAR soft-armor vest model. */
public final class MfUntarArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_mf_untar"), "main");

    private static final CubeDeformation SOFT_ARMOR_DEFORMATION = new CubeDeformation(0.30F);
    private static final CubeDeformation SHOULDER_DEFORMATION = new CubeDeformation(0.20F);

    public MfUntarArmorModel(ModelPart root) {
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
        root.addOrReplaceChild("right_arm", createRightShoulder(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", createLeftShoulder(), PartPose.offset(5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        CubeListBuilder body = CubeListBuilder.create()
                .texOffs(0, 0)
                .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, SOFT_ARMOR_DEFORMATION)
                .texOffs(25, 0)
                .addBox(-3.80F, 1.00F, -2.72F, 7.60F, 10.40F, 0.36F)
                .texOffs(42, 0)
                .addBox(-3.80F, 0.90F, 2.36F, 7.60F, 10.50F, 0.36F)
                .texOffs(59, 0)
                .addBox(-4.72F, 2.90F, -2.34F, 0.36F, 8.50F, 4.68F)
                .texOffs(59, 0)
                .addBox(4.36F, 2.90F, -2.34F, 0.36F, 8.50F, 4.68F)
                .texOffs(71, 0)
                .addBox(-3.65F, -0.35F, -2.82F, 1.55F, 3.65F, 0.40F)
                .texOffs(71, 0)
                .addBox(2.10F, -0.35F, -2.82F, 1.55F, 3.65F, 0.40F)
                .texOffs(76, 0)
                .addBox(-3.65F, -0.35F, 2.42F, 1.55F, 3.65F, 0.40F)
                .texOffs(76, 0)
                .addBox(2.10F, -0.35F, 2.42F, 1.55F, 3.65F, 0.40F)
                .texOffs(0, 20)
                .addBox(-2.65F, 1.55F, -2.98F, 5.30F, 2.15F, 0.18F)
                .texOffs(12, 20)
                .addBox(-1.80F, 2.05F, -3.14F, 3.60F, 1.05F, 0.12F)
                .texOffs(38, 20)
                .addBox(-3.80F, 11.45F, -2.86F, 7.60F, 0.90F, 0.30F)
                .texOffs(38, 20)
                .addBox(-3.80F, 11.45F, 2.56F, 7.60F, 0.90F, 0.30F);

        for (int row = 0; row < 6; row++) {
            body.texOffs(22, 20).addBox(
                    -3.45F,
                    4.35F + row * 1.05F,
                    -2.92F,
                    6.90F,
                    0.24F,
                    0.14F);
        }

        for (int row = 0; row < 2; row++) {
            float y = 6.20F + row * 1.90F;
            body.texOffs(55, 20).addBox(-4.90F, y, -2.10F, 0.12F, 0.28F, 4.20F);
            body.texOffs(55, 20).addBox(4.78F, y, -2.10F, 0.12F, 0.28F, 4.20F);
        }
        return body;
    }

    private static CubeListBuilder createRightShoulder() {
        return CubeListBuilder.create()
                .texOffs(65, 20)
                .addBox(-2.70F, -2.00F, -2.00F, 3.35F, 3.10F, 4.00F, SHOULDER_DEFORMATION)
                .texOffs(81, 20)
                .addBox(-2.85F, -2.34F, -2.46F, 4.25F, 0.24F, 4.92F);
    }

    private static CubeListBuilder createLeftShoulder() {
        return CubeListBuilder.create()
                .texOffs(65, 20)
                .addBox(-0.65F, -2.00F, -2.00F, 3.35F, 3.10F, 4.00F, SHOULDER_DEFORMATION)
                .texOffs(81, 20)
                .addBox(-1.40F, -2.34F, -2.46F, 4.25F, 0.24F, 4.92F);
    }
}
