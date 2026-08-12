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

/** CPC MOD.1 carrier with thick shoulder pads, a rounded left bag and right-side black magazines. */
public final class CpcMod1ArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_cpc_mod1_atacs_fg"), "main");

    private static final CubeDeformation CARRIER_DEFORMATION = new CubeDeformation(0.10F);

    public CpcMod1ArmorModel(ModelPart root) {
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
                .addBox(-3.50F, 0.60F, -2.65F, 7.00F, 8.70F, 0.55F)
                .texOffs(42, 0)
                .addBox(-3.50F, 0.65F, 2.10F, 7.00F, 8.65F, 0.55F)
                .texOffs(58, 0)
                .addBox(-3.98F, 4.25F, -2.00F, 0.38F, 6.35F, 4.00F)
                .texOffs(58, 0)
                .addBox(3.60F, 4.25F, -2.00F, 0.38F, 6.35F, 4.00F)

                // These are padded harness blocks on the torso, not arm-mounted shoulder armour.
                .texOffs(68, 0)
                .addBox(-3.75F, -0.30F, -2.95F, 1.75F, 3.55F, 0.72F)
                .texOffs(68, 0)
                .addBox(2.00F, -0.30F, -2.95F, 1.75F, 3.55F, 0.72F)
                .texOffs(74, 0)
                .addBox(-3.75F, -0.30F, 2.23F, 1.75F, 3.55F, 0.72F)
                .texOffs(74, 0)
                .addBox(2.00F, -0.30F, 2.23F, 1.75F, 3.55F, 0.72F)
                .texOffs(80, 0)
                .addBox(-3.65F, -0.52F, -2.30F, 1.55F, 0.78F, 4.60F)
                .texOffs(80, 0)
                .addBox(2.10F, -0.52F, -2.30F, 1.55F, 0.78F, 4.60F)
                .texOffs(94, 0)
                .addBox(-2.70F, 1.45F, -3.05F, 5.40F, 1.10F, 0.24F)

                // Broad top, deep belly and a narrower lower bevel create the reference's round bulging bag.
                .texOffs(0, 40)
                .addBox(-6.18F, 5.00F, -4.08F, 3.10F, 4.75F, 1.82F)
                .texOffs(25, 40)
                .addBox(-6.32F, 4.67F, -4.23F, 3.38F, 1.25F, 2.00F)
                .texOffs(50, 40)
                .addBox(-5.95F, 9.40F, -3.95F, 2.60F, 1.05F, 1.60F)
                .texOffs(75, 40)
                .addBox(-5.98F, 5.85F, -4.36F, 2.70F, 3.35F, 0.30F)

                .texOffs(33, 20)
                .addBox(3.46F, 5.25F, -3.66F, 1.55F, 4.30F, 1.30F)
                .texOffs(40, 20)
                .addBox(3.36F, 4.98F, -3.80F, 1.75F, 1.02F, 1.45F)
                .texOffs(68, 20)
                .addBox(-0.45F, 4.45F, -3.62F, 0.45F, 2.70F, 0.55F)
                .texOffs(71, 20)
                .addBox(-0.50F, 6.85F, -3.70F, 0.55F, 0.55F, 0.25F)
                .texOffs(120, 0)
                .addBox(-3.12F, 2.70F, -3.24F, 0.65F, 0.70F, 0.25F)
                .texOffs(120, 0)
                .addBox(2.47F, 2.70F, -3.24F, 0.65F, 0.70F, 0.25F);

        for (int row = 0; row < 4; row++) {
            body.texOffs(106, 0).addBox(-3.05F, 3.05F + row * 0.73F, -2.90F,
                    6.10F, 0.18F, 0.30F);
        }
        for (int column = 0; column < 3; column++) {
            float x = 0.15F + column * 1.20F;
            body.texOffs(48, 20).addBox(x, 4.45F, -3.28F, 1.08F, 3.72F, 0.56F);
            body.texOffs(53, 20).addBox(x - 0.04F, 4.22F, -3.40F, 1.16F, 0.60F, 0.22F);
        }
        for (int column = 0; column < 2; column++) {
            float x = -2.90F + column * 1.68F;
            body.texOffs(57, 20).addBox(x, 7.30F, -3.42F, 1.50F, 2.10F, 0.82F);
            body.texOffs(63, 20).addBox(x - 0.05F, 7.05F, -3.55F, 1.60F, 0.75F, 0.24F);
        }
        return body;
    }
}

