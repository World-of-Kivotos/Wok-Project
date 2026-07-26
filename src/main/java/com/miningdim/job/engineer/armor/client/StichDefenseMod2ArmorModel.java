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

/**
 * Stich Defense mod.2 carrier with a dense asymmetric field loadout.
 * The reference has no collar, shoulder armor or groin guard; its silhouette comes from the
 * thick shoulder straps and the full-volume magazine, medical, radio and utility pouches.
 */
public final class StichDefenseMod2ArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_stich_defense_mod2"), "main");

    public StichDefenseMod2ArmorModel(ModelPart root) {
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
        CubeListBuilder body = CubeListBuilder.create()
                // Compact front/rear plate bags and a continuous flexible cummerbund.
                .texOffs(0, 0)
                .addBox(-3.55F, 0.55F, -2.56F, 7.10F, 9.65F, 0.56F)
                .texOffs(18, 0)
                .addBox(-3.55F, 0.55F, 2.00F, 7.10F, 9.65F, 0.56F)
                .texOffs(36, 0)
                .addBox(-3.98F, 3.55F, -2.08F, 0.46F, 6.95F, 4.16F)
                .texOffs(47, 0)
                .addBox(3.52F, 3.55F, -2.08F, 0.46F, 6.95F, 4.16F)

                // Four padded strap faces and two top bridges; arm bones deliberately stay empty.
                .texOffs(58, 0)
                .addBox(-3.58F, -0.48F, -2.92F, 1.55F, 4.25F, 0.62F)
                .texOffs(58, 0)
                .addBox(2.03F, -0.48F, -2.92F, 1.55F, 4.25F, 0.62F)
                .texOffs(66, 0)
                .addBox(-3.58F, -0.48F, 2.30F, 1.55F, 4.25F, 0.62F)
                .texOffs(66, 0)
                .addBox(2.03F, -0.48F, 2.30F, 1.55F, 4.25F, 0.62F)
                .texOffs(74, 0)
                .addBox(-3.53F, -0.68F, -2.34F, 1.45F, 0.62F, 4.68F)
                .texOffs(74, 0)
                .addBox(2.08F, -0.68F, -2.34F, 1.45F, 0.62F, 4.68F)

                // Upper webbing field, hook-and-loop strip and the raised triangular badge base.
                .texOffs(88, 0)
                .addBox(-3.20F, 1.34F, -2.88F, 6.40F, 0.34F, 0.28F)
                .texOffs(88, 0)
                .addBox(-3.20F, 2.18F, -2.88F, 6.40F, 0.34F, 0.28F)
                .texOffs(88, 0)
                .addBox(-3.20F, 3.02F, -2.88F, 6.40F, 0.34F, 0.28F)
                .texOffs(102, 0)
                .addBox(-2.95F, 0.72F, -2.88F, 5.90F, 0.48F, 0.26F)
                .texOffs(116, 0)
                .addBox(-0.75F, 1.10F, -3.13F, 1.50F, 1.20F, 0.30F)
                .texOffs(116, 8)
                .addBox(-0.12F, 1.28F, -3.32F, 0.24F, 0.22F, 0.22F)
                .texOffs(120, 8)
                .addBox(-0.34F, 1.54F, -3.31F, 0.68F, 0.20F, 0.21F)
                .texOffs(124, 8)
                .addBox(-0.58F, 1.80F, -3.30F, 1.16F, 0.20F, 0.20F)

                // Broad lower belt underpins the pouches without becoming a groin panel.
                .texOffs(0, 18)
                .addBox(-3.90F, 9.42F, -2.76F, 7.80F, 1.30F, 0.46F)
                .texOffs(18, 18)
                .addBox(-3.90F, 9.42F, 2.30F, 7.80F, 1.30F, 0.46F)

                // Left outer utility bag and its protruding lid, side ribs and pull strap.
                .texOffs(36, 18)
                .addBox(-5.78F, 5.05F, -3.66F, 2.40F, 5.15F, 1.28F)
                .texOffs(44, 18)
                .addBox(-5.84F, 4.72F, -3.84F, 2.52F, 1.16F, 1.46F)
                .texOffs(52, 18)
                .addBox(-5.62F, 5.82F, -3.90F, 0.24F, 3.62F, 0.22F)
                .texOffs(55, 18)
                .addBox(-4.70F, 5.48F, -3.96F, 0.30F, 3.95F, 0.22F)

                // Three independent left/centre equipment pouches with separate lids.
                .texOffs(59, 18)
                .addBox(-3.48F, 4.82F, -3.62F, 1.34F, 5.05F, 1.10F)
                .texOffs(65, 18)
                .addBox(-3.58F, 4.55F, -3.80F, 1.42F, 1.02F, 1.28F)
                .texOffs(71, 18)
                .addBox(-2.08F, 5.18F, -3.70F, 1.94F, 4.88F, 1.18F)
                .texOffs(78, 18)
                .addBox(-2.12F, 4.90F, -3.88F, 2.02F, 1.05F, 1.36F)
                .texOffs(85, 18)
                .addBox(0.02F, 5.72F, -3.60F, 1.42F, 4.15F, 1.08F)
                .texOffs(91, 18)
                .addBox(-0.02F, 5.46F, -3.78F, 1.50F, 0.98F, 1.26F)

                // Two tall right-front magazine wells are staggered like the reference loadout.
                .texOffs(97, 18)
                .addBox(1.58F, 4.08F, -3.74F, 1.28F, 5.25F, 1.20F)
                .texOffs(103, 18)
                .addBox(2.88F, 4.52F, -3.78F, 1.22F, 5.05F, 1.22F)
                .texOffs(109, 18)
                .addBox(1.53F, 3.86F, -3.90F, 1.38F, 0.90F, 1.35F)
                .texOffs(115, 18)
                .addBox(2.83F, 4.28F, -3.94F, 1.32F, 0.90F, 1.38F)

                // Right outer utility pouch gives the carrier a genuinely loaded side waist.
                .texOffs(0, 36)
                .addBox(4.02F, 5.18F, -3.62F, 2.18F, 4.90F, 1.24F)
                .texOffs(8, 36)
                .addBox(3.96F, 4.88F, -3.84F, 2.30F, 1.12F, 1.46F)
                .texOffs(16, 36)
                .addBox(5.08F, 5.62F, -3.88F, 0.26F, 3.72F, 0.22F)

                // Three colored cylindrical/tool sleeves and their retaining bands.
                .texOffs(20, 36)
                .addBox(3.28F, 5.45F, -4.10F, 0.48F, 4.32F, 0.48F)
                .texOffs(24, 36)
                .addBox(3.84F, 5.45F, -4.10F, 0.48F, 4.32F, 0.48F)
                .texOffs(28, 36)
                .addBox(4.40F, 5.45F, -4.10F, 0.48F, 4.32F, 0.48F)
                .texOffs(32, 36)
                .addBox(3.20F, 6.20F, -4.25F, 1.78F, 0.38F, 0.24F)
                .texOffs(32, 36)
                .addBox(3.20F, 8.18F, -4.25F, 1.78F, 0.38F, 0.24F)

                // Lanyards, buckles and the blue release tab keep the silhouette from reading flat.
                .texOffs(38, 36)
                .addBox(-5.45F, 2.48F, -3.22F, 0.22F, 3.05F, 0.22F)
                .texOffs(38, 36)
                .addBox(-4.95F, 2.10F, -3.22F, 0.22F, 3.45F, 0.22F)
                .texOffs(42, 36)
                .addBox(-1.58F, 6.08F, -4.04F, 0.72F, 0.72F, 0.30F)
                .texOffs(46, 36)
                .addBox(5.48F, 9.20F, -3.94F, 0.36F, 2.15F, 0.28F);

        // Short horizontal retention ribs appear on every major front pouch.
        for (int row = 0; row < 3; row++) {
            body.texOffs(52, 36).addBox(-3.28F, 6.02F + row * 1.02F, -3.94F,
                    4.50F, 0.22F, 0.26F);
        }
        return body;
    }
}
