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

/** 6B13 assault armor: tall split collar, long apron and its characteristic shoulder caps. */
public final class B6B13ArmorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_6b13_flora"), "main");

    public B6B13ArmorModel(ModelPart root) { super(root); }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("body", body(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.12F, -2.25F, -2.15F, 4.10F, 0.65F, 4.30F)
                .texOffs(18, 0).addBox(-3.20F, -1.80F, -2.10F, 0.55F, 1.30F, 4.20F),
                PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create()
                .texOffs(29, 0).addBox(-0.98F, -2.25F, -2.15F, 4.10F, 0.65F, 4.30F)
                .texOffs(47, 0).addBox(2.65F, -1.80F, -2.10F, 0.55F, 1.30F, 4.20F),
                PartPose.offset(5.0F, 2.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder body() {
        return CubeListBuilder.create()
                .texOffs(58, 0).addBox(-3.40F, 0.40F, -2.36F, 6.80F, 2.80F, 0.36F)
                .texOffs(74, 0).addBox(-3.75F, 3.00F, -2.46F, 7.50F, 6.80F, 0.50F)
                .texOffs(91, 0).addBox(-3.85F, 9.60F, -2.40F, 7.70F, 2.00F, 0.40F)
                .texOffs(109, 0).addBox(-3.40F, 0.40F, 1.96F, 6.80F, 2.80F, 0.40F)
                .texOffs(0, 9).addBox(-3.75F, 3.00F, 1.92F, 7.50F, 8.60F, 0.50F)
                .texOffs(17, 9).addBox(-3.96F, 2.30F, -2.05F, 0.44F, 8.40F, 4.10F)
                .texOffs(28, 9).addBox(3.52F, 2.30F, -2.05F, 0.44F, 8.40F, 4.10F)
                .texOffs(39, 9).addBox(-4.20F, -0.78F, -4.25F, 3.80F, 1.65F, 0.42F)
                .texOffs(49, 9).addBox(0.40F, -0.78F, -4.25F, 3.80F, 1.65F, 0.42F)
                .texOffs(59, 9).addBox(-4.20F, -0.78F, 3.83F, 8.40F, 1.65F, 0.42F)
                .texOffs(78, 9).addBox(-3.95F, -0.75F, -4.05F, 0.42F, 1.65F, 8.10F)
                .texOffs(97, 9).addBox(3.53F, -0.75F, -4.05F, 0.42F, 1.65F, 8.10F)
                .texOffs(116, 9).addBox(-2.50F, 11.25F, -2.46F, 5.00F, 3.20F, 0.40F)
                .texOffs(0, 23).addBox(-1.70F, 1.00F, -2.52F, 3.40F, 1.30F, 0.22F)
                .texOffs(9, 23).addBox(-4.00F, 0.75F, -2.42F, 1.65F, 1.80F, 0.50F)
                .texOffs(15, 23).addBox(2.35F, 0.75F, -2.42F, 1.65F, 1.80F, 0.50F)
                .texOffs(21, 23).addBox(-4.00F, 0.75F, 1.92F, 1.65F, 1.80F, 0.50F)
                .texOffs(27, 23).addBox(2.35F, 0.75F, 1.92F, 1.65F, 1.80F, 0.50F);
    }
}
