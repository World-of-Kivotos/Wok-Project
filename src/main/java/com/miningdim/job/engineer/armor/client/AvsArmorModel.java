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

/** Crye AVS shared silhouette: zipped side packs, triple magazines and a tapered MOLLE groin flap. */
public final class AvsArmorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_avs"), "main");

    public AvsArmorModel(ModelPart root) { super(root); }

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
                // Plate envelope and shoulder bridges shared by both AVS colourways.
                .texOffs(0, 0).addBox(-3.45F, 0.70F, -2.43F, 6.90F, 6.30F, 0.45F)
                .texOffs(16, 0).addBox(-3.45F, 0.70F, 1.98F, 6.90F, 6.30F, 0.45F)
                .texOffs(32, 0).addBox(-3.96F, 4.55F, -2.02F, 0.44F, 6.30F, 4.04F)
                .texOffs(42, 0).addBox(3.52F, 4.55F, -2.02F, 0.44F, 6.30F, 4.04F)
                .texOffs(52, 0).addBox(-3.55F, -0.30F, -2.50F, 1.30F, 1.30F, 4.90F)
                .texOffs(66, 0).addBox(2.25F, -0.30F, -2.50F, 1.30F, 1.30F, 4.90F)
                .texOffs(80, 0).addBox(-3.90F, 6.45F, -2.56F, 7.80F, 2.65F, 0.59F)
                .texOffs(98, 0).addBox(-3.90F, 6.45F, 1.97F, 7.80F, 2.65F, 0.59F)

                // Two full-height zipped side packs; their lids project beyond the bodies.
                .texOffs(116, 0).addBox(-3.98F, 5.80F, -3.42F, 1.90F, 5.00F, 1.10F)
                .texOffs(0, 12).addBox(2.08F, 5.80F, -3.42F, 1.90F, 5.00F, 1.10F)

                // Open-topped triple magazine bank and the right-side radio body.
                .texOffs(7, 12).addBox(-2.22F, 5.25F, -3.48F, 1.40F, 4.65F, 1.08F)
                .texOffs(13, 12).addBox(-0.70F, 5.25F, -3.48F, 1.40F, 4.65F, 1.08F)
                .texOffs(19, 12).addBox(0.82F, 5.25F, -3.48F, 1.40F, 4.65F, 1.08F)
                .texOffs(25, 12).addBox(2.95F, 1.65F, -3.12F, 0.88F, 4.85F, 0.72F)

                // The long AVS groin panel is stepped to retain the triangular reference silhouette.
                .texOffs(30, 12).addBox(-2.70F, 8.82F, -2.72F, 5.40F, 2.05F, 0.36F)
                .texOffs(43, 12).addBox(-2.30F, 9.22F, -2.86F, 4.60F, 0.28F, 0.18F)
                .texOffs(54, 12).addBox(-2.30F, 9.70F, -2.86F, 4.60F, 0.28F, 0.18F)
                .texOffs(65, 12).addBox(-2.30F, 10.18F, -2.86F, 4.60F, 0.28F, 0.18F)
                .texOffs(76, 12).addBox(-2.30F, 10.66F, -2.86F, 4.60F, 0.28F, 0.18F)
                .texOffs(87, 12).addBox(-3.25F, 3.20F, -2.53F, 6.50F, 0.28F, 0.16F)
                .texOffs(102, 12).addBox(-3.25F, 4.00F, -2.53F, 6.50F, 0.28F, 0.16F)

                // Side-pack lids and raised zipper rails make the large pockets read at game scale.
                .texOffs(117, 12).addBox(-3.94F, 5.72F, -3.55F, 1.86F, 0.85F, 1.22F)
                .texOffs(0, 20).addBox(2.08F, 5.72F, -3.55F, 1.86F, 0.85F, 1.22F)
                .texOffs(8, 20).addBox(-3.80F, 6.62F, -3.58F, 0.18F, 3.82F, 0.18F)
                .texOffs(10, 20).addBox(3.62F, 6.62F, -3.58F, 0.18F, 3.82F, 0.18F)

                // Individual magazine lips are offset from both the magazines and plate face.
                .texOffs(12, 20).addBox(-2.20F, 4.72F, -3.60F, 1.36F, 0.55F, 1.22F)
                .texOffs(19, 20).addBox(-0.68F, 4.72F, -3.60F, 1.36F, 0.55F, 1.22F)
                .texOffs(26, 20).addBox(0.84F, 4.72F, -3.60F, 1.36F, 0.55F, 1.22F)

                // Two narrower apron stages preserve leg clearance while completing the long taper.
                .texOffs(33, 20).addBox(-2.20F, 10.82F, -2.69F, 4.40F, 2.05F, 0.35F)
                .texOffs(44, 20).addBox(-1.70F, 12.82F, -2.66F, 3.40F, 2.00F, 0.34F)
                .texOffs(53, 20).addBox(-1.85F, 11.48F, -2.83F, 3.70F, 0.28F, 0.18F)
                .texOffs(62, 20).addBox(-1.85F, 12.16F, -2.83F, 3.70F, 0.28F, 0.18F)
                .texOffs(71, 20).addBox(-1.35F, 13.30F, -2.80F, 2.70F, 0.28F, 0.18F);
    }
}
