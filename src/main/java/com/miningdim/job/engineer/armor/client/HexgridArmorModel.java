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

/** Lightweight 5.11 Hexgrid carrier with an exposed staggered honeycomb plate. */
public final class HexgridArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_hexgrid"), "main");

    private static final int HONEYCOMB_ROWS = 8;

    public HexgridArmorModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(),
                PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(),
                PartPose.offset(1.9F, 12.0F, 0.0F));

        PartDefinition body = root.addOrReplaceChild("body", createBody(), PartPose.ZERO);
        addFlexibleSideBraces(body);
        addHoneycombGrid(body);

        // Hexgrid has load-bearing shoulder straps, never armored shoulder or upper-arm parts.
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(),
                PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(),
                PartPose.offset(5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        return CubeListBuilder.create()
                // Narrow front and rear plate bags leave the lower torso visibly uncovered.
                .texOffs(0, 0)
                .addBox(-3.45F, 1.40F, -2.72F, 6.90F, 9.10F, 0.64F)
                .texOffs(16, 0)
                .addBox(-3.40F, 1.48F, 2.08F, 6.80F, 9.00F, 0.62F)

                // Four broad textile straps and two top bridges form the open neckline.
                .texOffs(32, 0)
                .addBox(-3.22F, -0.28F, -2.62F, 1.48F, 4.02F, 0.44F)
                .texOffs(32, 0)
                .addBox(1.74F, -0.28F, -2.62F, 1.48F, 4.02F, 0.44F)
                .texOffs(37, 0)
                .addBox(-3.22F, -0.28F, 2.18F, 1.48F, 4.02F, 0.44F)
                .texOffs(37, 0)
                .addBox(1.74F, -0.28F, 2.18F, 1.48F, 4.02F, 0.44F)
                .texOffs(42, 0)
                .addBox(-3.16F, -0.54F, -2.30F, 1.36F, 0.48F, 4.60F)
                .texOffs(42, 0)
                .addBox(1.80F, -0.54F, -2.30F, 1.36F, 0.48F, 4.60F)

                // Flexible cummerbund: the shallow sheets overlap both plate bags by 0.04F in
                // depth and at least 0.04F in width. That seam keeps the vest visually continuous while
                // the outer edge remains clear of the neutral player arms.
                .texOffs(56, 0)
                .addBox(-3.90F, 4.70F, -2.12F, 0.54F, 5.45F, 4.24F)
                .texOffs(56, 0)
                .addBox(3.36F, 4.70F, -2.12F, 0.54F, 5.45F, 4.24F)
                .texOffs(66, 0)
                .addBox(-3.94F, 5.15F, -1.84F, 0.60F, 0.74F, 3.68F)
                .texOffs(66, 0)
                .addBox(3.34F, 5.15F, -1.84F, 0.60F, 0.74F, 3.68F)
                .texOffs(66, 0)
                .addBox(-3.94F, 8.82F, -1.84F, 0.60F, 0.74F, 3.68F)
                .texOffs(66, 0)
                .addBox(3.34F, 8.82F, -1.84F, 0.60F, 0.74F, 3.68F)
                .texOffs(75, 0)
                .addBox(-3.42F, 9.62F, -2.84F, 6.84F, 0.78F, 0.26F)
                .texOffs(90, 0)
                .addBox(-3.37F, 9.62F, 2.58F, 6.74F, 0.78F, 0.26F)

                // A raised perimeter frames the plate without sharing a front plane at its corners.
                .texOffs(0, 16)
                .addBox(-3.50F, 1.60F, -2.92F, 0.20F, 8.70F, 0.24F)
                .texOffs(0, 16)
                .addBox(3.30F, 1.60F, -2.92F, 0.20F, 8.70F, 0.24F)
                .texOffs(4, 16)
                .addBox(-3.30F, 1.34F, -2.88F, 6.60F, 0.20F, 0.20F)
                .texOffs(4, 16)
                .addBox(-3.30F, 10.34F, -2.88F, 6.60F, 0.20F, 0.20F);
    }

    private static void addFlexibleSideBraces(PartDefinition body) {
        // The offset planes keep the two diagonal textile bands from becoming coplanar.
        body.addOrReplaceChild("left_side_brace", CubeListBuilder.create().texOffs(20, 16)
                        .addBox(-0.10F, -2.15F, -0.15F, 0.20F, 4.30F, 0.30F),
                PartPose.offsetAndRotation(-3.88F, 7.48F, 0.0F, 0.48F, 0.0F, 0.0F));
        body.addOrReplaceChild("right_side_brace", CubeListBuilder.create().texOffs(24, 16)
                        .addBox(-0.10F, -2.15F, -0.13F, 0.20F, 4.30F, 0.26F),
                PartPose.offsetAndRotation(3.88F, 7.48F, 0.0F, -0.48F, 0.0F, 0.0F));
    }

    private static void addHoneycombGrid(PartDefinition body) {
        int index = 0;
        for (int row = 0; row < HONEYCOMB_ROWS; row++) {
            int columns = row % 2 == 0 ? 5 : 4;
            float firstX = columns == 5 ? -2.32F : -1.74F;
            float y = 2.35F + row * 0.98F;
            for (int column = 0; column < columns; column++) {
                addHoneycombCell(body, index++, firstX + column * 1.16F, y);
            }
        }
    }

    private static void addHoneycombCell(PartDefinition body, int index, float x, float y) {
        String prefix = "honeycomb_" + index;

        // Six separate bars create a true flat-top hexagonal outline. Their front depths step
        // by 0.01F so corner overlaps cannot produce coincident faces or texture flicker.
        body.addOrReplaceChild(prefix + "_top", CubeListBuilder.create().texOffs(0, 36)
                        .addBox(-0.36F, -0.065F, -0.12F, 0.72F, 0.13F, 0.22F),
                PartPose.offset(x, y - 0.39F, -2.80F));
        body.addOrReplaceChild(prefix + "_bottom", CubeListBuilder.create().texOffs(4, 36)
                        .addBox(-0.36F, -0.065F, -0.11F, 0.72F, 0.13F, 0.21F),
                PartPose.offset(x, y + 0.39F, -2.80F));
        body.addOrReplaceChild(prefix + "_upper_left", CubeListBuilder.create().texOffs(8, 36)
                        .addBox(-0.07F, -0.28F, -0.10F, 0.14F, 0.56F, 0.20F),
                PartPose.offsetAndRotation(x - 0.36F, y - 0.20F, -2.80F,
                        0.0F, 0.0F, -0.52F));
        body.addOrReplaceChild(prefix + "_lower_left", CubeListBuilder.create().texOffs(12, 36)
                        .addBox(-0.07F, -0.28F, -0.09F, 0.14F, 0.56F, 0.19F),
                PartPose.offsetAndRotation(x - 0.36F, y + 0.20F, -2.80F,
                        0.0F, 0.0F, 0.52F));
        body.addOrReplaceChild(prefix + "_upper_right", CubeListBuilder.create().texOffs(16, 36)
                        .addBox(-0.07F, -0.28F, -0.08F, 0.14F, 0.56F, 0.18F),
                PartPose.offsetAndRotation(x + 0.36F, y - 0.20F, -2.80F,
                        0.0F, 0.0F, 0.52F));
        body.addOrReplaceChild(prefix + "_lower_right", CubeListBuilder.create().texOffs(20, 36)
                        .addBox(-0.07F, -0.28F, -0.07F, 0.14F, 0.56F, 0.17F),
                PartPose.offsetAndRotation(x + 0.36F, y + 0.20F, -2.80F,
                        0.0F, 0.0F, -0.52F));
    }
}
