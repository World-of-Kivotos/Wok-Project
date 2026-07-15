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

/** Lightweight WARTECH TV-115 skeleton chest-rig model. */
public final class Tv115ArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_tv115"), "main");

    private static final CubeDeformation RIG_DEFORMATION = new CubeDeformation(0.12F);

    public Tv115ArmorModel(ModelPart root) {
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
        CubeListBuilder body = CubeListBuilder.create()
                .texOffs(0, 0)
                .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, RIG_DEFORMATION)
                .texOffs(25, 0)
                .addBox(-3.35F, 1.60F, -2.68F, 6.70F, 7.20F, 0.34F)
                .texOffs(41, 0)
                .addBox(-3.25F, 1.20F, 2.34F, 6.50F, 7.40F, 0.34F)
                .texOffs(56, 0)
                .addBox(-3.20F, -0.20F, -2.78F, 1.05F, 3.90F, 0.36F)
                .texOffs(56, 0)
                .addBox(2.15F, -0.20F, -2.78F, 1.05F, 3.90F, 0.36F)
                .texOffs(61, 0)
                .addBox(-3.20F, -0.20F, 2.42F, 1.05F, 3.90F, 0.36F)
                .texOffs(61, 0)
                .addBox(2.15F, -0.20F, 2.42F, 1.05F, 3.90F, 0.36F)
                .texOffs(10, 20)
                .addBox(-3.10F, 3.55F, -2.84F, 6.20F, 0.22F, 0.10F)
                .texOffs(10, 20)
                .addBox(-3.10F, 4.30F, -2.84F, 6.20F, 0.22F, 0.10F)
                .texOffs(24, 20)
                .addBox(-3.70F, 9.10F, -2.70F, 7.40F, 0.90F, 0.30F)
                .texOffs(24, 20)
                .addBox(-3.70F, 9.10F, 2.40F, 7.40F, 0.90F, 0.30F)
                .texOffs(10, 28)
                .addBox(-3.05F, 2.05F, -3.06F, 0.80F, 2.90F, 0.18F)
                .texOffs(10, 28)
                .addBox(2.25F, 2.05F, -3.06F, 0.80F, 2.90F, 0.18F)
                .texOffs(15, 28)
                .addBox(-1.95F, 3.15F, -3.00F, 0.55F, 0.70F, 0.12F)
                .texOffs(15, 28)
                .addBox(1.40F, 3.15F, -3.00F, 0.55F, 0.70F, 0.12F);

        for (int row = 0; row < 3; row++) {
            float y = 5.10F + row * 1.55F;
            body.texOffs(0, 20).addBox(-4.40F, y, -2.08F, 0.22F, 0.28F, 4.16F);
            body.texOffs(0, 20).addBox(4.18F, y, -2.08F, 0.22F, 0.28F, 4.16F);
        }

        for (int column = 0; column < 4; column++) {
            float x = -3.05F + column * 1.55F;
            body.texOffs(0, 28).addBox(x, 5.15F, -3.10F, 1.35F, 4.35F, 0.30F);
            body.texOffs(5, 28).addBox(x + 0.15F, 4.45F, -3.14F, 1.05F, 1.10F, 0.24F);
        }
        return body;
    }

    private static CubeListBuilder createShoulderStrap() {
        return CubeListBuilder.create()
                .texOffs(20, 28)
                .addBox(-1.85F, -2.25F, -2.46F, 3.70F, 0.32F, 4.92F);
    }
}
