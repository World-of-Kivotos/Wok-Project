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

/** Stich Profi V2 black carrier with twin magazines, deep side pockets and a hanging front pouch. */
public final class StichProfiV2ArmorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_stich_profi_v2_black"), "main");

    public StichProfiV2ArmorModel(ModelPart root) { super(root); }

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
                // Main plate envelope and broad waist belt.
                .texOffs(0, 0).addBox(-3.40F, 0.70F, -2.43F, 6.80F, 6.10F, 0.45F)
                .texOffs(16, 0).addBox(-3.40F, 0.70F, 1.98F, 6.80F, 6.10F, 0.45F)
                .texOffs(32, 0).addBox(-3.96F, 4.55F, -2.02F, 0.44F, 6.35F, 4.04F)
                .texOffs(42, 0).addBox(3.52F, 4.55F, -2.02F, 0.44F, 6.35F, 4.04F)
                .texOffs(52, 0).addBox(-3.52F, -0.28F, -2.48F, 1.30F, 1.28F, 4.88F)
                .texOffs(66, 0).addBox(2.22F, -0.28F, -2.48F, 1.30F, 1.28F, 4.88F)
                .texOffs(80, 0).addBox(-3.90F, 6.50F, -2.56F, 7.80F, 2.70F, 0.59F)
                .texOffs(98, 0).addBox(-3.90F, 6.50F, 1.97F, 7.80F, 2.70F, 0.59F)

                // Twin central magazine pouches remain the dominant front feature.
                .texOffs(116, 0).addBox(-1.85F, 5.20F, -3.45F, 1.75F, 4.25F, 1.05F)
                .texOffs(0, 12).addBox(0.10F, 5.20F, -3.45F, 1.75F, 4.25F, 1.05F)

                // Full-volume left medical pouch and right radio/utility pouch.
                .texOffs(7, 12).addBox(-3.98F, 5.55F, -3.38F, 1.85F, 4.90F, 1.02F)
                .texOffs(14, 12).addBox(2.75F, 4.65F, -3.28F, 1.23F, 5.25F, 0.92F)
                .texOffs(20, 12).addBox(3.70F, 0.35F, -2.90F, 0.16F, 4.50F, 0.16F)

                // The broad lower-front pouch hangs from, and overlaps, the waist belt.
                .texOffs(22, 12).addBox(-2.55F, 9.10F, -3.30F, 5.10F, 3.85F, 0.88F)
                .texOffs(35, 12).addBox(-3.10F, 3.25F, -2.62F, 6.20F, 0.30F, 0.24F)

                // Every pouch has a separately protruding lid or reinforced open-top lip.
                .texOffs(49, 12).addBox(-1.82F, 5.12F, -3.58F, 1.69F, 0.58F, 1.20F)
                .texOffs(56, 12).addBox(0.13F, 5.12F, -3.58F, 1.69F, 0.58F, 1.20F)
                .texOffs(63, 12).addBox(-3.92F, 5.45F, -3.52F, 1.81F, 0.85F, 1.15F)
                .texOffs(70, 12).addBox(2.73F, 4.55F, -3.40F, 1.19F, 0.75F, 1.07F)
                .texOffs(76, 12).addBox(-2.48F, 9.12F, -3.62F, 4.96F, 0.90F, 1.24F)

                // Layered MOLLE rails and raised pouch faces replace the old flat silhouette.
                .texOffs(90, 12).addBox(-3.10F, 4.05F, -2.64F, 6.20F, 0.30F, 0.25F)
                .texOffs(104, 12).addBox(-3.10F, 4.83F, -2.66F, 6.20F, 0.30F, 0.27F)
                .texOffs(118, 12).addBox(-3.75F, 6.55F, -3.50F, 1.35F, 2.60F, 0.16F)
                .texOffs(123, 12).addBox(2.90F, 5.70F, -3.40F, 0.95F, 2.40F, 0.16F)
                .texOffs(0, 20).addBox(-1.60F, 10.35F, -3.43F, 3.20F, 1.80F, 0.16F);
    }
}
