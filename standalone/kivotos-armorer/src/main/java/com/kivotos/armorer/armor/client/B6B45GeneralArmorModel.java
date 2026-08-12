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

/** 6B45 general-purpose armored rig with a medium collar, medical pack and paired flap pouches. */
public final class B6B45GeneralArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_6b45_general"), "main");

    public B6B45GeneralArmorModel(ModelPart root) {
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
        root.addOrReplaceChild("right_arm", CubeListBuilder.create()
                // The compact cap hugs the torso edge and drops only a short outer lip.
                .texOffs(50, 88)
                .addBox(-1.25F, -2.15F, -2.15F, 2.25F, 0.55F, 4.30F)
                .texOffs(75, 88)
                .addBox(-1.35F, -1.65F, -2.00F, 0.45F, 1.55F, 4.00F),
                PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create()
                .texOffs(50, 88)
                .addBox(-1.00F, -2.15F, -2.15F, 2.25F, 0.55F, 4.30F)
                .texOffs(75, 88)
                .addBox(0.90F, -1.65F, -2.00F, 0.45F, 1.55F, 4.00F),
                PartPose.offset(5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        return CubeListBuilder.create()
                // Thick segmented torso and continuous side wraps.
                .texOffs(0, 0)
                .addBox(-3.40F, 0.55F, -2.48F, 6.80F, 3.35F, 0.52F)
                .texOffs(25, 0)
                .addBox(-3.85F, 3.70F, -2.55F, 7.70F, 7.90F, 0.58F)
                .texOffs(50, 0)
                .addBox(-3.40F, 0.55F, 1.96F, 6.80F, 3.35F, 0.52F)
                .texOffs(75, 0)
                .addBox(-3.85F, 3.70F, 1.97F, 7.70F, 7.90F, 0.58F)
                .texOffs(100, 0)
                .addBox(-3.93F, 3.35F, -2.05F, 0.45F, 8.20F, 4.10F)
                .texOffs(100, 0)
                .addBox(3.48F, 3.35F, -2.05F, 0.45F, 8.20F, 4.10F)

                // Five thick pieces reproduce the padded ring without rising into the wearer's head.
                .texOffs(0, 22)
                .addBox(-4.30F, -1.55F, -4.53F, 3.75F, 1.55F, 0.48F)
                .texOffs(0, 22)
                .addBox(0.55F, -1.55F, -4.53F, 3.75F, 1.55F, 0.48F)
                .texOffs(25, 22)
                .addBox(-4.30F, -1.52F, 4.05F, 8.60F, 1.52F, 0.48F)
                .texOffs(50, 22)
                .addBox(-4.53F, -1.62F, -4.13F, 0.48F, 1.62F, 8.26F)
                .texOffs(50, 22)
                .addBox(4.05F, -1.62F, -4.13F, 0.48F, 1.62F, 8.26F)

                // Four broad roots join the lowered collar to the torso without entering the arm volume.
                .texOffs(100, 88)
                .addBox(-3.35F, -0.08F, -4.13F, 2.70F, 0.78F, 2.22F)
                .texOffs(100, 88)
                .addBox(0.65F, -0.08F, -4.13F, 2.70F, 0.78F, 2.22F)
                .texOffs(100, 88)
                .addBox(-3.35F, -0.08F, 1.95F, 2.70F, 0.78F, 2.18F)
                .texOffs(100, 88)
                .addBox(0.65F, -0.08F, 1.95F, 2.70F, 0.78F, 2.18F)

                // A large left-centre medical pack dominates the general-purpose loadout.
                .texOffs(75, 22)
                .addBox(-3.45F, 5.30F, -3.72F, 3.50F, 5.15F, 1.28F)
                .texOffs(100, 22)
                .addBox(-3.40F, 5.05F, -3.92F, 3.40F, 1.32F, 0.35F)
                .texOffs(0, 44)
                .addBox(-2.28F, 8.55F, -3.98F, 1.20F, 1.20F, 0.30F)

                // A compact stepped outer pouch sits ahead of the left arm and rounds toward its base.
                .texOffs(0, 100)
                .addBox(-4.86F, 5.85F, -3.58F, 1.42F, 3.10F, 1.02F)
                .texOffs(7, 100)
                .addBox(-4.82F, 5.55F, -3.76F, 1.34F, 0.82F, 0.32F)
                .texOffs(13, 100)
                .addBox(-4.70F, 8.72F, -3.48F, 1.16F, 0.62F, 0.86F)
                .texOffs(19, 100)
                .addBox(-4.22F, 6.45F, -3.74F, 0.22F, 1.90F, 0.20F)

                // Two separate right pouches retain their own pointed flap and pull strap.
                .texOffs(25, 44)
                .addBox(0.40F, 5.70F, -3.52F, 1.50F, 4.55F, 1.00F)
                .texOffs(25, 44)
                .addBox(2.05F, 5.70F, -3.52F, 1.50F, 4.55F, 1.00F)
                .texOffs(50, 44)
                .addBox(0.43F, 5.43F, -3.74F, 1.44F, 1.30F, 0.35F)
                .texOffs(50, 44)
                .addBox(2.08F, 5.43F, -3.74F, 1.44F, 1.30F, 0.35F)
                .texOffs(75, 44)
                .addBox(1.02F, 6.18F, -3.96F, 0.22F, 2.30F, 0.30F)
                .texOffs(75, 44)
                .addBox(2.67F, 6.18F, -3.96F, 0.22F, 2.30F, 0.30F)

                // The side radio remains forward of the arm volume, with an exposed antenna.
                .texOffs(100, 44)
                .addBox(3.25F, 1.90F, -3.45F, 1.25F, 3.70F, 1.00F)
                .texOffs(0, 66)
                .addBox(3.29F, 1.65F, -3.65F, 1.17F, 0.82F, 0.32F)
                .texOffs(25, 66)
                .addBox(4.03F, -1.20F, -3.30F, 0.22F, 3.20F, 0.22F)

                .texOffs(50, 66)
                .addBox(-2.80F, 3.02F, -2.82F, 5.60F, 0.20F, 0.32F)
                .texOffs(50, 66)
                .addBox(-2.80F, 3.86F, -2.82F, 5.60F, 0.20F, 0.32F)
                .texOffs(75, 66)
                .addBox(-2.20F, 2.18F, -3.05F, 4.40F, 0.52F, 0.64F)
                .texOffs(100, 66)
                .addBox(-0.32F, 2.12F, -3.27F, 0.64F, 0.64F, 0.28F)

                .texOffs(0, 88)
                .addBox(-3.80F, 10.35F, -2.92F, 7.60F, 1.20F, 0.42F)
                .texOffs(0, 88)
                .addBox(-3.80F, 10.35F, 2.50F, 7.60F, 1.20F, 0.42F)
                .texOffs(25, 88)
                .addBox(-3.76F, 9.92F, -2.84F, 7.52F, 0.76F, 0.34F)
                .texOffs(25, 88)
                .addBox(-3.76F, 9.92F, 2.50F, 7.52F, 0.76F, 0.34F);
    }

}

