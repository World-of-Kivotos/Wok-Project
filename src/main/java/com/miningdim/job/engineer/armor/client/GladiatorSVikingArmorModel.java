package com.miningdim.job.engineer.armor.client;

import com.miningdim.core.MiningConstants;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/** Viking Gladiator-S with low neck, paired utility bags and a rectangular hanging pouch. */
public final class GladiatorSVikingArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_gladiator_s_viking"), "main");

    private static final CubeDeformation CARRIER_DEFORMATION = new CubeDeformation(0.10F);

    public GladiatorSVikingArmorModel(ModelPart root) {
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
                .texOffs(0, 0)
                .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, CARRIER_DEFORMATION)
                .texOffs(25, 0)
                .addBox(-3.60F, 0.70F, -2.65F, 7.20F, 8.70F, 0.55F)
                .texOffs(42, 0)
                .addBox(-3.60F, 0.70F, 2.10F, 7.20F, 8.70F, 0.55F)
                .texOffs(59, 0)
                .addBox(-3.98F, 4.10F, -2.00F, 0.40F, 6.40F, 4.00F)
                .texOffs(59, 0)
                .addBox(3.58F, 4.10F, -2.00F, 0.40F, 6.40F, 4.00F)

                // Broad straps form only a low padded neckline; no raised collar is present.
                .texOffs(69, 0)
                .addBox(-3.72F, -0.20F, -2.82F, 1.50F, 3.50F, 0.55F)
                .texOffs(69, 0)
                .addBox(2.22F, -0.20F, -2.82F, 1.50F, 3.50F, 0.55F)
                .texOffs(74, 0)
                .addBox(-3.72F, -0.20F, 2.27F, 1.50F, 3.50F, 0.55F)
                .texOffs(74, 0)
                .addBox(2.22F, -0.20F, 2.27F, 1.50F, 3.50F, 0.55F)
                .texOffs(79, 0)
                .addBox(-3.62F, -0.42F, -2.25F, 1.20F, 0.55F, 4.50F)
                .texOffs(79, 0)
                .addBox(2.42F, -0.42F, -2.25F, 1.20F, 0.55F, 4.50F)
                .texOffs(92, 0)
                .addBox(-2.90F, 1.55F, -2.94F, 5.80F, 1.00F, 0.22F)

                // Both utility bags are forward of the neutral arms and visibly project past the waist.
                .texOffs(0, 20)
                .addBox(-5.35F, 5.55F, -3.72F, 1.95F, 4.60F, 1.40F)
                .texOffs(8, 20)
                .addBox(-5.45F, 5.28F, -3.84F, 2.15F, 1.00F, 1.55F)
                .texOffs(17, 20)
                .addBox(3.40F, 5.55F, -3.72F, 1.95F, 4.60F, 1.40F)
                .texOffs(25, 20)
                .addBox(3.30F, 5.28F, -3.84F, 2.15F, 1.00F, 1.55F)

                // The long whip antenna is attached to the carrier, not to an arm bone.
                .texOffs(45, 20)
                .addBox(3.15F, 0.20F, -3.30F, 0.20F, 4.00F, 0.20F)
                .texOffs(47, 20)
                .addBox(2.98F, 3.80F, -3.42F, 0.55F, 0.70F, 0.45F)

                // This is a rectangular equipment pouch; its straight sides distinguish it from
                // the rounded protective groin panels used by other Gladiator configurations.
                .texOffs(51, 20)
                .addBox(-2.50F, 9.05F, -3.55F, 5.00F, 4.10F, 0.95F)
                .texOffs(64, 20)
                .addBox(-2.60F, 8.78F, -3.70F, 5.20F, 1.00F, 0.35F)
                .texOffs(77, 20)
                .addBox(-1.20F, 10.10F, -3.82F, 2.40F, 1.30F, 0.35F)
                .texOffs(84, 20)
                .addBox(-1.65F, 9.85F, -3.78F, 0.22F, 2.50F, 0.18F)
                .texOffs(84, 20)
                .addBox(1.43F, 9.85F, -3.78F, 0.22F, 2.50F, 0.18F)
                .texOffs(119, 0)
                .addBox(-3.25F, 2.75F, -3.12F, 0.60F, 0.65F, 0.22F)
                .texOffs(119, 0)
                .addBox(2.65F, 2.75F, -3.12F, 0.60F, 0.65F, 0.22F);

        for (int row = 0; row < 4; row++) {
            body.texOffs(105, 0).addBox(-3.10F, 3.05F + row * 0.72F, -2.92F,
                    6.20F, 0.18F, 0.28F);
        }
        for (int column = 0; column < 3; column++) {
            float x = -2.40F + column * 1.70F;
            body.texOffs(34, 20).addBox(x, 5.75F, -3.36F, 1.45F, 2.70F, 0.72F);
            body.texOffs(40, 20).addBox(x - 0.05F, 5.48F, -3.50F, 1.55F, 0.80F, 0.25F);
            body.texOffs(0, 40).addBox(x + 0.30F, 5.25F, -3.64F, 0.85F, 0.45F, 0.20F);
        }
        return body;
    }
}
