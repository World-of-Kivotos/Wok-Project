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

/** Soft sleeveless BNTI Kirasa-N vest with a visible open-front neck guard. */
public final class KirasaNArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_kirasa_n_green"), "main");

    public KirasaNArmorModel(ModelPart root) {
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
                // Separate front and rear soft panels create a narrow shoulder line,
                // wider middle and broad lower vest instead of a rectangular shell.
                .texOffs(0, 0)
                .addBox(-3.25F, 0.55F, -2.26F, 6.50F, 3.25F, 0.32F)
                .texOffs(16, 0)
                .addBox(-3.25F, 0.55F, 1.94F, 6.50F, 3.25F, 0.32F)
                .texOffs(32, 0)
                .addBox(-3.65F, 3.80F, -2.28F, 7.30F, 4.0F, 0.34F)
                .texOffs(50, 0)
                .addBox(-3.65F, 3.80F, 1.94F, 7.30F, 4.0F, 0.34F)
                .texOffs(68, 0)
                .addBox(-3.90F, 7.80F, -2.30F, 7.80F, 3.75F, 0.36F)
                .texOffs(87, 0)
                .addBox(-3.90F, 7.80F, 1.94F, 7.80F, 3.75F, 0.36F)
                .texOffs(0, 16)
                .addBox(-4.16F, 3.80F, -1.94F, 0.51F, 7.75F, 3.88F)
                .texOffs(10, 16)
                .addBox(3.65F, 3.80F, -1.94F, 0.51F, 7.75F, 3.88F)

                // Five panels form a low open-front collar outside the vanilla
                // head silhouette. The 0.96-wide opening keeps it visibly soft.
                .texOffs(22, 16)
                .addBox(-4.48F, -0.82F, -4.48F, 4.0F, 1.30F, 0.36F)
                .texOffs(32, 16)
                .addBox(0.48F, -0.82F, -4.48F, 4.0F, 1.30F, 0.36F)
                .texOffs(42, 16)
                .addBox(-4.48F, -0.82F, 4.12F, 8.96F, 1.30F, 0.36F)
                .texOffs(63, 16)
                .addBox(-4.48F, -0.78F, -4.12F, 0.36F, 1.28F, 8.24F)
                .texOffs(81, 16)
                .addBox(4.12F, -0.78F, -4.12F, 0.36F, 1.28F, 8.24F)

                // Four inset yokes join the neck guard to the narrow upper vest
                // without adding any geometry to the arm bones.
                .texOffs(0, 30)
                .addBox(-3.25F, 0.32F, -4.16F, 2.77F, 0.23F, 2.22F)
                .texOffs(12, 30)
                .addBox(0.48F, 0.32F, -4.16F, 2.77F, 0.23F, 2.22F)
                .texOffs(24, 30)
                .addBox(-3.25F, 0.32F, 1.94F, 2.77F, 0.23F, 2.22F)
                .texOffs(36, 30)
                .addBox(0.48F, 0.32F, 1.94F, 2.77F, 0.23F, 2.22F)

                // The closure and chest flap overlap the front surface by 0.02,
                // avoiding the floating detail seen on the first pass.
                .texOffs(50, 30)
                .addBox(-0.10F, 1.0F, -2.42F, 0.20F, 9.70F, 0.18F)
                .texOffs(54, 30)
                .addBox(-0.02F, 1.20F, -2.45F, 3.22F, 2.10F, 0.21F);
    }
}
