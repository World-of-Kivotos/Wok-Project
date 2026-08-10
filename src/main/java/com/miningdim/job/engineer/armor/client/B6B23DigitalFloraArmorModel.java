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

/** Native block-model silhouette for the 6B23-1 digital flora vest. */
public final class B6B23DigitalFloraArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_6b23_1_digital_flora"), "main");

    private static final CubeDeformation CARRIER = new CubeDeformation(0.30F);

    public B6B23DigitalFloraArmorModel(ModelPart root) {
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
                .addBox(-3.75F, 1.0F, -3.05F, 7.50F, 9.0F, 0.70F)
                .texOffs(44, 0)
                .addBox(-3.80F, 0.80F, 2.40F, 7.60F, 9.50F, 0.65F)
                .texOffs(62, 0)
                .addBox(-4.95F, 2.20F, -2.20F, 0.70F, 8.50F, 4.40F)
                .texOffs(74, 0)
                .addBox(4.25F, 2.20F, -2.20F, 0.70F, 8.50F, 4.40F)
                .texOffs(0, 20)
                .addBox(-3.40F, -0.70F, -3.55F, 6.80F, 2.25F, 0.65F)
                .texOffs(16, 20)
                .addBox(-3.40F, -0.75F, 2.90F, 6.80F, 2.40F, 0.65F)
                .texOffs(32, 20)
                .addBox(-4.05F, -0.65F, -3.25F, 0.65F, 2.30F, 6.50F)
                .texOffs(48, 20)
                .addBox(3.40F, -0.65F, -3.25F, 0.65F, 2.30F, 6.50F)
                .texOffs(0, 34)
                .addBox(-3.90F, 8.90F, -3.18F, 7.80F, 2.50F, 0.55F)
                .texOffs(18, 34)
                .addBox(-2.15F, 11.10F, -3.05F, 4.30F, 4.40F, 0.50F)
                .texOffs(30, 34)
                .addBox(-4.10F, 8.65F, -3.26F, 8.20F, 0.55F, 0.25F);
    }

}
