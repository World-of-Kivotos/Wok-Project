package com.miningdim.job.engineer.armor.client;

import com.miningdim.core.MiningConstants;
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

/** 6B45 medical carrier with its high collar and asymmetric field-medical loadout. */
public final class B6B45MedicArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_6b45_medic"), "main");

    public B6B45MedicArmorModel(ModelPart root) {
        super(root);
    }

    private static CubeListBuilder body() {
        return CubeListBuilder.create()
                // Three courses form the thick front and rear ballistic shell.
                .texOffs(0, 0).addBox(-3.60F, 0.60F, -2.50F, 7.20F, 3.00F, 0.48F)
                .texOffs(17, 0).addBox(-3.90F, 3.55F, -2.58F, 7.80F, 4.15F, 0.56F)
                .texOffs(35, 0).addBox(-4.00F, 7.65F, -2.66F, 8.00F, 3.50F, 0.58F)
                .texOffs(54, 0).addBox(-3.60F, 0.60F, 2.02F, 7.20F, 3.00F, 0.48F)
                .texOffs(71, 0).addBox(-3.90F, 3.55F, 2.02F, 7.80F, 4.15F, 0.56F)
                .texOffs(89, 0).addBox(-4.00F, 7.65F, 2.08F, 8.00F, 3.50F, 0.58F)
                .texOffs(108, 0).addBox(-3.98F, 2.60F, -2.00F, 0.42F, 8.50F, 4.00F)
                .texOffs(118, 0).addBox(3.56F, 2.60F, -2.00F, 0.42F, 8.50F, 4.00F)

                // A five-piece, two-block-thick collar reproduces the tall padded ring.
                .texOffs(0, 14).addBox(-4.10F, -1.25F, -4.30F, 3.80F, 2.15F, 0.58F)
                .texOffs(10, 14).addBox(0.30F, -1.25F, -4.30F, 3.80F, 2.15F, 0.58F)
                .texOffs(20, 14).addBox(-4.10F, -1.25F, 3.72F, 8.20F, 2.15F, 0.58F)
                .texOffs(39, 14).addBox(-3.98F, -1.20F, -3.72F, 0.50F, 2.10F, 7.44F)
                .texOffs(56, 14).addBox(3.48F, -1.20F, -3.72F, 0.50F, 2.10F, 7.44F)
                .texOffs(73, 14).addBox(-3.45F, -0.15F, -2.82F, 3.20F, 1.20F, 0.32F)
                .texOffs(82, 14).addBox(0.25F, -0.15F, -2.82F, 3.20F, 1.20F, 0.32F)
                .texOffs(91, 14).addBox(-3.45F, -0.15F, 2.50F, 3.20F, 1.20F, 0.32F)
                .texOffs(100, 14).addBox(0.25F, -0.15F, 2.50F, 3.20F, 1.20F, 0.32F)

                .texOffs(109, 14).addBox(-3.90F, 9.85F, -2.88F, 7.80F, 1.10F, 0.30F)
                .texOffs(0, 25).addBox(-3.90F, 9.85F, 2.58F, 7.80F, 1.10F, 0.30F)
                .texOffs(18, 25).addBox(-3.50F, 2.15F, -2.66F, 7.00F, 0.22F, 0.18F)
                .texOffs(34, 25).addBox(-3.50F, 3.00F, -2.76F, 7.00F, 0.22F, 0.18F)
                .texOffs(50, 25).addBox(-3.50F, 9.15F, -2.76F, 7.00F, 0.22F, 0.18F)

                // Central high medical bag with a physical zipper and raised red cross.
                .texOffs(66, 25).addBox(-0.95F, 2.60F, -3.73F, 2.90F, 5.10F, 1.15F)
                .texOffs(76, 25).addBox(-1.00F, 2.48F, -3.92F, 3.00F, 0.72F, 1.25F)
                .texOffs(86, 25).addBox(1.55F, 3.35F, -3.97F, 0.18F, 3.50F, 0.18F)
                .texOffs(88, 25).addBox(0.05F, 6.08F, -4.05F, 1.55F, 0.35F, 0.16F)
                .texOffs(93, 25).addBox(0.65F, 5.48F, -4.09F, 0.35F, 1.55F, 0.16F)

                // Two long left pouches remain distinct, lidded and visibly strapped.
                .texOffs(96, 25).addBox(-3.78F, 4.10F, -3.50F, 1.20F, 4.50F, 0.92F)
                .texOffs(102, 25).addBox(-3.81F, 3.95F, -3.67F, 1.26F, 0.70F, 1.05F)
                .texOffs(108, 25).addBox(-3.29F, 4.75F, -3.72F, 0.22F, 3.00F, 0.16F)
                .texOffs(110, 25).addBox(-2.48F, 4.10F, -3.50F, 1.20F, 4.50F, 0.92F)
                .texOffs(116, 25).addBox(-2.51F, 3.95F, -3.67F, 1.26F, 0.70F, 1.05F)
                .texOffs(122, 25).addBox(-1.99F, 4.75F, -3.72F, 0.22F, 3.00F, 0.16F)

                // The lower pair are separate small utility pouches, not a flat apron.
                .texOffs(0, 33).addBox(-1.10F, 8.05F, -3.46F, 1.22F, 2.60F, 0.88F)
                .texOffs(6, 33).addBox(-1.13F, 7.93F, -3.61F, 1.28F, 0.62F, 0.98F)
                .texOffs(12, 33).addBox(-0.58F, 8.60F, -3.64F, 0.18F, 1.50F, 0.15F)
                .texOffs(14, 33).addBox(0.27F, 8.05F, -3.46F, 1.22F, 2.60F, 0.88F)
                .texOffs(20, 33).addBox(0.24F, 7.93F, -3.61F, 1.28F, 0.62F, 0.98F)
                .texOffs(26, 33).addBox(0.79F, 8.60F, -3.64F, 0.18F, 1.50F, 0.15F)

                // A narrow radio/utility module balances the right side without becoming a large bag.
                .texOffs(28, 33).addBox(2.35F, 3.20F, -3.34F, 1.25F, 5.70F, 0.78F)
                .texOffs(34, 33).addBox(2.28F, 3.08F, -3.52F, 1.29F, 0.65F, 0.88F)
                .texOffs(40, 33).addBox(3.14F, 3.85F, -3.55F, 0.18F, 4.20F, 0.15F)
                .texOffs(42, 33).addBox(3.15F, 0.65F, -3.25F, 0.16F, 2.60F, 0.16F);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("body", body(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create()
                        .texOffs(44, 33).addBox(-1.25F, -2.15F, -2.10F, 2.30F, 0.55F, 4.20F)
                        .texOffs(58, 33).addBox(-1.35F, -1.65F, -2.00F, 0.45F, 1.55F, 4.00F),
                PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create()
                        .texOffs(68, 33).addBox(-1.05F, -2.15F, -2.10F, 2.30F, 0.55F, 4.20F)
                        .texOffs(82, 33).addBox(0.90F, -1.65F, -2.00F, 0.45F, 1.55F, 4.00F),
                PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }
}
