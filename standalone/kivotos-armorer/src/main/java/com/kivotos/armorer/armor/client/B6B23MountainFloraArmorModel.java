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

/** 6B23-2 mountain-flora soft armor with a layered shell, high collar and long protective skirt. */
public final class B6B23MountainFloraArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_6b23_2_mountain_flora"), "main");

    public B6B23MountainFloraArmorModel(ModelPart root) {
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
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        return CubeListBuilder.create()
                // Three stepped soft-armor courses reproduce the rounded shell.
                .texOffs(0, 0)
                .addBox(-3.25F, 0.50F, -2.36F, 6.50F, 3.20F, 0.42F)
                .texOffs(15, 0)
                .addBox(-3.25F, 0.50F, 1.94F, 6.50F, 3.20F, 0.42F)
                .texOffs(30, 0)
                .addBox(-3.75F, 3.65F, -2.40F, 7.50F, 4.05F, 0.47F)
                .texOffs(47, 0)
                .addBox(-3.75F, 3.65F, 1.93F, 7.50F, 4.05F, 0.47F)
                .texOffs(64, 0)
                .addBox(-4.00F, 7.65F, -2.44F, 8.00F, 4.10F, 0.48F)
                .texOffs(82, 0)
                .addBox(-4.00F, 7.65F, 1.96F, 8.00F, 4.10F, 0.48F)
                .texOffs(100, 0)
                .addBox(-3.98F, 3.72F, -1.98F, 0.46F, 8.10F, 3.96F)
                .texOffs(110, 0)
                .addBox(3.52F, 3.72F, -1.98F, 0.46F, 8.10F, 3.96F)

                // A tall five-piece collar surrounds the neck; staggered y faces prevent shimmer.
                .texOffs(0, 14)
                .addBox(-4.35F, -1.75F, -4.48F, 3.75F, 1.80F, 0.44F)
                .texOffs(10, 14)
                .addBox(0.60F, -1.75F, -4.48F, 3.75F, 1.80F, 0.44F)
                .texOffs(20, 14)
                .addBox(-4.35F, -1.68F, 4.04F, 8.70F, 1.68F, 0.44F)
                .texOffs(40, 14)
                .addBox(-4.47F, -1.61F, -4.06F, 0.44F, 1.56F, 8.12F)
                .texOffs(59, 14)
                .addBox(4.03F, -1.61F, -4.06F, 0.44F, 1.56F, 8.12F)

                // Thick top yokes bridge the collar into both faces of the vest.
                .texOffs(78, 14)
                .addBox(-3.55F, -0.12F, -4.08F, 3.00F, 0.90F, 2.16F)
                .texOffs(90, 14)
                .addBox(0.55F, -0.12F, -4.08F, 3.00F, 0.90F, 2.16F)
                .texOffs(102, 14)
                .addBox(-3.55F, -0.12F, 1.92F, 3.00F, 0.90F, 2.16F)
                .texOffs(114, 14)
                .addBox(0.55F, -0.12F, 1.92F, 3.00F, 0.90F, 2.16F)

                // Broad front fold, overlapping long apron and an independently raised waist seam.
                .texOffs(0, 25)
                .addBox(-3.90F, 7.55F, -2.70F, 7.80F, 4.25F, 0.34F)
                .texOffs(18, 25)
                .addBox(-3.10F, 11.60F, -2.67F, 6.20F, 3.80F, 0.36F)
                .texOffs(33, 25)
                .addBox(-3.70F, 6.90F, -2.70F, 7.40F, 0.38F, 0.29F)

                // Separate face layers make the padded upper and middle panels visibly three-dimensional.
                .texOffs(50, 25)
                .addBox(-3.05F, 0.72F, -2.58F, 6.10F, 2.70F, 0.25F)
                .texOffs(64, 25)
                .addBox(-3.50F, 3.85F, -2.64F, 7.00F, 3.50F, 0.28F)

                // Raised stitched seams are offset from the panel faces instead of sharing them.
                .texOffs(80, 25)
                .addBox(-2.90F, 2.00F, -2.72F, 5.80F, 0.22F, 0.18F)
                .texOffs(93, 25)
                .addBox(-3.25F, 5.25F, -2.78F, 6.50F, 0.22F, 0.18F)
                .texOffs(108, 25)
                .addBox(-3.45F, 9.00F, -2.86F, 6.90F, 0.24F, 0.20F)
                .texOffs(124, 25)
                .addBox(-3.64F, 4.00F, -2.76F, 0.24F, 7.40F, 0.20F)
                .texOffs(126, 25)
                .addBox(3.40F, 4.00F, -2.76F, 0.24F, 7.40F, 0.20F)

                // Short side guards stop above the leg pivots; only the front apron hangs lower.
                .texOffs(0, 34)
                .addBox(-3.94F, 9.40F, -2.18F, 0.50F, 2.45F, 4.36F)
                .texOffs(11, 34)
                .addBox(3.44F, 9.40F, -2.18F, 0.50F, 2.45F, 4.36F)
                .texOffs(22, 34)
                .addBox(-2.95F, 14.60F, -2.82F, 5.90F, 0.26F, 0.18F)

                // Two vertical shoulder-top tabs reproduce the layered cap seams in the reference.
                .texOffs(36, 34)
                .addBox(-3.20F, 0.25F, -2.64F, 1.10F, 1.60F, 0.28F)
                .texOffs(40, 34)
                .addBox(2.10F, 0.25F, -2.64F, 1.10F, 1.60F, 0.28F);
    }
}

