package com.kivotos.armorer.armor.client;

import com.kivotos.armorer.ArmorerMod;
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

/** Shellback Banshee carrier with distinct front-left IFAK and front-right utility cells. */
public final class BansheeArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_banshee_atacs_au"), "main");

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
                .addBox(-3.98F, 4.30F, -2.18F, 0.52F, 6.40F, 4.36F)
                .texOffs(75, 0)
                .addBox(3.46F, 4.30F, -2.18F, 0.52F, 6.40F, 4.36F)
                .texOffs(100, 0)
                .addBox(-3.64F, -0.24F, -2.76F, 1.65F, 4.20F, 0.56F)
                .texOffs(100, 0)
                .addBox(1.97F, -0.24F, -2.76F, 1.65F, 4.20F, 0.56F)
                .texOffs(0, 22)
                .addBox(-3.64F, -0.24F, 2.20F, 1.65F, 4.20F, 0.56F)
                .texOffs(0, 22)
                .addBox(1.97F, -0.24F, 2.20F, 1.65F, 4.20F, 0.56F)
                .texOffs(25, 22)
                .addBox(-3.67F, -0.44F, -2.24F, 1.59F, 0.62F, 4.48F)
                .texOffs(25, 22)
                .addBox(2.08F, -0.44F, -2.24F, 1.59F, 0.62F, 4.48F)
                .texOffs(50, 22)
                .addBox(-3.0F, 1.45F, -3.0F, 6.0F, 1.55F, 0.34F)
                .texOffs(75, 22)
                .addBox(-2.10F, 3.18F, -3.10F, 4.20F, 0.48F, 0.42F)
                .texOffs(25, 44)
                .addBox(-4.92F, 5.05F, -3.42F, 2.20F, 4.65F, 1.20F)
                .texOffs(50, 44)
                .addBox(-4.84F, 4.55F, -3.56F, 2.04F, 1.05F, 0.34F)
                .texOffs(75, 44)
                .addBox(-4.35F, 6.05F, -3.60F, 1.00F, 1.00F, 0.22F)
                .texOffs(100, 44)
                .addBox(-3.97F, 7.20F, -3.54F, 0.22F, 2.30F, 0.18F)
                .texOffs(0, 66)
                .addBox(2.78F, 5.55F, -3.38F, 2.15F, 4.10F, 1.12F)
                .texOffs(0, 88)
                .addBox(2.84F, 5.00F, -3.52F, 2.03F, 1.00F, 0.32F)
                .texOffs(25, 88)
                .addBox(3.76F, 6.30F, -3.50F, 0.18F, 3.05F, 0.18F)
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
            float x = -2.62F + column * 1.78F;
            body.texOffs(100, 22).addBox(x, 5.38F, -3.18F, 1.62F, 4.55F, 0.56F);
            body.texOffs(0, 44).addBox(x - 0.04F, 4.38F, -3.32F, 1.70F, 1.18F, 0.26F);
            for (int row = 0; row < 2; row++) {
                body.texOffs(50, 88).addBox(x + 0.18F, 6.55F + row * 1.35F, -3.30F, 1.26F, 0.18F, 0.20F);
            }
        }
        for (int row = 0; row < 3; row++) {
            body.texOffs(25, 66).addBox(-3.20F, 3.95F + row * 0.78F, -2.92F, 6.40F, 0.20F, 0.24F);
        }
        return body;
    }
}

