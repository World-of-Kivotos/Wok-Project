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

/** Lightweight Eagle Allied Industries MBSS plate-carrier model. */
public final class MbssArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_mbss"), "main");

    private static final CubeDeformation RIG_DEFORMATION = new CubeDeformation(0.16F);

    public MbssArmorModel(ModelPart root) {
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
        root.addOrReplaceChild("right_arm", createShoulderStrap(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", createShoulderStrap(), PartPose.offset(5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        return CubeListBuilder.create()
                .texOffs(0, 0)
                .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, RIG_DEFORMATION)
                .texOffs(25, 0)
                .addBox(-3.55F, 1.15F, -2.74F, 7.10F, 7.70F, 0.40F)
                .texOffs(42, 0)
                .addBox(-3.40F, 1.30F, 2.34F, 6.80F, 7.40F, 0.36F)
                .texOffs(58, 0)
                .addBox(-4.52F, 5.30F, -2.05F, 0.30F, 5.20F, 4.10F)
                .texOffs(58, 0)
                .addBox(4.22F, 5.30F, -2.05F, 0.30F, 5.20F, 4.10F)
                .texOffs(68, 0)
                .addBox(-3.50F, -0.25F, -2.82F, 1.15F, 4.10F, 0.40F)
                .texOffs(68, 0)
                .addBox(2.35F, -0.25F, -2.82F, 1.15F, 4.10F, 0.40F)
                .texOffs(73, 0)
                .addBox(-3.50F, -0.25F, 2.42F, 1.15F, 4.10F, 0.40F)
                .texOffs(73, 0)
                .addBox(2.35F, -0.25F, 2.42F, 1.15F, 4.10F, 0.40F)
                .texOffs(0, 20)
                .addBox(-2.55F, 2.15F, -2.94F, 5.10F, 1.25F, 0.16F)
                .texOffs(12, 20)
                .addBox(-3.10F, 4.00F, -2.90F, 6.20F, 0.22F, 0.12F)
                .texOffs(12, 20)
                .addBox(-3.10F, 4.80F, -2.90F, 6.20F, 0.22F, 0.12F)
                .texOffs(28, 20)
                .addBox(-2.45F, 2.40F, -3.12F, 0.60F, 0.75F, 0.14F)
                .texOffs(28, 20)
                .addBox(1.85F, 2.40F, -3.12F, 0.60F, 0.75F, 0.14F)
                .texOffs(0, 28)
                .addBox(-3.20F, 6.15F, -3.16F, 2.00F, 4.80F, 0.34F)
                .texOffs(0, 28)
                .addBox(-1.00F, 6.15F, -3.16F, 2.00F, 4.80F, 0.34F)
                .texOffs(0, 28)
                .addBox(1.20F, 6.15F, -3.16F, 2.00F, 4.80F, 0.34F)
                .texOffs(6, 28)
                .addBox(-3.20F, 5.10F, -3.28F, 2.00F, 1.00F, 0.18F)
                .texOffs(6, 28)
                .addBox(-1.00F, 5.10F, -3.28F, 2.00F, 1.00F, 0.18F)
                .texOffs(6, 28)
                .addBox(1.20F, 5.10F, -3.28F, 2.00F, 1.00F, 0.18F)
                .texOffs(12, 28)
                .addBox(-4.88F, 6.40F, -1.55F, 0.30F, 3.40F, 3.10F)
                .texOffs(12, 28)
                .addBox(4.58F, 6.40F, -1.55F, 0.30F, 3.40F, 3.10F)
                .texOffs(20, 28)
                .addBox(-3.75F, 10.35F, -2.66F, 7.50F, 1.00F, 0.28F)
                .texOffs(20, 28)
                .addBox(-3.75F, 10.35F, 2.38F, 7.50F, 1.00F, 0.28F);
    }

    private static CubeListBuilder createShoulderStrap() {
        return CubeListBuilder.create()
                .texOffs(38, 28)
                .addBox(-1.55F, -2.30F, -2.46F, 3.10F, 0.36F, 4.92F);
    }
}
