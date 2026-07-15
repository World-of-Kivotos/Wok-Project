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

/** NFM THOR 一体式防弹护甲的首个原生人形模型样板。 */
public final class ThorIntegratedArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_thor_integrated"), "main");

    private static final CubeDeformation CARRIER_DEFORMATION = new CubeDeformation(0.28F);
    private static final CubeDeformation SHOULDER_DEFORMATION = new CubeDeformation(0.34F);

    public ThorIntegratedArmorModel(ModelPart root) {
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
        root.addOrReplaceChild("right_arm", createRightShoulder(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", createLeftShoulder(), PartPose.offset(5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        return CubeListBuilder.create()
                .texOffs(0, 0)
                .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, CARRIER_DEFORMATION)
                .texOffs(26, 0)
                .addBox(-3.65F, 1.10F, -2.96F, 7.30F, 8.75F, 0.58F)
                .texOffs(44, 0)
                .addBox(-3.75F, 1.00F, 2.38F, 7.50F, 9.45F, 0.58F)
                .texOffs(63, 0)
                .addBox(-4.86F, 3.00F, -2.34F, 0.50F, 7.80F, 4.68F)
                .texOffs(75, 0)
                .addBox(4.36F, 3.00F, -2.34F, 0.50F, 7.80F, 4.68F)
                .texOffs(0, 18)
                .addBox(-4.30F, -2.45F, -4.70F, 8.60F, 3.35F, 0.62F)
                .texOffs(20, 18)
                .addBox(-4.30F, -2.45F, 4.08F, 8.60F, 3.35F, 0.62F)
                .texOffs(40, 18)
                .addBox(-4.70F, -2.30F, -4.16F, 0.62F, 3.20F, 8.32F)
                .texOffs(58, 18)
                .addBox(4.08F, -2.30F, -4.16F, 0.62F, 3.20F, 8.32F)
                .texOffs(76, 18)
                .addBox(-3.65F, -0.25F, -3.22F, 1.20F, 4.25F, 0.48F)
                .texOffs(81, 18)
                .addBox(2.45F, -0.25F, -3.22F, 1.20F, 4.25F, 0.48F)
                .texOffs(86, 18)
                .addBox(-3.65F, -0.25F, 2.74F, 1.20F, 4.25F, 0.48F)
                .texOffs(91, 18)
                .addBox(2.45F, -0.25F, 2.74F, 1.20F, 4.25F, 0.48F)
                .texOffs(0, 30)
                .addBox(-4.00F, 9.90F, -3.08F, 8.00F, 1.55F, 0.55F)
                .texOffs(19, 30)
                .addBox(-4.00F, 9.90F, 2.53F, 8.00F, 1.55F, 0.55F)
                .texOffs(38, 30)
                .addBox(-2.25F, 11.10F, -2.92F, 4.50F, 4.85F, 0.46F)
                .texOffs(50, 30)
                .addBox(-5.25F, 5.10F, -1.55F, 0.72F, 4.20F, 3.10F)
                .texOffs(59, 30)
                .addBox(4.53F, 5.10F, -1.55F, 0.72F, 4.20F, 3.10F);
    }

    private static CubeListBuilder createRightShoulder() {
        return CubeListBuilder.create()
                .texOffs(0, 48)
                .addBox(-3.0F, -2.0F, -2.0F, 4.0F, 4.75F, 4.0F, SHOULDER_DEFORMATION)
                .texOffs(36, 48)
                .addBox(-3.48F, -2.66F, -2.48F, 4.96F, 0.52F, 4.96F)
                .texOffs(78, 48)
                .addBox(-3.74F, -1.90F, -2.43F, 0.46F, 5.15F, 4.86F)
                .texOffs(0, 60)
                .addBox(-3.42F, -1.92F, -2.73F, 4.84F, 4.70F, 0.43F)
                .texOffs(24, 60)
                .addBox(-3.42F, -1.92F, 2.30F, 4.84F, 4.70F, 0.43F);
    }

    private static CubeListBuilder createLeftShoulder() {
        return CubeListBuilder.create()
                .texOffs(18, 48)
                .addBox(-1.0F, -2.0F, -2.0F, 4.0F, 4.75F, 4.0F, SHOULDER_DEFORMATION)
                .texOffs(57, 48)
                .addBox(-1.48F, -2.66F, -2.48F, 4.96F, 0.52F, 4.96F)
                .texOffs(90, 48)
                .addBox(3.28F, -1.90F, -2.43F, 0.46F, 5.15F, 4.86F)
                .texOffs(12, 60)
                .addBox(-1.42F, -1.92F, -2.73F, 4.84F, 4.70F, 0.43F)
                .texOffs(36, 60)
                .addBox(-1.42F, -1.92F, 2.30F, 4.84F, 4.70F, 0.43F);
    }
}
