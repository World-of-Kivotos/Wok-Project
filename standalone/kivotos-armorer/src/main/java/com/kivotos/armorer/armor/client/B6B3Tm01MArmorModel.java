package com.kivotos.armorer.armor.client;

import com.kivotos.armorer.ArmorerMod;
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

/** 6B3TM-01M long armored rig with open armholes, central flap, buckle and utility pouches. */
public final class B6B3Tm01MArmorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_6b3tm_01m_khaki"), "main");

    public B6B3Tm01MArmorModel(ModelPart root) { super(root); }

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
                .texOffs(0, 0).addBox(-3.40F, 0.60F, -2.38F, 6.80F, 3.70F, 0.40F)
                .texOffs(16, 0).addBox(-3.75F, 4.00F, -2.43F, 7.50F, 7.40F, 0.45F)
                .texOffs(33, 0).addBox(-3.40F, 0.60F, 1.98F, 6.80F, 3.70F, 0.40F)
                .texOffs(49, 0).addBox(-3.75F, 4.00F, 1.98F, 7.50F, 7.40F, 0.45F)
                .texOffs(66, 0).addBox(-4.02F, 2.20F, -2.00F, 0.42F, 8.80F, 4.00F)
                .texOffs(76, 0).addBox(3.60F, 2.20F, -2.00F, 0.42F, 8.80F, 4.00F)
                .texOffs(86, 0).addBox(-3.10F, -0.10F, -2.62F, 0.85F, 3.70F, 0.32F)
                .texOffs(90, 0).addBox(2.25F, -0.10F, -2.62F, 0.85F, 3.70F, 0.32F)
                .texOffs(94, 0).addBox(-3.10F, -0.10F, 2.30F, 0.85F, 3.70F, 0.32F)
                .texOffs(98, 0).addBox(2.25F, -0.10F, 2.30F, 0.85F, 3.70F, 0.32F)
                .texOffs(102, 0).addBox(-1.55F, 1.35F, -2.68F, 3.10F, 5.40F, 0.35F)
                .texOffs(110, 0).addBox(-3.90F, 8.55F, -2.62F, 7.80F, 1.30F, 0.45F)
                .texOffs(0, 14).addBox(-3.90F, 8.55F, 2.17F, 7.80F, 1.30F, 0.45F)
                .texOffs(18, 14).addBox(-3.79F, 7.00F, -3.05F, 1.45F, 3.90F, 0.70F)
                .texOffs(24, 14).addBox(-2.20F, 7.00F, -3.05F, 1.45F, 3.90F, 0.70F)
                .texOffs(30, 14).addBox(0.95F, 7.00F, -3.05F, 1.35F, 3.90F, 0.70F)
                .texOffs(36, 14).addBox(2.55F, 6.65F, -3.00F, 1.10F, 4.40F, 0.65F)
                .texOffs(41, 14).addBox(-0.70F, 8.45F, -2.84F, 1.40F, 1.50F, 0.30F)
                .texOffs(46, 14).addBox(-4.12F, 7.80F, -1.95F, 0.28F, 2.00F, 3.90F);
    }
}

