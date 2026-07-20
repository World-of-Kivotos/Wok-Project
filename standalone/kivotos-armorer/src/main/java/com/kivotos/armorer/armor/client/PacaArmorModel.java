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

/** PACA soft concealable vest with broad fabric panels and no rigid external pouches. */
public final class PacaArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_paca"), "main");

    private static final CubeDeformation SOFT_PADDING = new CubeDeformation(0.22F);

    public PacaArmorModel(ModelPart root) {
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
        root.addOrReplaceChild("right_arm", createRightSoftShoulder(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", createLeftSoftShoulder(), PartPose.offset(5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        return CubeListBuilder.create()
                .texOffs(0, 48)
                .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 11.50F, 4.0F, SOFT_PADDING)
                .texOffs(26, 48)
                .addBox(-3.80F, 1.00F, -2.68F, 7.60F, 9.60F, 0.30F)
                .texOffs(44, 48)
                .addBox(-3.80F, 1.00F, 2.38F, 7.60F, 9.60F, 0.30F)
                .texOffs(62, 48)
                .addBox(-4.62F, 3.00F, -1.85F, 0.26F, 7.30F, 3.70F)
                .texOffs(72, 48)
                .addBox(4.36F, 3.00F, -1.85F, 0.26F, 7.30F, 3.70F)
                .texOffs(0, 68)
                .addBox(-3.55F, -0.60F, -2.88F, 1.30F, 4.90F, 0.28F)
                .texOffs(4, 68)
                .addBox(2.25F, -0.60F, -2.88F, 1.30F, 4.90F, 0.28F)
                .texOffs(8, 68)
                .addBox(-3.55F, -0.60F, 2.60F, 1.30F, 4.90F, 0.28F)
                .texOffs(12, 68)
                .addBox(2.25F, -0.60F, 2.60F, 1.30F, 4.90F, 0.28F)
                .texOffs(16, 68)
                .addBox(-2.10F, 1.20F, -2.98F, 4.20F, 1.90F, 0.24F)
                .texOffs(26, 68)
                .addBox(-3.55F, 6.20F, -2.92F, 7.10F, 0.55F, 0.18F)
                .texOffs(26, 68)
                .addBox(-3.55F, 8.30F, -2.92F, 7.10F, 0.55F, 0.18F);
    }

    private static CubeListBuilder createRightSoftShoulder() {
        return CubeListBuilder.create()
                .texOffs(0, 78)
                .addBox(-2.35F, -2.22F, -1.65F, 3.00F, 0.50F, 3.30F);
    }

    private static CubeListBuilder createLeftSoftShoulder() {
        return CubeListBuilder.create()
                .texOffs(14, 78)
                .addBox(-0.65F, -2.22F, -1.65F, 3.00F, 0.50F, 3.30F);
    }
}

