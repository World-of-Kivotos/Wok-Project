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

/** 6B23-2 mountain-flora soft armor with a high collar and long front skirt. */
public final class B6B23MountainFloraArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_6b23_2_mountain_flora"), "main");

    public B6B23MountainFloraArmorModel(ModelPart root) {
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
                // Narrow padded shoulders widen into the soft vest body.
                .texOffs(0, 0)
                .addBox(-3.25F, 0.50F, -2.35F, 6.50F, 3.20F, 0.40F)
                .texOffs(15, 0)
                .addBox(-3.25F, 0.50F, 1.95F, 6.50F, 3.20F, 0.40F)
                .texOffs(30, 0)
                .addBox(-3.75F, 3.67F, -2.38F, 7.50F, 4.03F, 0.42F)
                .texOffs(47, 0)
                .addBox(-3.75F, 3.67F, 1.96F, 7.50F, 4.03F, 0.42F)
                .texOffs(64, 0)
                .addBox(-4.00F, 7.67F, -2.42F, 8.00F, 4.03F, 0.45F)
                .texOffs(82, 0)
                .addBox(-4.00F, 7.67F, 1.97F, 8.00F, 4.03F, 0.45F)
                .texOffs(100, 0)
                .addBox(-4.35F, 3.73F, -1.98F, 0.63F, 7.92F, 3.96F)
                .texOffs(110, 0)
                .addBox(3.72F, 3.73F, -1.98F, 0.63F, 7.92F, 3.96F)

                // Five outer panels keep the padded collar visible around the head.
                .texOffs(0, 13)
                .addBox(-4.35F, -0.90F, -4.45F, 3.75F, 1.55F, 0.42F)
                .texOffs(10, 13)
                .addBox(0.60F, -0.90F, -4.45F, 3.75F, 1.55F, 0.42F)
                .texOffs(20, 13)
                .addBox(-4.35F, -0.90F, 4.03F, 8.70F, 1.55F, 0.42F)
                .texOffs(40, 13)
                .addBox(-4.45F, -0.85F, -4.08F, 0.42F, 1.47F, 8.16F)
                .texOffs(58, 13)
                .addBox(4.03F, -0.85F, -4.08F, 0.42F, 1.47F, 8.16F)

                // Body-mounted shoulder yokes, not arm-mounted protective caps.
                .texOffs(76, 13)
                .addBox(-3.40F, 0.35F, -4.08F, 2.82F, 0.25F, 2.15F)
                .texOffs(87, 13)
                .addBox(0.58F, 0.35F, -4.08F, 2.82F, 0.25F, 2.15F)
                .texOffs(98, 13)
                .addBox(-3.40F, 0.35F, 1.93F, 2.82F, 0.25F, 2.15F)
                .texOffs(109, 13)
                .addBox(0.58F, 0.35F, 1.93F, 2.82F, 0.25F, 2.15F)

                // The broad fold and stepped apron reproduce the long front hem.
                .texOffs(0, 24)
                .addBox(-3.90F, 7.65F, -2.65F, 7.80F, 4.00F, 0.26F)
                .texOffs(18, 24)
                .addBox(-3.10F, 11.60F, -2.58F, 6.20F, 3.60F, 0.35F)
                .texOffs(33, 24)
                .addBox(-3.80F, 6.90F, -2.60F, 7.60F, 0.40F, 0.24F);
    }
}
