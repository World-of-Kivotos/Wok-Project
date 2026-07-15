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

/** Shellback Banshee carrier with broad padded straps and a three-magazine front bank. */
public final class BansheeArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_banshee_atacs_au"), "main");

    private static final CubeDeformation CARRIER_DEFORMATION = new CubeDeformation(0.14F);

    public BansheeArmorModel(ModelPart root) {
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
                .addBox(-3.72F, 1.05F, -2.72F, 7.44F, 9.45F, 0.64F)
                .texOffs(50, 0)
                .addBox(-3.72F, 1.05F, 2.08F, 7.44F, 9.45F, 0.64F)
                .texOffs(75, 0)
                .addBox(-4.62F, 4.30F, -2.18F, 0.52F, 6.40F, 4.36F)
                .texOffs(75, 0)
                .addBox(4.10F, 4.30F, -2.18F, 0.52F, 6.40F, 4.36F)
                .texOffs(100, 0)
                .addBox(-3.70F, -0.24F, -2.76F, 1.65F, 4.20F, 0.56F)
                .texOffs(100, 0)
                .addBox(2.05F, -0.24F, -2.76F, 1.65F, 4.20F, 0.56F)
                .texOffs(0, 22)
                .addBox(-3.70F, -0.24F, 2.20F, 1.65F, 4.20F, 0.56F)
                .texOffs(0, 22)
                .addBox(2.05F, -0.24F, 2.20F, 1.65F, 4.20F, 0.56F)
                .texOffs(25, 22)
                .addBox(-3.67F, -0.44F, -2.24F, 1.59F, 0.62F, 4.48F)
                .texOffs(25, 22)
                .addBox(2.08F, -0.44F, -2.24F, 1.59F, 0.62F, 4.48F)
                .texOffs(50, 22)
                .addBox(-3.0F, 1.45F, -3.0F, 6.0F, 1.55F, 0.34F)
                .texOffs(75, 22)
                .addBox(-2.10F, 3.18F, -3.10F, 4.20F, 0.48F, 0.42F)
                .texOffs(25, 44)
                .addBox(-5.22F, 5.05F, -1.70F, 0.64F, 4.35F, 3.40F)
                .texOffs(50, 44)
                .addBox(-5.36F, 4.32F, -1.74F, 0.32F, 0.84F, 3.48F)
                .texOffs(75, 44)
                .addBox(4.58F, 5.05F, -1.42F, 0.40F, 3.60F, 2.84F)
                .texOffs(100, 44)
                .addBox(4.72F, 3.45F, -0.55F, 0.42F, 3.15F, 1.10F)
                .texOffs(0, 66)
                .addBox(4.58F, 8.70F, -1.64F, 0.64F, 3.0F, 3.28F)
                .texOffs(50, 66)
                .addBox(-3.80F, 10.05F, -2.96F, 7.60F, 1.02F, 0.42F)
                .texOffs(50, 66)
                .addBox(-3.80F, 10.05F, 2.54F, 7.60F, 1.02F, 0.42F)
                .texOffs(75, 66)
                .addBox(-0.14F, 10.85F, -3.02F, 0.28F, 2.15F, 0.26F)
                .texOffs(100, 66)
                .addBox(-3.42F, 4.22F, -3.18F, 0.62F, 0.72F, 0.20F)
                .texOffs(100, 66)
                .addBox(2.80F, 4.22F, -3.18F, 0.62F, 0.72F, 0.20F);

        for (int column = 0; column < 3; column++) {
            float x = -2.95F + column * 2.05F;
            body.texOffs(100, 22).addBox(x, 5.38F, -3.16F, 1.80F, 4.60F, 0.52F);
            body.texOffs(0, 44).addBox(x - 0.03F, 4.42F, -3.28F, 1.86F, 1.08F, 0.24F);
        }
        for (int row = 0; row < 3; row++) {
            body.texOffs(25, 66).addBox(-3.20F, 3.95F + row * 0.78F, -2.92F, 6.40F, 0.20F, 0.24F);
        }
        return body;
    }
}
