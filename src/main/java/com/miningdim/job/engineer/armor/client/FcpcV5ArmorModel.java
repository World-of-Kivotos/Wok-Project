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

/** Narrow FCPC V5 carrier with a radio antenna, four magazines and a raised lower utility pouch. */
public final class FcpcV5ArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_fcpc_v5"), "main");

    private static final CubeDeformation CARRIER_DEFORMATION = new CubeDeformation(0.08F);

    public FcpcV5ArmorModel(ModelPart root) {
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
                .addBox(-2.90F, 0.90F, -2.62F, 5.80F, 7.55F, 0.52F)
                .texOffs(41, 0)
                .addBox(-2.95F, 0.85F, 2.10F, 5.90F, 7.75F, 0.52F)
                .texOffs(57, 0)
                .addBox(-3.55F, 5.00F, -2.00F, 0.30F, 4.20F, 4.00F)
                .texOffs(57, 0)
                .addBox(3.25F, 5.00F, -2.00F, 0.30F, 4.20F, 4.00F)
                .texOffs(67, 0)
                .addBox(-3.45F, -0.25F, -2.82F, 1.35F, 3.70F, 0.52F)
                .texOffs(67, 0)
                .addBox(2.10F, -0.25F, -2.82F, 1.35F, 3.70F, 0.52F)
                .texOffs(72, 0)
                .addBox(-3.45F, -0.25F, 2.30F, 1.35F, 3.70F, 0.52F)
                .texOffs(72, 0)
                .addBox(2.10F, -0.25F, 2.30F, 1.35F, 3.70F, 0.52F)
                .texOffs(77, 0)
                .addBox(-3.37F, -0.45F, -2.22F, 1.19F, 0.55F, 4.44F)
                .texOffs(77, 0)
                .addBox(2.18F, -0.45F, -2.22F, 1.19F, 0.55F, 4.44F)
                .texOffs(89, 0)
                .addBox(-2.65F, 1.55F, -2.91F, 5.30F, 1.05F, 0.22F)

                // Radio and antenna are held forward of the left arm and remain clear of its
                // neutral z=-2 plane while the pouch stays visually connected to the carrier.
                .texOffs(0, 20)
                .addBox(-5.22F, 5.10F, -3.62F, 2.10F, 4.55F, 1.47F)
                .texOffs(8, 20)
                .addBox(-5.08F, 4.20F, -3.78F, 1.46F, 4.65F, 0.78F)
                .texOffs(13, 20)
                .addBox(-4.72F, 0.35F, -3.62F, 0.22F, 4.15F, 0.22F)
                .texOffs(16, 20)
                .addBox(-5.30F, 4.82F, -3.88F, 1.98F, 1.02F, 1.48F)

                .texOffs(45, 20)
                .addBox(3.12F, 6.05F, -3.48F, 1.72F, 3.25F, 1.16F)
                .texOffs(51, 20)
                .addBox(3.02F, 5.78F, -3.61F, 1.92F, 0.95F, 1.30F)
                .texOffs(0, 40)
                .addBox(3.28F, 6.70F, -3.77F, 1.40F, 2.00F, 0.32F)
                .texOffs(25, 40)
                .addBox(3.86F, 6.95F, -3.94F, 0.20F, 1.55F, 0.50F)

                // The reference uses a broad, shallow soft dangler rather than a tall utility pouch.
                .texOffs(58, 20)
                .addBox(-2.40F, 8.90F, -3.78F, 4.80F, 1.85F, 1.10F)
                .texOffs(72, 20)
                .addBox(-2.50F, 8.42F, -3.88F, 5.00F, 0.70F, 1.26F)
                .texOffs(86, 20)
                .addBox(-0.72F, 9.20F, -4.02F, 0.24F, 1.20F, 0.22F)
                .texOffs(86, 20)
                .addBox(0.50F, 9.20F, -4.02F, 0.24F, 1.20F, 0.22F)
                .texOffs(114, 0)
                .addBox(-3.00F, 2.75F, -3.05F, 0.58F, 0.65F, 0.22F)
                .texOffs(114, 0)
                .addBox(2.42F, 2.75F, -3.05F, 0.58F, 0.65F, 0.22F);

        for (int row = 0; row < 4; row++) {
            body.texOffs(101, 0).addBox(-2.95F, 3.05F + row * 0.72F, -2.96F,
                    5.90F, 0.18F, 0.20F);
        }
        for (int column = 0; column < 4; column++) {
            float x = -1.95F + column * 1.40F;
            body.texOffs(23, 20).addBox(x, 4.78F, -3.31F, 1.22F, 3.68F, 0.66F);
            body.texOffs(29, 20).addBox(x + 0.08F, 3.82F, -3.12F, 1.06F, 1.35F, 0.42F);
            body.texOffs(34, 20).addBox(x - 0.03F, 5.95F, -3.45F, 1.28F, 0.42F, 0.20F);
        }
        return body;
    }
}
