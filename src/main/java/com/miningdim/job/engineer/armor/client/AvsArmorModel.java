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

/** Crye AVS shared silhouette: three magazine cells, side load and long MOLLE groin flap. */
public final class AvsArmorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_avs"), "main");

    public AvsArmorModel(ModelPart root) { super(root); }

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
                .texOffs(0, 0).addBox(-3.45F, 0.70F, -2.43F, 6.90F, 6.30F, 0.45F)
                .texOffs(16, 0).addBox(-3.45F, 0.70F, 1.98F, 6.90F, 6.30F, 0.45F)
                .texOffs(32, 0).addBox(-4.00F, 4.70F, -2.00F, 0.42F, 6.40F, 4.00F)
                .texOffs(42, 0).addBox(3.58F, 4.70F, -2.00F, 0.42F, 6.40F, 4.00F)
                .texOffs(52, 0).addBox(-3.49F, -0.25F, -2.40F, 1.20F, 1.10F, 4.80F)
                .texOffs(65, 0).addBox(2.29F, -0.25F, -2.40F, 1.20F, 1.10F, 4.80F)
                .texOffs(78, 0).addBox(-3.90F, 6.70F, -2.50F, 7.80F, 2.40F, 0.50F)
                .texOffs(96, 0).addBox(-3.90F, 6.70F, 2.00F, 7.80F, 2.40F, 0.50F)
                .texOffs(114, 0).addBox(-4.00F, 6.50F, -3.20F, 1.70F, 4.30F, 0.85F)
                .texOffs(121, 0).addBox(2.50F, 6.80F, -3.12F, 1.50F, 4.10F, 0.75F)
                .texOffs(0, 12).addBox(-2.25F, 5.70F, -3.07F, 1.55F, 4.20F, 0.72F)
                .texOffs(6, 12).addBox(-0.65F, 5.70F, -3.07F, 1.55F, 4.20F, 0.72F)
                .texOffs(12, 12).addBox(0.95F, 5.70F, -3.07F, 1.55F, 4.20F, 0.72F)
                .texOffs(18, 12).addBox(3.05F, 1.80F, -2.95F, 0.85F, 4.90F, 0.60F)
                .texOffs(22, 12).addBox(-2.70F, 8.90F, -2.42F, 5.40F, 5.80F, 0.42F)
                .texOffs(35, 12).addBox(-2.30F, 10.00F, -2.54F, 4.60F, 0.28F, 0.18F)
                .texOffs(46, 12).addBox(-2.30F, 11.00F, -2.54F, 4.60F, 0.28F, 0.18F)
                .texOffs(57, 12).addBox(-2.30F, 12.00F, -2.54F, 4.60F, 0.28F, 0.18F)
                .texOffs(68, 12).addBox(-2.30F, 13.00F, -2.54F, 4.60F, 0.28F, 0.18F)
                .texOffs(79, 12).addBox(-3.25F, 3.20F, -2.53F, 6.50F, 0.28F, 0.16F)
                .texOffs(94, 12).addBox(-3.25F, 4.00F, -2.53F, 6.50F, 0.28F, 0.16F);
    }
}
