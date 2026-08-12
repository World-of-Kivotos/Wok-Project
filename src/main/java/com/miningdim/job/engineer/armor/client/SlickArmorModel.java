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

/** LBT 6094A Slick: compact clean carrier with broad straps and exposed adjustment webbing. */
public final class SlickArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_slick"), "main");

    public SlickArmorModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("body", createBody(), PartPose.ZERO);

        // The reference has load-bearing shoulder straps, but no articulated shoulder armor.
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        CubeListBuilder body = CubeListBuilder.create()
                // A short, close-fitting carrier establishes the Slick silhouette without a collar or apron.
                .texOffs(0, 0)
                .addBox(-3.90F, 0.20F, -1.90F, 7.80F, 10.40F, 3.80F)
                .texOffs(25, 0)
                .addBox(-3.60F, 0.70F, -2.50F, 7.20F, 8.80F, 0.68F)
                .texOffs(42, 0)
                .addBox(-3.60F, 0.70F, 1.82F, 7.20F, 8.90F, 0.68F)

                // Wide, clean cummerbund panels wrap the waist; these are structural bands, not side pouches.
                .texOffs(59, 0)
                .addBox(-3.86F, 4.25F, -2.20F, 0.36F, 5.20F, 4.40F)
                .texOffs(59, 0)
                .addBox(3.50F, 4.25F, -2.20F, 0.36F, 5.20F, 4.40F)

                // Broad padded shoulder straps remain separated around the neck opening.
                .texOffs(70, 0)
                .addBox(-3.40F, -0.20F, -2.76F, 1.45F, 4.00F, 0.90F)
                .texOffs(70, 0)
                .addBox(1.95F, -0.20F, -2.76F, 1.45F, 4.00F, 0.90F)
                .texOffs(76, 0)
                .addBox(-3.40F, -0.20F, 1.86F, 1.45F, 4.00F, 0.90F)
                .texOffs(76, 0)
                .addBox(1.95F, -0.20F, 1.86F, 1.45F, 4.00F, 0.90F)
                .texOffs(82, 0)
                .addBox(-3.36F, -0.52F, -2.00F, 1.45F, 0.70F, 4.00F)
                .texOffs(82, 0)
                .addBox(1.91F, -0.52F, -2.00F, 1.45F, 0.70F, 4.00F)

                // The raised upper hook-and-loop field and its narrow center pull tab dominate the clean face.
                .texOffs(94, 0)
                .addBox(-2.85F, 1.45F, -2.82F, 5.70F, 1.50F, 0.36F)
                .texOffs(108, 0)
                .addBox(-0.31F, 0.55F, -3.06F, 0.62F, 1.55F, 0.38F)
                .texOffs(111, 0)
                .addBox(-3.10F, 3.25F, -2.72F, 6.20F, 0.18F, 0.26F)

                // Compact strap keepers and buckles reproduce the ribbed shoulder adjustment hardware.
                .texOffs(0, 22)
                .addBox(-3.30F, 0.48F, -2.95F, 1.25F, 0.20F, 0.25F)
                .texOffs(0, 22)
                .addBox(2.05F, 0.48F, -2.95F, 1.25F, 0.20F, 0.25F)
                .texOffs(0, 22)
                .addBox(-3.30F, 1.18F, -2.95F, 1.25F, 0.20F, 0.25F)
                .texOffs(0, 22)
                .addBox(2.05F, 1.18F, -2.95F, 1.25F, 0.20F, 0.25F)
                .texOffs(4, 22)
                .addBox(-3.05F, 2.08F, -3.05F, 0.75F, 0.55F, 0.36F)
                .texOffs(4, 22)
                .addBox(2.30F, 2.08F, -3.05F, 0.75F, 0.55F, 0.36F)

                // Lower reinforcement panels carry stitches and MOLLE courses, never magazine-shaped solids.
                .texOffs(14, 16)
                .addBox(-3.40F, 8.35F, -2.72F, 6.80F, 1.55F, 0.36F)
                .texOffs(44, 16)
                .addBox(-2.85F, 1.55F, 2.50F, 5.70F, 1.25F, 0.30F)
                .texOffs(89, 16)
                .addBox(-3.40F, 8.35F, 2.40F, 6.80F, 1.35F, 0.32F)

                // Narrow dangling tails identify the adjustment system without becoming a groin protector.
                .texOffs(80, 16)
                .addBox(-3.25F, 9.35F, -2.64F, 0.52F, 1.95F, 0.30F)
                .texOffs(80, 16)
                .addBox(2.73F, 9.35F, -2.64F, 0.52F, 1.95F, 0.30F)
                .texOffs(83, 16)
                .addBox(-0.32F, 9.45F, -2.78F, 0.64F, 1.65F, 0.30F)
                .texOffs(86, 16)
                .addBox(-3.21F, 11.02F, -2.76F, 0.52F, 0.48F, 0.34F)
                .texOffs(86, 16)
                .addBox(2.69F, 11.02F, -2.76F, 0.52F, 0.48F, 0.34F);

        for (int row = 0; row < 6; row++) {
            body.texOffs(0, 16).addBox(
                    -3.10F, 4.15F + row * 0.73F, -2.74F, 6.20F, 0.18F, 0.28F);
        }
        for (int row = 0; row < 3; row++) {
            body.texOffs(30, 16).addBox(
                    -3.125F, 8.72F + row * 0.42F, -2.92F, 6.25F, 0.18F, 0.24F);
        }
        for (int row = 0; row < 5; row++) {
            body.texOffs(57, 16).addBox(
                    -3.10F, 4.20F + row * 0.82F, 2.46F, 6.20F, 0.18F, 0.24F);
        }
        for (int row = 0; row < 3; row++) {
            body.texOffs(105, 16).addBox(
                    -3.125F, 8.62F + row * 0.38F, 2.68F, 6.25F, 0.18F, 0.20F);
        }
        for (int row = 0; row < 4; row++) {
            float y = 4.78F + row * 1.15F;
            body.texOffs(71, 16).addBox(-3.99F, y, -1.75F, 0.20F, 0.18F, 3.50F);
            body.texOffs(71, 16).addBox(3.79F, y, -1.75F, 0.20F, 0.18F, 3.50F);
        }
        return body;
    }
}
