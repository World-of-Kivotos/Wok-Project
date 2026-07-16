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

/** Heavy 6B43 Zabralo-Sh with a stand collar, segmented sleeves and long tapered groin guard. */
public final class B6B43ZabraloShArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_6b43_zabralo_sh"), "main");

    public B6B43ZabraloShArmorModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("body", createBody(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", createRightArm(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", createLeftArm(), PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        return CubeListBuilder.create()
                // Three stepped courses on each face make the shell visibly thick without
                // placing a single inflated cuboid across the moving arm volumes.
                .texOffs(0, 0)
                .addBox(-3.50F, 0.25F, -2.68F, 7.00F, 3.25F, 0.64F)
                .texOffs(17, 0)
                .addBox(-3.88F, 3.30F, -2.76F, 7.76F, 4.25F, 0.72F)
                .texOffs(35, 0)
                .addBox(-4.00F, 7.35F, -2.82F, 8.00F, 4.20F, 0.78F)
                .texOffs(54, 0)
                .addBox(-3.50F, 0.25F, 2.04F, 7.00F, 3.25F, 0.64F)
                .texOffs(71, 0)
                .addBox(-3.88F, 3.30F, 2.04F, 7.76F, 4.25F, 0.72F)
                .texOffs(89, 0)
                .addBox(-4.00F, 7.35F, 2.04F, 8.00F, 4.20F, 0.78F)

                // Continuous side courses stay at the torso edge and stop short of the
                // neutral arm surface, avoiding the old body-to-sleeve intersection.
                .texOffs(108, 0)
                .addBox(-3.96F, 2.25F, -2.30F, 0.43F, 9.24F, 4.60F)
                .texOffs(0, 15)
                .addBox(3.53F, 2.25F, -2.30F, 0.43F, 9.24F, 4.60F)

                // Five thick stand-collar panels reproduce the Zabralo silhouette. The
                // split front leaves a 1.8-pixel central opening, so it never masks the face.
                .texOffs(12, 15)
                .addBox(-4.15F, -1.85F, -4.52F, 3.25F, 1.85F, 0.55F)
                .texOffs(21, 15)
                .addBox(0.90F, -1.85F, -4.52F, 3.25F, 1.85F, 0.55F)
                .texOffs(30, 15)
                .addBox(-4.20F, -2.00F, 3.97F, 8.40F, 2.00F, 0.55F)
                .texOffs(49, 15)
                .addBox(-4.52F, -1.95F, -3.98F, 0.55F, 1.95F, 7.96F)
                .texOffs(68, 15)
                .addBox(3.97F, -1.95F, -3.98F, 0.55F, 1.95F, 7.96F)

                // Shallow roots overlap both collar and torso rather than leaving a moving crack.
                .texOffs(87, 15)
                .addBox(-3.35F, -0.15F, -4.00F, 2.45F, 0.65F, 1.45F)
                .texOffs(96, 15)
                .addBox(0.90F, -0.15F, -4.00F, 2.45F, 0.65F, 1.45F)
                .texOffs(105, 15)
                .addBox(-3.35F, -0.15F, 2.55F, 2.45F, 0.65F, 1.45F)
                .texOffs(114, 15)
                .addBox(0.90F, -0.15F, 2.55F, 2.45F, 0.65F, 1.45F)

                // The reference front is empty MOLLE: seven horizontal rows, no invented
                // magazine or utility pouches.
                .texOffs(0, 30)
                .addBox(-3.50F, 2.65F, -2.94F, 7.00F, 0.22F, 0.20F)
                .texOffs(16, 30)
                .addBox(-3.50F, 3.75F, -2.94F, 7.00F, 0.22F, 0.20F)
                .texOffs(32, 30)
                .addBox(-3.50F, 4.85F, -2.94F, 7.00F, 0.22F, 0.20F)
                .texOffs(48, 30)
                .addBox(-3.50F, 5.95F, -2.94F, 7.00F, 0.22F, 0.20F)
                .texOffs(64, 30)
                .addBox(-3.50F, 7.05F, -2.94F, 7.00F, 0.22F, 0.20F)
                .texOffs(80, 30)
                .addBox(-3.50F, 8.20F, -2.94F, 7.00F, 0.22F, 0.20F)
                .texOffs(96, 30)
                .addBox(-3.50F, 9.35F, -2.94F, 7.00F, 0.22F, 0.20F)

                .texOffs(0, 32)
                .addBox(-4.00F, 10.35F, -3.10F, 8.00F, 1.25F, 0.30F)
                .texOffs(18, 32)
                .addBox(-4.00F, 10.35F, 2.80F, 8.00F, 1.25F, 0.30F)
                .texOffs(36, 32)
                .addBox(-3.90F, 10.38F, -2.34F, 0.39F, 1.05F, 4.68F)
                .texOffs(48, 32)
                .addBox(3.51F, 10.38F, -2.34F, 0.39F, 1.05F, 4.68F)

                // Five overlapping levels taper the wide central groin guard down to a
                // rounded-looking narrow tip. Each front plane is offset by 0.04 to prevent
                // coplanar flicker between levels.
                .texOffs(60, 32)
                .addBox(-3.00F, 11.10F, -3.36F, 6.00F, 2.00F, 0.50F)
                .texOffs(74, 32)
                .addBox(-2.72F, 12.90F, -3.40F, 5.44F, 2.05F, 0.48F)
                .texOffs(87, 32)
                .addBox(-2.38F, 14.75F, -3.44F, 4.76F, 1.95F, 0.46F)
                .texOffs(99, 32)
                .addBox(-2.00F, 16.50F, -3.48F, 4.00F, 1.75F, 0.44F)
                .texOffs(109, 32)
                .addBox(-1.50F, 18.05F, -3.52F, 3.00F, 1.35F, 0.42F)

                // A large rectangular secondary plate is visibly raised over the upper guard.
                .texOffs(117, 32)
                .addBox(-2.35F, 11.65F, -3.70F, 4.70F, 3.15F, 0.30F)
                .texOffs(0, 39)
                .addBox(-2.15F, 12.48F, -3.94F, 4.30F, 0.24F, 0.18F);
    }

    private static CubeListBuilder createRightArm() {
        return CubeListBuilder.create()
                // The bridge overlaps the sleeve by a small amount; all visible front/rear
                // courses use stepped depth values, eliminating coincident faces.
                .texOffs(10, 39)
                .addBox(-3.25F, -2.35F, -2.45F, 4.30F, 0.58F, 4.90F)
                .texOffs(30, 39)
                .addBox(-3.40F, -1.85F, -2.22F, 0.52F, 3.15F, 4.44F)
                .texOffs(41, 39)
                .addBox(-3.36F, 1.20F, -2.18F, 0.48F, 3.35F, 4.36F)
                .texOffs(52, 39)
                .addBox(-3.25F, -1.78F, -2.82F, 4.15F, 2.15F, 0.46F)
                .texOffs(63, 39)
                .addBox(-3.15F, 0.28F, -2.88F, 3.95F, 2.05F, 0.44F)
                .texOffs(73, 39)
                .addBox(-3.00F, 2.22F, -2.84F, 3.65F, 2.18F, 0.42F)
                .texOffs(83, 39)
                .addBox(-3.25F, -1.78F, 2.36F, 4.15F, 2.15F, 0.46F)
                .texOffs(94, 39)
                .addBox(-3.15F, 0.28F, 2.44F, 3.95F, 2.05F, 0.44F)
                .texOffs(104, 39)
                .addBox(-3.00F, 2.22F, 2.42F, 3.65F, 2.18F, 0.42F);
    }

    private static CubeListBuilder createLeftArm() {
        return CubeListBuilder.create()
                .texOffs(0, 48)
                .addBox(-1.05F, -2.35F, -2.45F, 4.30F, 0.58F, 4.90F)
                .texOffs(20, 48)
                .addBox(2.88F, -1.85F, -2.22F, 0.52F, 3.15F, 4.44F)
                .texOffs(31, 48)
                .addBox(2.88F, 1.20F, -2.18F, 0.48F, 3.35F, 4.36F)
                .texOffs(42, 48)
                .addBox(-0.90F, -1.78F, -2.82F, 4.15F, 2.15F, 0.46F)
                .texOffs(53, 48)
                .addBox(-0.80F, 0.28F, -2.88F, 3.95F, 2.05F, 0.44F)
                .texOffs(63, 48)
                .addBox(-0.65F, 2.22F, -2.84F, 3.65F, 2.18F, 0.42F)
                .texOffs(73, 48)
                .addBox(-0.90F, -1.78F, 2.36F, 4.15F, 2.15F, 0.46F)
                .texOffs(84, 48)
                .addBox(-0.80F, 0.28F, 2.44F, 3.95F, 2.05F, 0.44F)
                .texOffs(94, 48)
                .addBox(-0.65F, 2.22F, 2.42F, 3.65F, 2.18F, 0.42F);
    }
}
