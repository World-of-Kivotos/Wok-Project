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

/** Dense Eagle Industries MMAC plate carrier with four front magazine cells. */
public final class MmacArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_mmac_ranger_green"), "main");

    private static final CubeDeformation CARRIER_DEFORMATION = new CubeDeformation(0.16F);

    public MmacArmorModel(ModelPart root) {
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
                .addBox(-3.75F, 1.0F, -2.68F, 7.50F, 9.60F, 0.58F)
                .texOffs(50, 0)
                .addBox(-3.70F, 1.10F, 2.10F, 7.40F, 9.30F, 0.58F)
                .texOffs(75, 0)
                .addBox(-4.58F, 4.50F, -2.20F, 0.48F, 6.20F, 4.40F)
                .texOffs(75, 0)
                .addBox(4.10F, 4.50F, -2.20F, 0.48F, 6.20F, 4.40F)
                .texOffs(100, 0)
                .addBox(-3.50F, -0.25F, -2.72F, 1.35F, 4.15F, 0.48F)
                .texOffs(100, 0)
                .addBox(2.15F, -0.25F, -2.72F, 1.35F, 4.15F, 0.48F)
                .texOffs(0, 22)
                .addBox(-3.50F, -0.25F, 2.24F, 1.35F, 4.15F, 0.48F)
                .texOffs(0, 22)
                .addBox(2.15F, -0.25F, 2.24F, 1.35F, 4.15F, 0.48F)
                .texOffs(25, 22)
                .addBox(-3.47F, -0.38F, -2.28F, 1.29F, 0.55F, 4.56F)
                .texOffs(25, 22)
                .addBox(2.18F, -0.38F, -2.28F, 1.29F, 0.55F, 4.56F)
                .texOffs(50, 22)
                .addBox(-2.80F, 1.80F, -2.92F, 5.60F, 1.40F, 0.30F)
                .texOffs(25, 44)
                .addBox(-5.10F, 6.0F, -1.70F, 0.55F, 3.80F, 3.40F)
                .texOffs(25, 44)
                .addBox(4.55F, 6.0F, -1.70F, 0.55F, 3.80F, 3.40F)
                .texOffs(50, 44)
                .addBox(-4.96F, 2.80F, -1.0F, 0.40F, 3.0F, 2.0F)
                .texOffs(75, 44)
                .addBox(-3.82F, 10.20F, -2.87F, 7.64F, 0.90F, 0.34F)
                .texOffs(75, 44)
                .addBox(-3.82F, 10.20F, 2.53F, 7.64F, 0.90F, 0.34F)
                .texOffs(100, 44)
                .addBox(-2.55F, 10.80F, -2.92F, 0.32F, 2.75F, 0.25F)
                .texOffs(100, 44)
                .addBox(2.23F, 10.80F, -2.92F, 0.32F, 2.75F, 0.25F);

        for (int row = 0; row < 4; row++) {
            body.texOffs(75, 22).addBox(-3.25F, 3.55F + row * 0.78F, -2.88F, 6.50F, 0.20F, 0.24F);
        }
        for (int column = 0; column < 4; column++) {
            float x = -3.42F + column * 1.72F;
            body.texOffs(100, 22).addBox(x, 5.85F, -3.08F, 1.55F, 4.40F, 0.46F);
            body.texOffs(0, 44).addBox(x - 0.03F, 4.92F, -3.20F, 1.61F, 1.04F, 0.22F);
        }
        return body;
    }
}

