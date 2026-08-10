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

/** TacTec carrier with a dense magazine placard, asymmetric side bags and a soft lower pouch. */
public final class TactecArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_tactec_ranger_green"), "main");

    private static final CubeDeformation CARRIER_DEFORMATION = new CubeDeformation(0.10F);

    public TactecArmorModel(ModelPart root) {
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
                .addBox(-3.55F, 0.65F, -2.66F, 7.10F, 8.75F, 0.58F)
                .texOffs(42, 0)
                .addBox(-3.50F, 0.70F, 2.08F, 7.00F, 8.80F, 0.56F)
                .texOffs(58, 0)
                .addBox(-3.98F, 4.10F, -2.05F, 0.40F, 6.65F, 4.10F)
                .texOffs(58, 0)
                .addBox(3.58F, 4.10F, -2.05F, 0.40F, 6.65F, 4.10F)
                .texOffs(68, 0)
                .addBox(-3.65F, -0.30F, -2.83F, 1.45F, 4.05F, 0.55F)
                .texOffs(68, 0)
                .addBox(2.20F, -0.30F, -2.83F, 1.45F, 4.05F, 0.55F)
                .texOffs(73, 0)
                .addBox(-3.65F, -0.30F, 2.28F, 1.45F, 4.05F, 0.55F)
                .texOffs(73, 0)
                .addBox(2.20F, -0.30F, 2.28F, 1.45F, 4.05F, 0.55F)
                .texOffs(78, 0)
                .addBox(-3.57F, -0.48F, -2.25F, 1.29F, 0.55F, 4.50F)
                .texOffs(78, 0)
                .addBox(2.28F, -0.48F, -2.25F, 1.29F, 0.55F, 4.50F)
                .texOffs(91, 0)
                .addBox(-2.90F, 1.55F, -2.94F, 5.80F, 1.10F, 0.22F)

                // A five-piece stepped outline makes the large left zipper bag broad, deep and rounded.
                .texOffs(16, 20)
                .addBox(-6.15F, 5.00F, -4.02F, 2.85F, 5.45F, 1.75F)
                .texOffs(26, 20)
                .addBox(-6.30F, 4.72F, -4.15F, 3.10F, 1.12F, 1.90F)
                .texOffs(37, 20)
                .addBox(-5.93F, 5.80F, -4.28F, 2.40F, 3.85F, 0.30F)
                .texOffs(43, 20)
                .addBox(-6.10F, 5.35F, -4.42F, 0.22F, 4.45F, 0.18F)
                .texOffs(0, 40)
                .addBox(-5.98F, 9.95F, -3.94F, 2.50F, 0.72F, 1.55F)

                // Two separate right-waist pockets replace the old single rectangular bag.
                .texOffs(45, 20)
                .addBox(3.30F, 5.45F, -3.75F, 1.02F, 4.35F, 1.45F)
                .texOffs(45, 20)
                .addBox(4.48F, 5.45F, -3.75F, 1.02F, 4.35F, 1.45F)
                .texOffs(51, 20)
                .addBox(3.24F, 5.15F, -3.90F, 1.14F, 1.00F, 1.60F)
                .texOffs(51, 20)
                .addBox(4.42F, 5.15F, -3.90F, 1.14F, 1.00F, 1.60F)

                // The bottom element is a soft dump pouch rather than a rigid groin plate.
                .texOffs(60, 20)
                .addBox(-1.90F, 9.30F, -3.50F, 5.00F, 4.35F, 0.90F)
                .texOffs(73, 20)
                .addBox(-2.00F, 9.05F, -3.64F, 5.20F, 1.05F, 0.32F)
                .texOffs(85, 20)
                .addBox(-0.55F, 10.20F, -3.70F, 0.22F, 3.15F, 0.15F)
                .texOffs(85, 20)
                .addBox(1.53F, 10.20F, -3.70F, 0.22F, 3.15F, 0.15F)
                .texOffs(119, 0)
                .addBox(-3.25F, 2.75F, -3.12F, 0.55F, 0.65F, 0.22F)
                .texOffs(119, 0)
                .addBox(2.70F, 2.75F, -3.12F, 0.55F, 0.65F, 0.22F);

        for (int row = 0; row < 4; row++) {
            body.texOffs(105, 0).addBox(-3.15F, 3.05F + row * 0.72F, -2.98F,
                    6.30F, 0.18F, 0.20F);
        }
        // Five slim independent columns reproduce the dense magazine/small-pouch bank.
        for (int column = 0; column < 5; column++) {
            float x = -3.20F + column * 1.28F;
            body.texOffs(0, 20).addBox(x, 5.25F, -3.32F, 1.05F, 3.50F, 0.72F);
            body.texOffs(6, 20).addBox(x + 0.10F, 4.15F, -3.14F, 0.86F, 1.50F, 0.42F);
            body.texOffs(11, 20).addBox(x - 0.03F, 6.20F, -3.46F, 1.11F, 0.42F, 0.20F);
        }
        return body;
    }
}

