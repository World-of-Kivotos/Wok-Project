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

/** Tac-Kek JayPC light plate carrier; olive and black variants share this geometry. */
public final class JaypcArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_jaypc"), "main");

    private static final CubeDeformation SOFT_CARRIER = new CubeDeformation(0.18F);

    public JaypcArmorModel(ModelPart root) {
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
        root.addOrReplaceChild("right_arm", createRightShoulderStrap(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", createLeftShoulderStrap(), PartPose.offset(5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        return CubeListBuilder.create()
                .texOffs(0, 0)
                .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 11.70F, 4.0F, SOFT_CARRIER)
                .texOffs(26, 0)
                .addBox(-3.45F, 1.10F, -2.84F, 6.90F, 8.20F, 0.42F)
                .texOffs(42, 0)
                .addBox(-3.45F, 1.00F, 2.42F, 6.90F, 8.50F, 0.42F)
                .texOffs(58, 0)
                .addBox(-4.70F, 4.00F, -1.95F, 0.34F, 5.50F, 3.90F)
                .texOffs(68, 0)
                .addBox(4.36F, 4.00F, -1.95F, 0.34F, 5.50F, 3.90F)
                .texOffs(0, 20)
                .addBox(-3.35F, -0.80F, -3.02F, 1.15F, 4.80F, 0.32F)
                .texOffs(4, 20)
                .addBox(2.20F, -0.80F, -3.02F, 1.15F, 4.80F, 0.32F)
                .texOffs(8, 20)
                .addBox(-3.35F, -0.80F, 2.70F, 1.15F, 4.80F, 0.32F)
                .texOffs(12, 20)
                .addBox(2.20F, -0.80F, 2.70F, 1.15F, 4.80F, 0.32F)
                .texOffs(0, 28)
                .addBox(-3.15F, 4.10F, -3.04F, 6.30F, 0.22F, 0.12F)
                .texOffs(0, 28)
                .addBox(-3.15F, 5.25F, -3.04F, 6.30F, 0.22F, 0.12F)
                .texOffs(0, 28)
                .addBox(-3.15F, 6.40F, -3.04F, 6.30F, 0.22F, 0.12F)
                .texOffs(18, 20)
                .addBox(-1.40F, 7.55F, -3.12F, 2.80F, 2.60F, 0.34F)
                .texOffs(26, 20)
                .addBox(-5.04F, 6.00F, -0.95F, 0.42F, 3.46F, 1.90F)
                .texOffs(31, 20)
                .addBox(4.62F, 6.00F, -0.95F, 0.42F, 3.46F, 1.90F)
                .texOffs(36, 20)
                .addBox(-1.20F, 2.30F, -3.18F, 0.65F, 0.80F, 0.40F)
                .texOffs(40, 20)
                .addBox(0.55F, 2.30F, -3.18F, 0.65F, 0.80F, 0.40F);
    }

    private static CubeListBuilder createRightShoulderStrap() {
        return CubeListBuilder.create()
                .texOffs(0, 32)
                .addBox(-2.30F, -2.20F, -1.60F, 2.95F, 0.42F, 3.20F);
    }

    private static CubeListBuilder createLeftShoulderStrap() {
        return CubeListBuilder.create()
                .texOffs(14, 32)
                .addBox(-0.65F, -2.20F, -1.60F, 2.95F, 0.42F, 3.20F);
    }
}
