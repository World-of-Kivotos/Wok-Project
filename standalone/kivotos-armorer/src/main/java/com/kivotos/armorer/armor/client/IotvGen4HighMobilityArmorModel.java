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

/** IOTV Gen4 high-mobility configuration: empty carrier, high collar, long groin and paired hip plates. */
public final class IotvGen4HighMobilityArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_iotv_gen4_high_mobility"), "main");

    public IotvGen4HighMobilityArmorModel(ModelPart root) {
        super(root);
    }

    private static CubeListBuilder body() {
        return CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.60F, 0.55F, -2.50F, 7.20F, 3.10F, 0.48F)
                .texOffs(17, 0).addBox(-3.90F, 3.50F, -2.58F, 7.80F, 4.00F, 0.56F)
                .texOffs(35, 0).addBox(-4.00F, 7.45F, -2.66F, 8.00F, 3.50F, 0.58F)
                .texOffs(54, 0).addBox(-3.60F, 0.55F, 2.02F, 7.20F, 3.10F, 0.48F)
                .texOffs(71, 0).addBox(-3.90F, 3.50F, 2.02F, 7.80F, 4.00F, 0.56F)
                .texOffs(89, 0).addBox(-4.00F, 7.45F, 2.08F, 8.00F, 3.50F, 0.58F)
                .texOffs(108, 0).addBox(-3.98F, 2.40F, -2.00F, 0.42F, 8.30F, 4.00F)
                .texOffs(118, 0).addBox(3.56F, 2.40F, -2.00F, 0.42F, 8.30F, 4.00F)
                .texOffs(0, 14).addBox(-3.45F, -0.10F, -3.08F, 3.20F, 1.20F, 0.58F)
                .texOffs(9, 14).addBox(0.25F, -0.10F, -3.08F, 3.20F, 1.20F, 0.58F)
                .texOffs(18, 14).addBox(-3.45F, -0.10F, 2.50F, 3.20F, 1.20F, 0.58F)
                .texOffs(27, 14).addBox(0.25F, -0.10F, 2.50F, 3.20F, 1.20F, 0.58F)

                // Low rolled collar: open at the throat, softly raised at the rear and sides.
                .texOffs(36, 14).addBox(-3.70F, -0.55F, -3.55F, 3.05F, 1.35F, 0.50F)
                .texOffs(45, 14).addBox(0.65F, -0.55F, -3.55F, 3.05F, 1.35F, 0.50F)
                .texOffs(54, 14).addBox(-3.70F, -0.55F, 3.05F, 7.40F, 1.35F, 0.50F)
                .texOffs(71, 14).addBox(-3.90F, -0.52F, -3.05F, 0.38F, 1.30F, 6.10F)
                .texOffs(85, 14).addBox(3.52F, -0.52F, -3.05F, 0.38F, 1.30F, 6.10F)
                .texOffs(99, 14).addBox(-3.65F, -0.85F, -3.68F, 2.90F, 0.32F, 0.58F)
                .texOffs(107, 14).addBox(0.75F, -0.85F, -3.68F, 2.90F, 0.32F, 0.58F)
                .texOffs(0, 23).addBox(-3.65F, -0.85F, 3.10F, 7.30F, 0.32F, 0.58F)
                .texOffs(17, 23).addBox(-3.95F, -0.82F, -3.05F, 0.45F, 0.30F, 6.10F)
                .texOffs(32, 23).addBox(3.50F, -0.82F, -3.05F, 0.45F, 0.30F, 6.10F)

                // Five empty MOLLE rows and chest hardware; no fictional pouches are present.
                .texOffs(47, 23).addBox(-3.55F, 2.05F, -2.68F, 7.10F, 0.24F, 0.18F)
                .texOffs(63, 23).addBox(-3.55F, 3.55F, -2.76F, 7.10F, 0.24F, 0.18F)
                .texOffs(79, 23).addBox(-3.55F, 5.05F, -2.76F, 7.10F, 0.24F, 0.18F)
                .texOffs(95, 23).addBox(-3.55F, 6.55F, -2.76F, 7.10F, 0.24F, 0.18F)
                .texOffs(111, 23).addBox(-3.55F, 8.05F, -2.76F, 7.10F, 0.24F, 0.18F)
                .texOffs(0, 31).addBox(-3.90F, 9.65F, -2.94F, 7.80F, 1.00F, 0.28F)
                .texOffs(18, 31).addBox(-3.90F, 9.65F, 2.66F, 7.80F, 1.00F, 0.28F)
                .texOffs(36, 31).addBox(-3.94F, 9.65F, -2.20F, 0.36F, 1.00F, 4.40F)
                .texOffs(47, 31).addBox(3.58F, 9.65F, -2.20F, 0.36F, 1.00F, 4.40F)
                .texOffs(58, 31).addBox(-2.85F, 0.75F, -2.78F, 1.00F, 1.50F, 0.24F)
                .texOffs(62, 31).addBox(1.85F, 0.75F, -2.78F, 1.00F, 1.50F, 0.24F)
                .texOffs(66, 31).addBox(-2.50F, 1.15F, -3.04F, 0.30F, 0.60F, 0.20F)
                .texOffs(68, 31).addBox(2.20F, 1.15F, -3.04F, 0.30F, 0.60F, 0.20F)

                // Five overlapping stages taper to a pointed water-drop guard; hip plates sit behind it.
                .texOffs(70, 31).addBox(-2.70F, 10.55F, -3.34F, 5.40F, 1.85F, 0.42F)
                .texOffs(83, 31).addBox(-2.40F, 12.25F, -3.38F, 4.80F, 1.75F, 0.40F)
                .texOffs(95, 31).addBox(-2.05F, 13.85F, -3.42F, 4.10F, 1.65F, 0.38F)
                .texOffs(105, 31).addBox(-1.60F, 15.35F, -3.46F, 3.20F, 1.40F, 0.36F)
                .texOffs(114, 31).addBox(-0.90F, 16.60F, -3.50F, 1.80F, 0.90F, 0.34F)
                .texOffs(120, 31).addBox(-4.00F, 11.00F, -2.86F, 2.45F, 3.80F, 0.30F)
                .texOffs(0, 38).addBox(1.55F, 11.00F, -2.86F, 2.45F, 3.80F, 0.30F)
                .texOffs(7, 38).addBox(-4.00F, 11.00F, -3.02F, 2.45F, 0.32F, 0.16F)
                .texOffs(14, 38).addBox(-2.30F, 11.10F, -3.51F, 4.60F, 0.20F, 0.17F)
                .texOffs(25, 38).addBox(-2.10F, 11.75F, -3.51F, 4.20F, 0.20F, 0.17F)
                .texOffs(35, 38).addBox(-1.90F, 12.45F, -3.55F, 3.80F, 0.20F, 0.17F)
                .texOffs(44, 38).addBox(-3.78F, 11.45F, -3.01F, 0.18F, 2.80F, 0.15F)
                .texOffs(46, 38).addBox(3.58F, 11.45F, -3.01F, 0.18F, 2.80F, 0.15F);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("body", body(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }
}

