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

/** NFM THOR concealable soft armor: clean tapered shell with padded lower channels. */
public final class ThorConcealableArmorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_thor_concealable"), "main");

    public ThorConcealableArmorModel(ModelPart root) { super(root); }

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
                .texOffs(0, 0).addBox(-3.30F, 0.40F, -2.32F, 6.60F, 4.00F, 0.34F)
                .texOffs(15, 0).addBox(-3.80F, 4.20F, -2.34F, 7.60F, 6.80F, 0.36F)
                .texOffs(32, 0).addBox(-3.30F, 0.40F, 1.98F, 6.60F, 4.00F, 0.34F)
                .texOffs(47, 0).addBox(-3.80F, 4.20F, 1.98F, 7.60F, 6.80F, 0.36F)
                .texOffs(64, 0).addBox(-4.00F, 2.20F, -2.00F, 0.36F, 8.80F, 4.00F)
                .texOffs(74, 0).addBox(3.64F, 2.20F, -2.00F, 0.36F, 8.80F, 4.00F)
                .texOffs(84, 0).addBox(-3.45F, -0.20F, -2.35F, 1.40F, 1.20F, 4.70F)
                .texOffs(98, 0).addBox(2.05F, -0.20F, -2.35F, 1.40F, 1.20F, 4.70F)
                .texOffs(0, 14).addBox(-3.90F, 8.25F, -2.48F, 7.80F, 1.10F, 0.35F)
                .texOffs(18, 14).addBox(-3.90F, 8.25F, 2.13F, 7.80F, 1.10F, 0.35F)
                .texOffs(36, 14).addBox(-3.50F, 9.35F, -2.46F, 3.40F, 2.00F, 0.20F)
                .texOffs(45, 14).addBox(0.10F, 9.35F, -2.46F, 3.40F, 2.00F, 0.20F)
                .texOffs(54, 14).addBox(-1.10F, 2.00F, -2.43F, 2.20F, 0.70F, 0.16F);
    }
}

