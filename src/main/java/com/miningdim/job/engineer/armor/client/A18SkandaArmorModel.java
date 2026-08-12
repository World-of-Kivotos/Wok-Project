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

/** A18 Skanda multicam carrier: wide cummerbund, asymmetric side bags and layered front equipment. */
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
                // Rebuilt carrier shell: broader plate, wider waist and substantial shoulder harness.
                .texOffs(0, 0).addBox(-3.55F, 0.55F, -2.48F, 7.10F, 6.60F, 0.52F)
                .texOffs(17, 0).addBox(-3.55F, 0.55F, 1.96F, 7.10F, 6.60F, 0.50F)
                .texOffs(34, 0).addBox(-4.00F, 4.40F, -2.08F, 0.48F, 6.55F, 4.16F)
                .texOffs(45, 0).addBox(3.52F, 4.40F, -2.08F, 0.48F, 6.55F, 4.16F)
                .texOffs(56, 0).addBox(-3.72F, -0.45F, -2.56F, 1.54F, 1.45F, 5.12F)
                .texOffs(71, 0).addBox(2.18F, -0.45F, -2.56F, 1.54F, 1.45F, 5.12F)
                .texOffs(86, 0).addBox(-4.18F, 6.35F, -2.64F, 8.36F, 3.30F, 0.64F)
                .texOffs(105, 0).addBox(-4.12F, 6.40F, 2.00F, 8.24F, 3.20F, 0.62F)

                // Large left and right sustainment bags, offset in size and height like the reference.
                .texOffs(0, 12).addBox(-4.92F, 6.15F, -3.82F, 2.82F, 4.70F, 1.60F)
                .texOffs(10, 12).addBox(2.18F, 6.55F, -3.68F, 2.87F, 4.20F, 1.48F)

                // Central double magazines, utility pouch, radio and MOLLE layer.
                .texOffs(20, 12).addBox(-1.95F, 5.80F, -3.62F, 1.77F, 4.35F, 1.20F)
                .texOffs(27, 12).addBox(0.22F, 5.90F, -3.55F, 1.83F, 4.10F, 1.15F)
                .texOffs(34, 12).addBox(-3.52F, 5.30F, -3.42F, 1.24F, 4.40F, 1.04F)
                .texOffs(40, 12).addBox(2.62F, 1.65F, -3.12F, 1.00F, 5.00F, 0.76F)
                .texOffs(45, 12).addBox(-3.32F, 3.05F, -2.68F, 6.64F, 0.32F, 0.26F)
                .texOffs(60, 12).addBox(-0.44F, 8.75F, -3.78F, 0.88F, 1.30F, 1.30F)

                // Each side bag has a separate lid and raised stitch panel, with deliberately
                // staggered outer faces so no two exposed surfaces fight for the same plane.
                .texOffs(66, 12).addBox(-5.05F, 5.98F, -3.92F, 3.05F, 1.14F, 1.80F)
                .texOffs(77, 12).addBox(-4.78F, 7.45F, -3.93F, 2.44F, 2.85F, 0.25F)
                .texOffs(84, 12).addBox(2.08F, 6.38F, -3.78F, 3.09F, 1.10F, 1.68F)
                .texOffs(95, 12).addBox(2.42F, 7.72F, -3.80F, 2.48F, 2.53F, 0.24F)
                .texOffs(102, 12).addBox(-2.06F, 5.65F, -3.74F, 1.98F, 1.20F, 1.42F)
                .texOffs(110, 12).addBox(0.12F, 5.75F, -3.67F, 2.04F, 1.07F, 1.37F)

                // Central tool channel, shoulder hardware and additional raised webbing rows.
                .texOffs(118, 12).addBox(-0.12F, 4.70F, -3.35F, 0.30F, 3.80F, 0.97F)
                .texOffs(122, 12).addBox(-0.20F, 4.55F, -3.43F, 0.44F, 0.80F, 1.11F)
                .texOffs(0, 20).addBox(-3.45F, -0.10F, -2.77F, 0.63F, 1.35F, 0.41F)
                .texOffs(4, 20).addBox(2.75F, -0.02F, -2.75F, 0.75F, 1.37F, 0.39F)
                .texOffs(8, 20).addBox(-3.25F, 3.90F, -2.70F, 6.50F, 0.30F, 0.28F)
                .texOffs(23, 20).addBox(-3.15F, 4.75F, -2.72F, 6.30F, 0.28F, 0.30F)
                .texOffs(38, 20).addBox(-4.00F, 8.05F, -2.78F, 8.00F, 0.28F, 0.22F);
    }
}
