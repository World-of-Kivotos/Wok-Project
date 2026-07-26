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

/** Smooth Gzhel-K armor with a soft raised collar, three MOLLE rows and a wide buckle belt. */
public final class GzhelKArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_gzhel_k"), "main");

    public GzhelKArmorModel(ModelPart root) {
        super(root);
    }

    private static CubeListBuilder body() {
        return CubeListBuilder.create()
                // Broad uninterrupted shell; this model intentionally has no invented magazine pouches.
                .texOffs(0, 0).addBox(-3.90F, 0.75F, -2.62F, 7.80F, 10.40F, 0.62F)
                .texOffs(18, 0).addBox(-3.90F, 0.75F, 2.00F, 7.80F, 10.40F, 0.62F)
                .texOffs(36, 0).addBox(-3.98F, 2.70F, -2.00F, 0.42F, 8.20F, 4.00F)
                .texOffs(46, 0).addBox(3.56F, 2.70F, -2.00F, 0.42F, 8.20F, 4.00F)
                .texOffs(56, 0).addBox(-3.65F, -0.05F, -2.98F, 3.45F, 1.25F, 0.32F)
                .texOffs(65, 0).addBox(0.20F, -0.05F, -2.98F, 3.45F, 1.25F, 0.32F)
                .texOffs(74, 0).addBox(-3.65F, -0.05F, 2.66F, 3.45F, 1.25F, 0.32F)
                .texOffs(83, 0).addBox(0.20F, -0.05F, 2.66F, 3.45F, 1.25F, 0.32F)

                // A thick five-piece base and offset upper rim make the collar read as padded fabric.
                .texOffs(92, 0).addBox(-4.05F, -1.25F, -4.25F, 3.75F, 2.15F, 0.65F)
                .texOffs(102, 0).addBox(0.30F, -1.25F, -4.25F, 3.75F, 2.15F, 0.65F)
                .texOffs(0, 14).addBox(-4.05F, -1.25F, 3.60F, 8.10F, 2.15F, 0.65F)
                .texOffs(19, 14).addBox(-3.95F, -1.25F, -3.60F, 0.50F, 2.15F, 7.20F)
                .texOffs(36, 14).addBox(3.45F, -1.25F, -3.60F, 0.50F, 2.15F, 7.20F)
                .texOffs(53, 14).addBox(-4.00F, -1.55F, -4.32F, 3.65F, 0.48F, 0.72F)
                .texOffs(63, 14).addBox(0.35F, -1.55F, -4.32F, 3.65F, 0.48F, 0.72F)
                .texOffs(73, 14).addBox(-4.00F, -1.55F, 3.60F, 8.00F, 0.48F, 0.72F)
                .texOffs(92, 14).addBox(-3.98F, -1.55F, -3.55F, 0.56F, 0.48F, 7.10F)
                .texOffs(109, 14).addBox(3.42F, -1.55F, -3.55F, 0.56F, 0.48F, 7.10F)

                // Exactly three segmented webbing rows cover the otherwise smooth front.
                .texOffs(0, 25).addBox(-3.35F, 2.40F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(4, 25).addBox(-2.22F, 2.40F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(8, 25).addBox(-1.09F, 2.40F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(12, 25).addBox(0.04F, 2.40F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(16, 25).addBox(1.17F, 2.40F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(20, 25).addBox(2.30F, 2.40F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(24, 25).addBox(-3.35F, 3.72F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(28, 25).addBox(-2.22F, 3.72F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(32, 25).addBox(-1.09F, 3.72F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(36, 25).addBox(0.04F, 3.72F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(40, 25).addBox(1.17F, 3.72F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(44, 25).addBox(2.30F, 3.72F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(48, 25).addBox(-3.35F, 5.04F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(52, 25).addBox(-2.22F, 5.04F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(56, 25).addBox(-1.09F, 5.04F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(60, 25).addBox(0.04F, 5.04F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(64, 25).addBox(1.17F, 5.04F, -2.80F, 0.92F, 0.28F, 0.18F)
                .texOffs(68, 25).addBox(2.30F, 5.04F, -2.80F, 0.92F, 0.28F, 0.18F)

                // The reference-defining wide belt and two-piece central buckle remain pouch-free.
                .texOffs(72, 25).addBox(-3.90F, 8.65F, -2.98F, 7.80F, 1.25F, 0.36F)
                .texOffs(90, 25).addBox(-3.90F, 8.65F, 2.62F, 7.80F, 1.25F, 0.36F)
                .texOffs(108, 25).addBox(-3.94F, 8.65F, -2.40F, 0.38F, 1.25F, 4.80F)
                .texOffs(0, 33).addBox(3.56F, 8.65F, -2.40F, 0.38F, 1.25F, 4.80F)
                .texOffs(12, 33).addBox(-1.18F, 8.75F, -3.35F, 2.35F, 1.05F, 0.42F)
                .texOffs(19, 33).addBox(-0.55F, 8.92F, -3.58F, 1.10F, 0.72F, 0.20F)
                .texOffs(23, 33).addBox(-2.82F, 7.70F, -3.10F, 0.40F, 2.10F, 0.24F)
                .texOffs(26, 33).addBox(2.42F, 7.70F, -3.10F, 0.40F, 2.10F, 0.24F)
                .texOffs(29, 33).addBox(-3.80F, 10.72F, -2.92F, 7.60F, 0.45F, 0.30F)

                // Four short steps soften the shoulder-to-armhole transition without entering the arms.
                .texOffs(46, 33).addBox(-3.82F, 0.95F, -2.94F, 0.62F, 1.45F, 0.32F)
                .texOffs(49, 33).addBox(3.20F, 0.95F, -2.94F, 0.62F, 1.45F, 0.32F)
                .texOffs(52, 33).addBox(-3.82F, 0.95F, 2.62F, 0.62F, 1.45F, 0.32F)
                .texOffs(55, 33).addBox(3.20F, 0.95F, 2.62F, 0.62F, 1.45F, 0.32F);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("body", body(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }
}
