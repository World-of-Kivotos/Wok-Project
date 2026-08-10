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

/** Native block-model silhouette for the 6B5-16 Zh-86 Uley vest. */
public final class B6B5ArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_6b5_16"), "main");

    private static final CubeDeformation CARRIER = new CubeDeformation(0.28F);

    public B6B5ArmorModel(ModelPart root) {
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
                .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, CARRIER)
                .texOffs(26, 0)
                .addBox(-3.80F, 0.55F, -2.92F, 7.60F, 10.30F, 0.55F)
                .texOffs(44, 0)
                .addBox(-3.80F, 0.55F, 2.37F, 7.60F, 10.30F, 0.55F)
                .texOffs(62, 0)
                .addBox(-4.83F, 2.10F, -2.15F, 0.55F, 8.70F, 4.30F)
                .texOffs(73, 0)
                .addBox(4.28F, 2.10F, -2.15F, 0.55F, 8.70F, 4.30F)
                .texOffs(0, 18)
                .addBox(-3.20F, -0.60F, -3.40F, 6.40F, 2.0F, 0.65F)
                .texOffs(16, 18)
                .addBox(-3.20F, -0.60F, 2.75F, 6.40F, 2.0F, 0.65F)
                .texOffs(32, 18)
                .addBox(-4.05F, -0.55F, -2.95F, 0.65F, 2.15F, 5.90F)
                .texOffs(47, 18)
                .addBox(3.40F, -0.55F, -2.95F, 0.65F, 2.15F, 5.90F)
                .texOffs(62, 18)
                .addBox(-3.0F, 0.60F, -3.08F, 0.75F, 4.20F, 0.28F)
                .texOffs(66, 18)
                .addBox(2.25F, 0.60F, -3.08F, 0.75F, 4.20F, 0.28F)
                .texOffs(0, 30)
                .addBox(-3.75F, 5.0F, -3.42F, 1.50F, 4.10F, 0.72F)
                .texOffs(6, 30)
                .addBox(-2.05F, 5.0F, -3.42F, 1.55F, 4.10F, 0.72F)
                .texOffs(12, 30)
                .addBox(0.40F, 5.0F, -3.42F, 1.50F, 4.10F, 0.72F)
                .texOffs(18, 30)
                .addBox(2.10F, 5.0F, -3.42F, 1.55F, 4.10F, 0.72F)
                .texOffs(24, 30)
                .addBox(-2.90F, 9.60F, -3.20F, 5.80F, 2.60F, 0.50F)
                .texOffs(38, 30)
                .addBox(-1.60F, 12.0F, -3.10F, 3.20F, 1.40F, 0.50F);
    }

}
