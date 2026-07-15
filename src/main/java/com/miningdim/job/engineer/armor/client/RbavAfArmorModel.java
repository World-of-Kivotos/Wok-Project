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

/** Bulky wraparound ECLiPSE RBAV-AF carrier with front tube cells and drop pouch. */
public final class RbavAfArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_rbav_af_ranger_green"), "main");

    private static final CubeDeformation CARRIER_DEFORMATION = new CubeDeformation(0.22F);

    public RbavAfArmorModel(ModelPart root) {
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
                .addBox(-3.85F, 1.15F, -2.86F, 7.70F, 8.95F, 0.70F)
                .texOffs(50, 0)
                .addBox(-3.85F, 1.05F, 2.16F, 7.70F, 9.20F, 0.66F)
                .texOffs(75, 0)
                .addBox(-4.82F, 3.10F, -2.32F, 0.62F, 7.70F, 4.64F)
                .texOffs(75, 0)
                .addBox(4.20F, 3.10F, -2.32F, 0.62F, 7.70F, 4.64F)
                .texOffs(100, 0)
                .addBox(-3.65F, -0.18F, -2.88F, 1.55F, 4.30F, 0.62F)
                .texOffs(100, 0)
                .addBox(2.10F, -0.18F, -2.88F, 1.55F, 4.30F, 0.62F)
                .texOffs(0, 22)
                .addBox(-3.65F, -0.18F, 2.26F, 1.55F, 4.30F, 0.62F)
                .texOffs(0, 22)
                .addBox(2.10F, -0.18F, 2.26F, 1.55F, 4.30F, 0.62F)
                .texOffs(25, 22)
                .addBox(-3.62F, -0.42F, -2.32F, 1.49F, 0.64F, 4.64F)
                .texOffs(25, 22)
                .addBox(2.13F, -0.42F, -2.32F, 1.49F, 0.64F, 4.64F)
                .texOffs(0, 44)
                .addBox(-5.45F, 5.0F, -1.70F, 0.68F, 4.50F, 3.40F)
                .texOffs(25, 44)
                .addBox(4.77F, 5.20F, -1.58F, 0.68F, 4.10F, 3.16F)
                .texOffs(75, 44)
                .addBox(-2.60F, 9.75F, -3.18F, 5.20F, 4.10F, 0.52F)
                .texOffs(100, 44)
                .addBox(-3.86F, 9.65F, -3.02F, 7.72F, 1.05F, 0.42F)
                .texOffs(100, 44)
                .addBox(-3.86F, 9.65F, 2.60F, 7.72F, 1.05F, 0.42F)
                .texOffs(0, 66)
                .addBox(-3.32F, 4.32F, -3.27F, 0.62F, 0.72F, 0.20F)
                .texOffs(0, 66)
                .addBox(2.67F, 4.32F, -3.27F, 0.62F, 0.72F, 0.20F);

        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 4; column++) {
                body.texOffs(50, 22).addBox(
                        -2.72F + column * 1.45F,
                        2.05F + row * 0.92F,
                        -3.10F,
                        0.48F,
                        0.66F,
                        0.30F);
            }
        }
        for (int column = 0; column < 4; column++) {
            float x = -3.42F + column * 1.72F;
            body.texOffs(75, 22).addBox(x, 5.48F, -3.22F, 1.55F, 4.20F, 0.48F);
            body.texOffs(100, 22).addBox(x - 0.03F, 4.52F, -3.34F, 1.61F, 1.08F, 0.24F);
        }
        for (int row = 0; row < 2; row++) {
            float y = 3.95F + row * 1.30F;
            body.texOffs(50, 44).addBox(-4.98F, y, -2.10F, 0.20F, 0.28F, 4.20F);
            body.texOffs(50, 44).addBox(4.78F, y, -2.10F, 0.20F, 0.28F, 4.20F);
        }
        return body;
    }
}
