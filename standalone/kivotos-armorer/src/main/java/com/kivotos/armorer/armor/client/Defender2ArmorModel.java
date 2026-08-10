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

/** Smooth Defender-2 shell shared by its plain and spot-camouflage colorways. */
public final class Defender2ArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_defender_2"), "main");

    public Defender2ArmorModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("body", createBody(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        return CubeListBuilder.create()
                // Split upper pads leave a real central V opening instead of a square tube front.
                .texOffs(0, 0)
                .addBox(-3.25F, 0.60F, -2.55F, 2.20F, 3.35F, 0.52F)
                .texOffs(0, 0)
                .addBox(1.05F, 0.60F, -2.55F, 2.20F, 3.35F, 0.52F)
                .texOffs(100, 66)
                .addBox(-1.05F, 1.20F, -2.75F, 0.75F, 1.00F, 0.71F)
                .texOffs(100, 66)
                .addBox(0.30F, 1.20F, -2.75F, 0.75F, 1.00F, 0.71F)
                .texOffs(50, 66)
                .addBox(-1.65F, 2.05F, -2.70F, 3.30F, 1.90F, 0.66F)

                // Two lower courses retain the reference's smooth, pouch-free shell.
                .texOffs(25, 0)
                .addBox(-3.72F, 3.70F, -2.62F, 7.44F, 4.15F, 0.58F)
                .texOffs(50, 0)
                .addBox(-3.84F, 7.62F, -2.68F, 7.68F, 3.55F, 0.62F)
                .texOffs(75, 0)
                .addBox(-3.25F, 0.45F, 2.03F, 6.50F, 3.45F, 0.52F)
                .texOffs(100, 0)
                .addBox(-3.72F, 3.70F, 2.04F, 7.44F, 4.15F, 0.58F)
                .texOffs(0, 22)
                .addBox(-3.84F, 7.62F, 2.06F, 7.68F, 3.55F, 0.62F)
                .texOffs(25, 22)
                .addBox(-3.95F, 3.48F, -2.05F, 0.43F, 7.72F, 4.10F)
                .texOffs(25, 22)
                .addBox(3.52F, 3.48F, -2.05F, 0.43F, 7.72F, 4.10F)

                // Narrow padded yokes retain the soft neckline without inventing shoulder armor.
                .texOffs(50, 22)
                .addBox(-3.12F, -0.05F, -2.86F, 1.30F, 3.90F, 0.34F)
                .texOffs(50, 22)
                .addBox(1.82F, -0.05F, -2.86F, 1.30F, 3.90F, 0.34F)
                .texOffs(75, 22)
                .addBox(-3.12F, -0.05F, 2.52F, 1.30F, 3.90F, 0.34F)
                .texOffs(75, 22)
                .addBox(1.82F, -0.05F, 2.52F, 1.30F, 3.90F, 0.34F)
                .texOffs(100, 22)
                .addBox(-3.09F, -0.30F, -2.50F, 1.24F, 0.35F, 5.00F)
                .texOffs(100, 22)
                .addBox(1.85F, -0.30F, -2.50F, 1.24F, 0.35F, 5.00F)

                // Defender-2's defining broad wrap belt remains completely free of pouches.
                .texOffs(0, 44)
                .addBox(-3.90F, 9.86F, -2.98F, 7.80F, 1.28F, 0.38F)
                .texOffs(25, 44)
                .addBox(-3.90F, 9.86F, 2.60F, 7.80F, 1.28F, 0.38F)
                .texOffs(50, 44)
                .addBox(-3.98F, 9.82F, -2.25F, 0.42F, 1.30F, 4.50F)
                .texOffs(50, 44)
                .addBox(3.56F, 9.82F, -2.25F, 0.42F, 1.30F, 4.50F)
                // Muted side bands frame a dark central hook-and-loop panel; there are no white buckles.
                .texOffs(75, 44)
                .addBox(-3.42F, 8.45F, -3.05F, 2.65F, 1.10F, 0.40F)
                .texOffs(75, 44)
                .addBox(0.77F, 8.45F, -3.05F, 2.65F, 1.10F, 0.40F)
                .texOffs(75, 66)
                .addBox(-0.70F, 8.40F, -3.12F, 1.40F, 1.20F, 0.42F)

                // Three gently narrowing plates keep the droplet long while ending broad and blunt.
                .texOffs(100, 44)
                .addBox(-3.10F, 10.85F, -3.18F, 6.20F, 1.90F, 0.52F)
                .texOffs(0, 66)
                .addBox(-2.80F, 12.60F, -3.13F, 5.60F, 3.05F, 0.48F)
                .texOffs(25, 66)
                .addBox(-2.25F, 15.48F, -3.04F, 4.50F, 1.90F, 0.40F);
    }
}

