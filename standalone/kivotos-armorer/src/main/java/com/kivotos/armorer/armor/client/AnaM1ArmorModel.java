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

/** ANA M1 olive rig with broad cummerbund, twin side sustainment pouches and a central magazine load. */
public final class AnaM1ArmorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_ana_m1_olive"), "main");

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
                // Carrier shell and the broad waist foundation.
                .texOffs(0, 0).addBox(-3.48F, 0.55F, -2.46F, 6.96F, 6.45F, 0.48F)
                .texOffs(16, 0).addBox(-3.48F, 0.55F, 1.98F, 6.96F, 6.45F, 0.48F)
                .texOffs(32, 0).addBox(-3.98F, 4.30F, -2.08F, 0.56F, 6.70F, 4.16F)
                .texOffs(43, 0).addBox(3.42F, 4.30F, -2.08F, 0.56F, 6.70F, 4.16F)
                .texOffs(54, 0).addBox(-3.62F, -0.30F, -2.54F, 1.37F, 1.25F, 5.08F)
                .texOffs(68, 0).addBox(2.25F, -0.30F, -2.54F, 1.37F, 1.25F, 5.08F)
                .texOffs(82, 0).addBox(-3.28F, 3.20F, -2.70F, 6.56F, 0.32F, 0.28F)
                .texOffs(97, 0).addBox(-4.12F, 6.30F, -2.68F, 8.24F, 3.40F, 0.63F)
                .texOffs(0, 12).addBox(-4.08F, 6.35F, 2.05F, 8.16F, 3.30F, 0.63F)

                // The reference load is asymmetric: a large left sustainment bag and a narrower
                // right radio/utility pouch, both ahead of the neutral arm plane.
                .texOffs(19, 12).addBox(-4.95F, 6.15F, -3.72F, 2.45F, 4.70F, 1.48F)
                .texOffs(28, 12).addBox(2.50F, 6.55F, -3.58F, 1.55F, 4.40F, 1.20F)

                // Central magazine and tool layer.
                .texOffs(35, 12).addBox(-1.55F, 6.10F, -3.46F, 1.35F, 4.25F, 1.04F)
                .texOffs(41, 12).addBox(0.15F, 6.20F, -3.40F, 1.35F, 4.10F, 1.00F)
                .texOffs(47, 12).addBox(-2.45F, 5.95F, -3.50F, 0.90F, 1.85F, 1.10F)
                .texOffs(52, 12).addBox(2.92F, 2.00F, -3.10F, 1.08F, 4.70F, 0.76F)
                .texOffs(57, 12).addBox(3.70F, 0.15F, -2.88F, 0.18F, 4.55F, 0.20F)
                .texOffs(59, 12).addBox(2.25F, 1.25F, -2.76F, 0.50F, 3.20F, 0.36F)

                // Raised lids and stitching panels prevent flat, texture-only side pockets.
                .texOffs(62, 12).addBox(-5.08F, 6.00F, -3.82F, 2.68F, 1.15F, 1.68F)
                .texOffs(72, 12).addBox(-4.84F, 7.45F, -3.83F, 2.10F, 2.90F, 0.24F)
                .texOffs(78, 12).addBox(2.37F, 6.35F, -3.68F, 1.71F, 1.10F, 1.40F)
                .texOffs(86, 12).addBox(2.62F, 7.72F, -3.69F, 1.30F, 2.55F, 0.22F)
                .texOffs(91, 12).addBox(-1.60F, 5.92F, -3.58F, 1.45F, 1.05F, 1.24F)
                .texOffs(98, 12).addBox(0.10F, 6.00F, -3.52F, 1.45F, 1.02F, 1.20F)
                .texOffs(105, 12).addBox(-3.20F, 4.12F, -2.72F, 6.35F, 0.30F, 0.30F)
                .texOffs(120, 12).addBox(-2.45F, 7.95F, -3.52F, 0.90F, 2.00F, 1.12F)
                .texOffs(0, 20).addBox(-2.48F, 5.82F, -3.64F, 0.96F, 0.55F, 1.25F)
                .texOffs(6, 20).addBox(-2.48F, 7.82F, -3.66F, 0.96F, 0.55F, 1.27F);
    }
}

