package com.kivotos.armorer.armor.client;

import com.kivotos.armorer.ArmorerMod;
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

/** Osprey MK4A protection configuration with empty upper MOLLE and large articulated sleeves. */
public final class OspreyMk4AProtectionArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_osprey_mk4a_protection"), "main");

    public OspreyMk4AProtectionArmorModel(ModelPart root) {
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
                .addBox(-3.40F, 0.75F, -2.45F, 6.80F, 3.00F, 0.50F)
                .texOffs(16, 0)
                .addBox(-3.40F, 0.75F, 1.95F, 6.80F, 3.00F, 0.50F)
                .texOffs(32, 0)
                .addBox(-4.00F, 3.72F, -2.50F, 8.00F, 4.00F, 0.55F)
                .texOffs(51, 0)
                .addBox(-4.00F, 3.72F, 1.95F, 8.00F, 4.00F, 0.55F)
                .texOffs(70, 0)
                .addBox(-3.95F, 7.68F, -2.62F, 7.90F, 3.60F, 0.68F)
                .texOffs(89, 0)
                .addBox(-3.95F, 7.68F, 2.04F, 7.90F, 3.60F, 0.58F)

                // Side courses remain inside x=+-4 so the torso never enters the neutral arm volume.
                .texOffs(108, 0)
                .addBox(-3.90F, 3.55F, -2.00F, 0.32F, 7.55F, 4.00F)
                .texOffs(108, 0)
                .addBox(3.58F, 3.55F, -2.00F, 0.32F, 7.55F, 4.00F)

                // Compact five-piece ring gives the protection model its high collar without
                // placing any body cuboid in the arms' x range.
                .texOffs(0, 15)
                .addBox(-3.70F, -0.85F, -4.05F, 3.25F, 1.70F, 0.45F)
                .texOffs(0, 15)
                .addBox(0.45F, -0.85F, -4.05F, 3.25F, 1.70F, 0.45F)
                .texOffs(16, 15)
                .addBox(-3.70F, -0.85F, 3.60F, 7.40F, 1.70F, 0.45F)
                .texOffs(33, 15)
                .addBox(-3.95F, -0.82F, -3.60F, 0.40F, 1.70F, 7.20F)
                .texOffs(33, 15)
                .addBox(3.55F, -0.82F, -3.60F, 0.40F, 1.70F, 7.20F)
                .texOffs(50, 15)
                .addBox(-3.30F, 0.45F, -3.75F, 2.60F, 0.40F, 1.50F)
                .texOffs(50, 15)
                .addBox(0.70F, 0.45F, -3.75F, 2.60F, 0.40F, 1.50F)
                .texOffs(50, 15)
                .addBox(-3.30F, 0.45F, 2.25F, 2.60F, 0.40F, 1.50F)
                .texOffs(50, 15)
                .addBox(0.70F, 0.45F, 2.25F, 2.60F, 0.40F, 1.50F)

                // The upper chest is deliberately empty except for MOLLE and a small radio pouch.
                .texOffs(76, 15)
                .addBox(2.30F, 1.10F, -3.30F, 1.20F, 2.20F, 0.82F)
                .texOffs(81, 15)
                .addBox(2.22F, 0.85F, -3.44F, 1.36F, 0.65F, 0.25F)
                .texOffs(16, 25)
                .addBox(-3.90F, 4.05F, -3.04F, 0.22F, 4.00F, 0.62F)
                .texOffs(16, 25)
                .addBox(3.68F, 4.05F, -3.04F, 0.22F, 4.00F, 0.62F)
                .texOffs(18, 25)
                .addBox(-3.90F, 8.85F, -2.92F, 7.80F, 1.80F, 0.55F)
                .texOffs(36, 25)
                .addBox(-0.75F, 10.50F, -2.80F, 1.50F, 0.70F, 0.35F)

                // Protection loadout: a thick left tool bag and a shallower right utility pouch.
                .texOffs(0, 60)
                .addBox(-5.40F, 5.00F, -3.70F, 2.10F, 4.40F, 1.25F)
                .texOffs(9, 60)
                .addBox(-5.45F, 4.72F, -3.88F, 2.20F, 0.90F, 1.40F)
                .texOffs(19, 60)
                .addBox(-5.20F, 8.95F, -3.58F, 1.70F, 0.65F, 1.05F)
                .texOffs(27, 60)
                .addBox(3.50F, 5.40F, -3.40F, 1.55F, 3.60F, 0.88F)
                .texOffs(34, 60)
                .addBox(3.46F, 5.14F, -3.56F, 1.65F, 0.75F, 0.35F)
                .texOffs(40, 60)
                .addBox(3.78F, 6.05F, -3.62F, 0.90F, 2.00F, 0.28F);

        for (int row = 0; row < 4; row++) {
            body.texOffs(61, 15).addBox(-3.25F, 1.35F + row * 0.72F, -2.74F,
                    6.50F, 0.18F, 0.32F);
        }
        for (int column = 0; column < 4; column++) {
            float x = -3.30F + column * 1.67F;
            body.texOffs(0, 25).addBox(x, 5.75F, -3.28F, 1.55F, 3.00F, 0.82F);
            body.texOffs(6, 25).addBox(x + 0.15F, 4.72F, -3.10F, 1.25F, 1.50F, 0.45F);
            body.texOffs(11, 25).addBox(x - 0.03F, 6.25F, -3.44F, 1.61F, 0.40F, 0.20F);
        }
        return body;
    }

    private static CubeListBuilder createRightShoulder() {
        return CubeListBuilder.create()
                .texOffs(0, 40)
                .addBox(-2.75F, -2.35F, -2.30F, 3.70F, 0.55F, 4.60F)
                .texOffs(21, 40)
                .addBox(-3.00F, -1.75F, -2.10F, 0.50F, 5.00F, 4.20F)
                .texOffs(33, 40)
                .addBox(-3.10F, -1.90F, -2.70F, 4.10F, 3.40F, 0.42F)
                .texOffs(45, 40)
                .addBox(-2.90F, -1.82F, 2.28F, 3.70F, 2.60F, 0.42F);
    }

    private static CubeListBuilder createLeftShoulder() {
        return CubeListBuilder.create()
                .texOffs(0, 40)
                .addBox(-0.95F, -2.35F, -2.30F, 3.70F, 0.55F, 4.60F)
                .texOffs(21, 40)
                .addBox(2.50F, -1.75F, -2.10F, 0.50F, 5.00F, 4.20F)
                .texOffs(33, 40)
                .addBox(-1.00F, -1.90F, -2.70F, 4.10F, 3.40F, 0.42F)
                .texOffs(45, 40)
                .addBox(-0.80F, -1.82F, 2.28F, 3.70F, 2.60F, 0.42F);
    }
}

