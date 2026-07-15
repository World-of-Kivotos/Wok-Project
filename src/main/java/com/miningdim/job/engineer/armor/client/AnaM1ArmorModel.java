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

/** ANA M1 olive rig with short carrier, broad cummerbund and asymmetric field load. */
public final class AnaM1ArmorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_ana_m1_olive"), "main");

    public AnaM1ArmorModel(ModelPart root) { super(root); }

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
                .texOffs(0, 0).addBox(-3.45F, 0.70F, -2.40F, 6.90F, 6.00F, 0.42F)
                .texOffs(16, 0).addBox(-3.45F, 0.70F, 1.98F, 6.90F, 6.00F, 0.42F)
                .texOffs(32, 0).addBox(-4.00F, 4.70F, -2.00F, 0.40F, 6.30F, 4.00F)
                .texOffs(42, 0).addBox(3.60F, 4.70F, -2.00F, 0.40F, 6.30F, 4.00F)
                .texOffs(52, 0).addBox(-3.40F, -0.20F, -2.43F, 1.10F, 1.00F, 4.80F)
                .texOffs(65, 0).addBox(2.30F, -0.20F, -2.43F, 1.10F, 1.00F, 4.80F)
                .texOffs(78, 0).addBox(-3.30F, 3.40F, -2.50F, 6.60F, 0.28F, 0.16F)
                .texOffs(93, 0).addBox(-3.90F, 6.65F, -2.45F, 7.80F, 3.80F, 0.45F)
                .texOffs(111, 0).addBox(-3.90F, 6.65F, 2.00F, 7.80F, 3.80F, 0.45F)
                .texOffs(0, 12).addBox(-3.85F, 5.80F, -3.12F, 1.70F, 4.40F, 0.80F)
                .texOffs(6, 12).addBox(-3.85F, 10.25F, -3.08F, 1.35F, 2.30F, 0.68F)
                .texOffs(12, 12).addBox(-2.10F, 6.55F, -3.12F, 1.65F, 4.20F, 0.75F)
                .texOffs(18, 12).addBox(-0.40F, 6.55F, -3.12F, 1.65F, 4.20F, 0.75F)
                .texOffs(24, 12).addBox(1.30F, 6.55F, -3.12F, 1.65F, 4.20F, 0.75F)
                .texOffs(30, 12).addBox(2.95F, 4.55F, -3.00F, 1.05F, 4.60F, 0.65F)
                .texOffs(35, 12).addBox(3.78F, 0.50F, -2.75F, 0.16F, 5.00F, 0.16F)
                .texOffs(37, 12).addBox(1.50F, 1.50F, -2.54F, 0.45F, 3.10F, 0.20F);
    }
}
