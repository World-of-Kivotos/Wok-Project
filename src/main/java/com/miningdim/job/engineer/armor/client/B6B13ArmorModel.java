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
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(78, 14)
                .addBox(-3.35F, -2.25F, -2.10F, 4.0F, 0.45F, 4.20F), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(96, 14)
                .addBox(-0.65F, -2.25F, -2.10F, 4.0F, 0.45F, 4.20F), PartPose.offset(5.0F, 2.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder body() {
        return CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.40F, 0.40F, -2.36F, 6.80F, 2.80F, 0.38F)
                .texOffs(16, 0).addBox(-3.75F, 3.00F, -2.46F, 7.50F, 6.80F, 0.48F)
                .texOffs(33, 0).addBox(-3.85F, 9.60F, -2.40F, 7.70F, 2.00F, 0.42F)
                .texOffs(51, 0).addBox(-3.40F, 0.40F, 1.98F, 6.80F, 2.80F, 0.38F)
                .texOffs(67, 0).addBox(-3.75F, 3.00F, 1.98F, 7.50F, 8.60F, 0.44F)
                .texOffs(84, 0).addBox(-4.02F, 2.30F, -2.05F, 0.44F, 8.40F, 4.10F)
                .texOffs(95, 0).addBox(3.58F, 2.30F, -2.05F, 0.44F, 8.40F, 4.10F)
                .texOffs(106, 0).addBox(-4.20F, -0.78F, -4.25F, 3.80F, 1.65F, 0.42F)
                .texOffs(116, 0).addBox(0.40F, -0.78F, -4.25F, 3.80F, 1.65F, 0.42F)
                .texOffs(0, 14).addBox(-4.20F, -0.78F, 3.83F, 8.40F, 1.65F, 0.42F)
                .texOffs(19, 14).addBox(-4.25F, -0.75F, -4.05F, 0.42F, 1.65F, 8.10F)
                .texOffs(38, 14).addBox(3.83F, -0.75F, -4.05F, 0.42F, 1.65F, 8.10F)
                .texOffs(57, 14).addBox(-2.50F, 11.25F, -2.46F, 5.00F, 3.20F, 0.40F)
                .texOffs(69, 14).addBox(-1.70F, 1.00F, -2.52F, 3.40F, 1.30F, 0.22F)
                .texOffs(114, 14).addBox(-4.00F, 0.75F, -2.42F, 1.65F, 1.80F, 0.50F)
                .texOffs(120, 14).addBox(2.35F, 0.75F, -2.42F, 1.65F, 1.80F, 0.50F)
                .texOffs(0, 25).addBox(-4.00F, 0.75F, 1.92F, 1.65F, 1.80F, 0.50F)
                .texOffs(6, 25).addBox(2.35F, 0.75F, 1.92F, 1.65F, 1.80F, 0.50F);
    }
}
