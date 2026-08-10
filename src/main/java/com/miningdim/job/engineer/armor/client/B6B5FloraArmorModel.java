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

/** 6B5-15 Zh-86 Uley flora vest with a high ring collar, four lidded pouches and a long skirt. */
public final class B6B5FloraArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_6b5_15_flora"), "main");

    public B6B5FloraArmorModel(ModelPart root) {
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
        return CubeListBuilder.create()
                // Soft vest shell, widened below the chest.
                .texOffs(0, 0)
                .addBox(-3.30F, 0.55F, -2.32F, 6.60F, 3.00F, 0.40F)
                .texOffs(15, 0)
                .addBox(-3.30F, 0.55F, 1.92F, 6.60F, 3.00F, 0.40F)
                .texOffs(30, 0)
                .addBox(-3.75F, 3.52F, -2.38F, 7.50F, 4.03F, 0.44F)
                .texOffs(47, 0)
                .addBox(-3.75F, 3.52F, 1.94F, 7.50F, 4.03F, 0.44F)
                .texOffs(64, 0)
                .addBox(-3.95F, 7.52F, -2.44F, 7.90F, 4.23F, 0.47F)
                .texOffs(82, 0)
                .addBox(-3.95F, 7.52F, 1.97F, 7.90F, 4.23F, 0.47F)
                .texOffs(100, 0)
                .addBox(-3.98F, 3.58F, -1.94F, 0.46F, 8.14F, 3.88F)
                .texOffs(110, 0)
                .addBox(3.52F, 3.58F, -1.94F, 0.46F, 8.14F, 3.88F)

                // The tall five-piece ring collar is raised clear of the neutral-pose arms.
                .texOffs(0, 14)
                .addBox(-4.40F, -1.95F, -4.52F, 3.80F, 1.90F, 0.44F)
                .texOffs(10, 14)
                .addBox(0.60F, -1.95F, -4.52F, 3.80F, 1.90F, 0.44F)
                .texOffs(20, 14)
                .addBox(-4.40F, -1.88F, 4.08F, 8.80F, 1.78F, 0.44F)
                .texOffs(40, 14)
                .addBox(-4.52F, -1.80F, -4.13F, 0.44F, 1.63F, 8.26F)
                .texOffs(59, 14)
                .addBox(4.08F, -1.80F, -4.13F, 0.44F, 1.63F, 8.26F)

                // Four thick yokes tie the collar into front and rear plates without arm-mounted caps.
                .texOffs(78, 14)
                .addBox(-3.35F, -0.20F, -4.13F, 2.80F, 0.85F, 2.21F)
                .texOffs(90, 14)
                .addBox(0.55F, -0.20F, -4.13F, 2.80F, 0.85F, 2.21F)
                .texOffs(102, 14)
                .addBox(-3.35F, -0.20F, 1.92F, 2.80F, 0.85F, 2.21F)
                .texOffs(114, 14)
                .addBox(0.55F, -0.20F, 1.92F, 2.80F, 0.85F, 2.21F)

                // Long shoulder-front straps terminate behind the pouch row.
                .texOffs(0, 25)
                .addBox(-3.00F, 0.65F, -2.62F, 0.72F, 4.65F, 0.32F)
                .texOffs(4, 25)
                .addBox(2.28F, 0.65F, -2.62F, 0.72F, 4.65F, 0.32F)

                // Four separate magazine/utility pouch bodies span the front and side waist.
                .texOffs(8, 25)
                .addBox(-3.88F, 4.90F, -3.20F, 1.65F, 4.65F, 0.92F)
                .texOffs(15, 25)
                .addBox(-2.00F, 4.90F, -3.20F, 1.65F, 4.65F, 0.92F)
                .texOffs(22, 25)
                .addBox(0.35F, 4.90F, -3.20F, 1.65F, 4.65F, 0.92F)
                .texOffs(29, 25)
                .addBox(2.23F, 4.90F, -3.20F, 1.65F, 4.65F, 0.92F)

                // A broad long apron plus narrowed tip reproduce the rounded Zh-86 lower edge.
                .texOffs(36, 25)
                .addBox(-3.25F, 9.35F, -2.70F, 6.50F, 5.10F, 0.40F)
                .texOffs(51, 25)
                .addBox(-2.00F, 14.35F, -2.66F, 4.00F, 1.30F, 0.34F)

                // Independent overhanging lids keep all four pouches from reading as flat blocks.
                .texOffs(61, 25)
                .addBox(-3.84F, 4.75F, -3.35F, 1.57F, 0.85F, 1.06F)
                .texOffs(68, 25)
                .addBox(-1.96F, 4.75F, -3.35F, 1.57F, 0.85F, 1.06F)
                .texOffs(75, 25)
                .addBox(0.39F, 4.75F, -3.35F, 1.57F, 0.85F, 1.06F)
                .texOffs(82, 25)
                .addBox(2.27F, 4.75F, -3.35F, 1.57F, 0.85F, 1.06F)

                // Side skirts end before the leg pivots while preserving the continuous lower surround.
                .texOffs(89, 25)
                .addBox(-3.90F, 8.80F, -2.20F, 0.50F, 3.05F, 4.40F)
                .texOffs(100, 25)
                .addBox(3.40F, 8.80F, -2.20F, 0.50F, 3.05F, 4.40F)

                // Raised chest, waist and centre seams add a visible padded-panel hierarchy.
                .texOffs(111, 25)
                .addBox(-2.94F, 3.20F, -2.58F, 5.88F, 0.24F, 0.26F)
                .texOffs(0, 34)
                .addBox(-3.60F, 7.35F, -2.64F, 7.20F, 0.28F, 0.28F)
                .texOffs(16, 34)
                .addBox(-0.12F, 0.90F, -2.64F, 0.24F, 3.00F, 0.26F)

                // Narrow pull straps are offset again from the four pouch lids.
                .texOffs(18, 34)
                .addBox(-3.15F, 5.08F, -3.48F, 0.20F, 2.20F, 0.16F)
                .texOffs(20, 34)
                .addBox(-1.27F, 5.08F, -3.48F, 0.20F, 2.20F, 0.16F)
                .texOffs(22, 34)
                .addBox(1.07F, 5.08F, -3.48F, 0.20F, 2.20F, 0.16F)
                .texOffs(24, 34)
                .addBox(2.95F, 5.08F, -3.48F, 0.20F, 2.20F, 0.16F)

                // A second outer lip makes the high collar read as a padded ring from every angle.
                .texOffs(26, 34)
                .addBox(-4.38F, -2.18F, -4.56F, 3.80F, 0.22F, 0.52F)
                .texOffs(36, 34)
                .addBox(0.58F, -2.18F, -4.56F, 3.80F, 0.22F, 0.52F)
                .texOffs(46, 34)
                .addBox(-4.38F, -2.10F, 4.04F, 8.76F, 0.22F, 0.52F)
                .texOffs(66, 34)
                .addBox(-4.54F, -2.02F, -4.16F, 0.52F, 0.22F, 8.32F)
                .texOffs(85, 34)
                .addBox(4.02F, -2.02F, -4.16F, 0.52F, 0.22F, 8.32F);
    }
}
