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

/** Wartech TV-110 coyote carrier with a square utility pouch, twin magazines and a tall radio pouch. */
public final class Tv110ArmorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_tv110_coyote"), "main");

    public Tv110ArmorModel(ModelPart root) { super(root); }

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
                // Compact plate envelope, cummerbund and offset shoulder bridges.
                .texOffs(0, 0).addBox(-3.40F, 0.70F, -2.43F, 6.80F, 6.20F, 0.45F)
                .texOffs(16, 0).addBox(-3.40F, 0.70F, 1.98F, 6.80F, 6.20F, 0.45F)
                .texOffs(32, 0).addBox(-3.96F, 4.60F, -2.02F, 0.44F, 6.30F, 4.04F)
                .texOffs(42, 0).addBox(3.52F, 4.60F, -2.02F, 0.44F, 6.30F, 4.04F)
                .texOffs(52, 0).addBox(-3.55F, -0.28F, -2.48F, 1.35F, 1.28F, 4.88F)
                .texOffs(66, 0).addBox(2.20F, -0.28F, -2.48F, 1.35F, 1.28F, 4.88F)
                .texOffs(80, 0).addBox(-3.90F, 6.55F, -2.56F, 7.80F, 2.55F, 0.59F)
                .texOffs(98, 0).addBox(-3.90F, 6.55F, 1.97F, 7.80F, 2.55F, 0.59F)

                // Reference-defining left square pouch and twin long central magazine pouches.
                .texOffs(116, 0).addBox(-3.98F, 5.55F, -3.50F, 2.10F, 4.95F, 1.18F)
                .texOffs(0, 12).addBox(-1.82F, 5.30F, -3.48F, 1.70F, 4.70F, 1.08F)
                .texOffs(7, 12).addBox(0.12F, 5.30F, -3.48F, 1.70F, 4.70F, 1.08F)

                // Tall radio/side pouch remains inside the arm plane for neutral-pose clearance.
                .texOffs(14, 12).addBox(2.35F, 4.75F, -3.35F, 1.63F, 5.55F, 0.99F)
                .texOffs(21, 12).addBox(-3.15F, 3.10F, -2.62F, 6.30F, 0.30F, 0.24F)

                // Separately protruding lids give each pouch a readable top edge.
                .texOffs(36, 12).addBox(-3.95F, 5.45F, -3.64F, 2.04F, 0.95F, 1.34F)
                .texOffs(44, 12).addBox(-1.79F, 5.20F, -3.61F, 1.64F, 0.62F, 1.23F)
                .texOffs(51, 12).addBox(0.15F, 5.20F, -3.61F, 1.64F, 0.62F, 1.23F)
                .texOffs(58, 12).addBox(2.38F, 4.65F, -3.48F, 1.57F, 0.88F, 1.14F)
                .texOffs(65, 12).addBox(3.72F, 0.40F, -2.94F, 0.16F, 4.55F, 0.16F)

                // Four stepped MOLLE rails and the padded upper face add TV-110 surface depth.
                .texOffs(67, 12).addBox(-3.15F, 3.86F, -2.64F, 6.30F, 0.30F, 0.26F)
                .texOffs(82, 12).addBox(-3.15F, 4.62F, -2.66F, 6.30F, 0.30F, 0.28F)
                .texOffs(97, 12).addBox(-3.15F, 5.36F, -2.68F, 6.30F, 0.30F, 0.29F)
                .texOffs(112, 12).addBox(-2.80F, 1.20F, -2.60F, 5.60F, 1.40F, 0.20F)

                // Raised zipper rails, radio face and stitched panel seams stay off the base faces.
                .texOffs(125, 12).addBox(-3.75F, 6.70F, -3.62F, 0.18F, 3.20F, 0.16F)
                .texOffs(127, 12).addBox(-2.30F, 6.70F, -3.62F, 0.18F, 3.20F, 0.16F)
                .texOffs(0, 20).addBox(2.63F, 5.85F, -3.47F, 1.04F, 2.75F, 0.16F)
                .texOffs(4, 20).addBox(-3.25F, 0.20F, -2.60F, 1.02F, 0.50F, 0.25F)
                .texOffs(8, 20).addBox(2.23F, 0.20F, -2.60F, 1.02F, 0.50F, 0.25F)
                .texOffs(12, 20).addBox(-0.12F, 1.00F, -2.76F, 0.24F, 4.00F, 0.40F)
                .texOffs(15, 20).addBox(-3.45F, 8.35F, -2.72F, 6.90F, 0.24F, 0.18F);
    }
}
