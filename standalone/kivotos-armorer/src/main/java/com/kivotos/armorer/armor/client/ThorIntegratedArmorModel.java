package com.kivotos.armorer.armor.client;

import com.kivotos.armorer.ArmorerMod;
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
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_thor_integrated"), "main");

    private static final CubeDeformation CARRIER_DEFORMATION = new CubeDeformation(0.14F);
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
        CubeListBuilder body = CubeListBuilder.create()
                .texOffs(0, 0)
                .addBox(-3.75F, 0.0F, -2.0F, 7.50F, 12.0F, 4.0F, CARRIER_DEFORMATION)
                .texOffs(26, 0)
                .addBox(-3.65F, 1.10F, -2.96F, 7.30F, 8.75F, 0.58F)
                .texOffs(44, 0)
                .addBox(-3.75F, 1.00F, 2.38F, 7.50F, 9.45F, 0.58F)
                .texOffs(63, 0)
                .addBox(-3.95F, 3.00F, -2.40F, 0.50F, 7.80F, 4.80F)
                .texOffs(75, 0)
                .addBox(3.45F, 3.00F, -2.40F, 0.50F, 7.80F, 4.80F)
                .texOffs(0, 72)
                .addBox(-4.30F, -1.15F, -4.70F, 1.55F, 2.01F, 0.62F)
                .texOffs(8, 72)
                .addBox(-2.75F, -0.55F, -4.70F, 5.50F, 1.37F, 0.62F)
                .texOffs(24, 72)
                .addBox(2.75F, -1.15F, -4.70F, 1.55F, 2.01F, 0.62F)
                .texOffs(20, 18)
                .addBox(-4.30F, -1.35F, 4.08F, 8.60F, 2.23F, 0.62F)
                .texOffs(40, 18)
                .addBox(-3.95F, -1.20F, -4.16F, 0.62F, 2.10F, 8.32F)
                .texOffs(58, 18)
                .addBox(3.33F, -1.20F, -4.16F, 0.62F, 2.10F, 8.32F)
                .texOffs(76, 18)
                .addBox(-3.61F, -0.25F, -3.22F, 1.20F, 4.25F, 0.48F)
                .texOffs(81, 18)
                .addBox(2.41F, -0.25F, -3.22F, 1.20F, 4.25F, 0.48F)
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
                .addBox(-5.68F, 5.36F, -5.08F, 1.76F, 4.20F, 3.00F)
                .texOffs(60, 30)
                .addBox(3.92F, 5.36F, -5.08F, 1.76F, 4.20F, 3.00F)
                .texOffs(8, 80)
                .addBox(-3.10F, 2.35F, -3.12F, 6.20F, 0.26F, 0.12F)
                .texOffs(8, 80)
                .addBox(-3.10F, 3.10F, -3.12F, 6.20F, 0.26F, 0.12F)
                .texOffs(24, 80)
                .addBox(-1.15F, 1.45F, -3.18F, 0.70F, 0.75F, 0.18F)
                .texOffs(24, 80)
                .addBox(0.45F, 1.45F, -3.18F, 0.70F, 0.75F, 0.18F);

        for (int row = 0; row < 5; row++) {
            for (int column = 0; column < 4; column++) {
                body.texOffs(0, 80).addBox(
                        -3.075F + column * 1.65F,
                        4.04F + row * 1.15F,
                        -3.12F,
                        1.20F,
                        0.24F,
                        0.12F);
            }
        }
        return body;
    }

    private static CubeListBuilder createRightShoulder() {
        return CubeListBuilder.create()
                .texOffs(0, 48)
                .addBox(-3.0F, -2.0F, -2.0F, 3.65F, 4.75F, 4.0F, SHOULDER_DEFORMATION)
                .texOffs(36, 48)
                .addBox(-3.48F, -2.66F, -2.48F, 4.40F, 0.52F, 4.96F)
                .texOffs(78, 48)
                .addBox(-3.74F, -1.82F, -2.43F, 0.46F, 5.07F, 4.86F)
                .texOffs(0, 60)
                .addBox(-3.42F, -1.92F, -2.73F, 4.32F, 4.70F, 0.43F)
                .texOffs(24, 60)
                .addBox(-3.42F, -1.92F, 2.30F, 4.32F, 4.70F, 0.43F);
    }

    private static CubeListBuilder createLeftShoulder() {
        return CubeListBuilder.create()
                .texOffs(18, 48)
                .addBox(-0.65F, -2.0F, -2.0F, 3.65F, 4.75F, 4.0F, SHOULDER_DEFORMATION)
                .texOffs(57, 48)
                .addBox(-0.92F, -2.66F, -2.48F, 4.40F, 0.52F, 4.96F)
                .texOffs(90, 48)
                .addBox(3.28F, -1.82F, -2.43F, 0.46F, 5.07F, 4.86F)
                .texOffs(12, 60)
                .addBox(-0.90F, -1.92F, -2.73F, 4.32F, 4.70F, 0.43F)
                .texOffs(36, 60)
                .addBox(-0.90F, -1.92F, 2.30F, 4.32F, 4.70F, 0.43F);
    }
}

