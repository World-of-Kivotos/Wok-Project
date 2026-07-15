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

/** A18 Skanda multicam carrier with broad side load and paired dark magazines. */
public final class A18SkandaArmorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_a18_skanda_multicam"), "main");

    public A18SkandaArmorModel(ModelPart root) { super(root); }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("body", body(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder body() {
        return CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.40F, 0.70F, -2.43F, 6.80F, 6.20F, 0.45F)
                .texOffs(16, 0).addBox(-3.40F, 0.70F, 1.98F, 6.80F, 6.20F, 0.45F)
                .texOffs(32, 0).addBox(-4.00F, 4.80F, -2.00F, 0.42F, 6.20F, 4.00F)
                .texOffs(42, 0).addBox(3.58F, 4.80F, -2.00F, 0.42F, 6.20F, 4.00F)
                .texOffs(52, 0).addBox(-3.44F, -0.20F, -2.40F, 1.15F, 1.00F, 4.80F)
                .texOffs(65, 0).addBox(2.29F, -0.20F, -2.40F, 1.15F, 1.00F, 4.80F)
                .texOffs(78, 0).addBox(-3.90F, 6.60F, -2.50F, 7.80F, 2.50F, 0.50F)
                .texOffs(96, 0).addBox(-3.90F, 6.60F, 2.00F, 7.80F, 2.50F, 0.50F)
                .texOffs(114, 0).addBox(-3.94F, 6.30F, -3.20F, 2.10F, 4.50F, 0.85F)
                .texOffs(121, 0).addBox(1.84F, 6.30F, -3.20F, 2.10F, 4.50F, 0.85F)
                .texOffs(0, 12).addBox(-1.75F, 5.80F, -3.10F, 1.80F, 3.80F, 0.75F)
                .texOffs(7, 12).addBox(0.05F, 5.80F, -3.10F, 1.80F, 3.80F, 0.75F)
                .texOffs(14, 12).addBox(-3.80F, 1.90F, -3.08F, 1.10F, 4.40F, 0.70F)
                .texOffs(19, 12).addBox(2.85F, 1.60F, -3.03F, 0.95F, 4.70F, 0.65F)
                .texOffs(24, 12).addBox(-3.20F, 3.50F, -2.53F, 6.40F, 0.25F, 0.16F)
                .texOffs(39, 12).addBox(-0.40F, 8.90F, -2.64F, 0.80F, 1.00F, 0.22F);
    }
}
