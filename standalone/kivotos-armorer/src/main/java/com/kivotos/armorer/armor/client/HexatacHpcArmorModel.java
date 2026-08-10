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

/** Hexatac HPC minimalist carrier with mesh straps and an open skeletal cummerbund. */
public final class HexatacHpcArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_hexatac_hpc_black_multicam"), "main");

    public HexatacHpcArmorModel(ModelPart root) {
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
                // Split upper plates preserve the deep open neckline of the reference.
                .texOffs(0, 0)
                .addBox(-3.35F, 0.62F, -2.43F, 2.85F, 3.58F, 0.42F)
                .texOffs(0, 0)
                .addBox(0.50F, 0.62F, -2.43F, 2.85F, 3.58F, 0.42F)
                .texOffs(25, 0)
                .addBox(-3.60F, 3.98F, -2.50F, 7.20F, 6.92F, 0.50F)
                .texOffs(50, 0)
                .addBox(-3.35F, 0.62F, 2.01F, 2.85F, 3.58F, 0.42F)
                .texOffs(50, 0)
                .addBox(0.50F, 0.62F, 2.01F, 2.85F, 3.58F, 0.42F)
                .texOffs(75, 0)
                .addBox(-3.60F, 3.98F, 2.00F, 7.20F, 6.92F, 0.50F)

                // Wide mesh shoulder straps remain soft load-bearing parts, not armor caps.
                .texOffs(100, 0)
                .addBox(-3.20F, -0.12F, -2.72F, 1.55F, 4.32F, 0.38F)
                .texOffs(100, 0)
                .addBox(1.65F, -0.12F, -2.72F, 1.55F, 4.32F, 0.38F)
                .texOffs(0, 22)
                .addBox(-3.20F, -0.12F, 2.34F, 1.55F, 4.32F, 0.38F)
                .texOffs(0, 22)
                .addBox(1.65F, -0.12F, 2.34F, 1.55F, 4.32F, 0.38F)
                .texOffs(25, 22)
                .addBox(-3.17F, -0.38F, -2.38F, 1.49F, 0.56F, 4.76F)
                .texOffs(25, 22)
                .addBox(1.68F, -0.38F, -2.38F, 1.49F, 0.56F, 4.76F)

                // Four narrow rails and open vertical links make the skeletal waist structure.
                .texOffs(50, 22)
                .addBox(-3.92F, 5.00F, -2.05F, 0.38F, 1.00F, 4.10F)
                .texOffs(50, 22)
                .addBox(3.54F, 5.00F, -2.05F, 0.38F, 1.00F, 4.10F)
                .texOffs(50, 22)
                .addBox(-3.92F, 8.30F, -2.05F, 0.38F, 1.00F, 4.10F)
                .texOffs(50, 22)
                .addBox(3.54F, 8.30F, -2.05F, 0.38F, 1.00F, 4.10F)

                .texOffs(75, 22)
                .addBox(-3.96F, 5.72F, -1.62F, 0.22F, 2.72F, 0.30F)
                .texOffs(75, 22)
                .addBox(-3.96F, 5.72F, -0.15F, 0.22F, 2.72F, 0.30F)
                .texOffs(75, 22)
                .addBox(-3.96F, 5.72F, 1.32F, 0.22F, 2.72F, 0.30F)
                .texOffs(75, 22)
                .addBox(3.74F, 5.72F, -1.62F, 0.22F, 2.72F, 0.30F)
                .texOffs(75, 22)
                .addBox(3.74F, 5.72F, -0.15F, 0.22F, 2.72F, 0.30F)
                .texOffs(75, 22)
                .addBox(3.74F, 5.72F, 1.32F, 0.22F, 2.72F, 0.30F)

                // The two shallow front fields are structural panels, not invented cargo pouches.
                .texOffs(100, 22)
                .addBox(-2.80F, 1.28F, -2.80F, 5.60F, 2.05F, 0.40F)
                .texOffs(0, 44)
                .addBox(-3.15F, 6.82F, -2.82F, 6.30F, 2.48F, 0.35F)

                .texOffs(25, 44)
                .addBox(-2.80F, 4.38F, -2.72F, 5.60F, 0.18F, 0.24F)
                .texOffs(25, 44)
                .addBox(-2.80F, 5.10F, -2.72F, 5.60F, 0.18F, 0.24F)
                .texOffs(25, 44)
                .addBox(-2.80F, 5.82F, -2.72F, 5.60F, 0.18F, 0.24F)
                .texOffs(25, 44)
                .addBox(-2.80F, 6.54F, -2.72F, 5.60F, 0.18F, 0.24F)

                .texOffs(50, 44)
                .addBox(-3.56F, 10.46F, -2.79F, 7.12F, 0.52F, 0.35F)
                .texOffs(50, 44)
                .addBox(-3.56F, 10.46F, 2.44F, 7.12F, 0.52F, 0.35F)

                .texOffs(75, 44)
                .addBox(-3.98F, 4.96F, -0.48F, 0.30F, 0.70F, 0.96F)
                .texOffs(75, 44)
                .addBox(3.68F, 4.96F, -0.48F, 0.30F, 0.70F, 0.96F)
                .texOffs(75, 44)
                .addBox(-3.98F, 8.58F, -0.48F, 0.30F, 0.66F, 0.96F)
                .texOffs(75, 44)
                .addBox(3.68F, 8.58F, -0.48F, 0.30F, 0.66F, 0.96F);
        return body;
    }
}

