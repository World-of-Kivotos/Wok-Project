package com.miningdim.job.engineer.armor.client;

import com.miningdim.core.MiningConstants;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/** 6B5-15 Zh-86 Uley flora vest with four front pouches and no arm armor. */
public final class B6B5FloraArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_6b5_15_flora"), "main");

    public B6B5FloraArmorModel(ModelPart root) {
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
        return CubeListBuilder.create()
                .texOffs(0, 0)
                .addBox(-3.30F, 0.55F, -2.30F, 6.60F, 3.00F, 0.36F)
                .texOffs(15, 0)
                .addBox(-3.30F, 0.55F, 1.94F, 6.60F, 3.00F, 0.36F)
                .texOffs(30, 0)
                .addBox(-3.75F, 3.52F, -2.32F, 7.50F, 4.03F, 0.40F)
                .texOffs(47, 0)
                .addBox(-3.75F, 3.52F, 1.92F, 7.50F, 4.03F, 0.40F)
                .texOffs(64, 0)
                .addBox(-3.95F, 7.52F, -2.35F, 7.90F, 4.23F, 0.42F)
                .texOffs(82, 0)
                .addBox(-3.95F, 7.52F, 1.93F, 7.90F, 4.23F, 0.42F)
                .texOffs(100, 0)
                .addBox(-4.35F, 3.58F, -1.94F, 0.63F, 8.14F, 3.88F)
                .texOffs(110, 0)
                .addBox(3.72F, 3.58F, -1.94F, 0.63F, 8.14F, 3.88F)

                // The reference has a padded ring collar and top tabs, but no deltoid pads.
                .texOffs(0, 13)
                .addBox(-4.40F, -1.00F, -4.50F, 3.80F, 1.60F, 0.42F)
                .texOffs(10, 13)
                .addBox(0.60F, -1.00F, -4.50F, 3.80F, 1.60F, 0.42F)
                .texOffs(20, 13)
                .addBox(-4.40F, -1.00F, 4.08F, 8.80F, 1.60F, 0.42F)
                .texOffs(40, 13)
                .addBox(-4.50F, -0.95F, -4.13F, 0.42F, 1.52F, 8.26F)
                .texOffs(58, 13)
                .addBox(4.08F, -0.95F, -4.13F, 0.42F, 1.52F, 8.26F)
                .texOffs(76, 13)
                .addBox(-3.28F, 0.35F, -4.13F, 2.70F, 0.28F, 2.21F)
                .texOffs(87, 13)
                .addBox(0.58F, 0.35F, -4.13F, 2.70F, 0.28F, 2.21F)
                .texOffs(98, 13)
                .addBox(-3.28F, 0.35F, 1.92F, 2.70F, 0.28F, 2.21F)
                .texOffs(109, 13)
                .addBox(0.58F, 0.35F, 1.92F, 2.70F, 0.28F, 2.21F)

                .texOffs(0, 24)
                .addBox(-3.00F, 0.65F, -2.56F, 0.70F, 4.60F, 0.28F)
                .texOffs(3, 24)
                .addBox(2.30F, 0.65F, -2.56F, 0.70F, 4.60F, 0.28F)
                .texOffs(6, 24)
                .addBox(-3.60F, 5.00F, -3.07F, 1.55F, 4.40F, 0.78F)
                .texOffs(12, 24)
                .addBox(-1.85F, 5.00F, -3.07F, 1.55F, 4.40F, 0.78F)
                .texOffs(18, 24)
                .addBox(0.30F, 5.00F, -3.07F, 1.55F, 4.40F, 0.78F)
                .texOffs(24, 24)
                .addBox(2.05F, 5.00F, -3.07F, 1.55F, 4.40F, 0.78F)

                // Stepped front-only hem mirrors the rounded lower panel in the source.
                .texOffs(30, 24)
                .addBox(-3.10F, 11.45F, -2.56F, 6.20F, 3.00F, 0.30F)
                .texOffs(44, 24)
                .addBox(-1.75F, 14.25F, -2.50F, 3.50F, 1.20F, 0.25F);
    }
}
