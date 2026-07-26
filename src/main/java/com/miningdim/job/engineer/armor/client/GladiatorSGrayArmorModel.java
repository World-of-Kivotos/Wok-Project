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

/** Gray Gladiator-S carrier with a high collar and an unloaded full-face MOLLE field. */
public final class GladiatorSGrayArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_gladiator_s_gray"), "main");

    public GladiatorSGrayArmorModel(ModelPart root) {
        super(root);
    }

    private static CubeListBuilder body() {
        return CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.50F, 0.50F, -2.48F, 7.00F, 3.00F, 0.46F)
                .texOffs(16, 0).addBox(-3.85F, 3.35F, -2.58F, 7.70F, 6.90F, 0.54F)
                .texOffs(34, 0).addBox(-3.50F, 0.50F, 2.02F, 7.00F, 3.00F, 0.46F)
                .texOffs(50, 0).addBox(-3.85F, 3.35F, 2.04F, 7.70F, 6.90F, 0.54F)
                .texOffs(68, 0).addBox(-3.98F, 2.40F, -2.00F, 0.40F, 7.80F, 4.00F)
                .texOffs(78, 0).addBox(3.58F, 2.40F, -2.00F, 0.40F, 7.80F, 4.00F)

                // Four thin side wings sit outside the arm envelope instead of becoming bulky packs.
                .texOffs(88, 0).addBox(-4.53F, 3.35F, -2.72F, 0.68F, 7.00F, 0.50F)
                .texOffs(92, 0).addBox(3.85F, 3.35F, -2.72F, 0.68F, 7.00F, 0.50F)
                .texOffs(96, 0).addBox(-4.53F, 3.35F, 2.22F, 0.68F, 7.00F, 0.50F)
                .texOffs(100, 0).addBox(3.85F, 3.35F, 2.22F, 0.68F, 7.00F, 0.50F)
                .texOffs(104, 0).addBox(-3.45F, -0.05F, -2.78F, 2.90F, 1.20F, 0.30F)
                .texOffs(112, 0).addBox(0.55F, -0.05F, -2.78F, 2.90F, 1.20F, 0.30F)
                .texOffs(120, 0).addBox(-3.45F, -0.05F, 2.48F, 2.90F, 1.20F, 0.30F)
                .texOffs(0, 13).addBox(0.55F, -0.05F, 2.48F, 2.90F, 1.20F, 0.30F)

                // The front opening is widened while the offset rim reads as a soft rolled collar.
                .texOffs(8, 13).addBox(-4.25F, -1.00F, -4.07F, 3.70F, 1.75F, 0.52F)
                .texOffs(18, 13).addBox(0.55F, -1.00F, -4.07F, 3.70F, 1.75F, 0.52F)
                .texOffs(28, 13).addBox(-4.00F, -1.00F, 3.55F, 8.00F, 1.75F, 0.52F)
                .texOffs(47, 13).addBox(-3.95F, -1.00F, -3.55F, 0.46F, 1.75F, 7.10F)
                .texOffs(64, 13).addBox(3.49F, -1.00F, -3.55F, 0.46F, 1.75F, 7.10F)
                .texOffs(81, 13).addBox(-4.20F, -1.30F, -4.15F, 3.60F, 0.35F, 0.60F)
                .texOffs(91, 13).addBox(0.60F, -1.30F, -4.15F, 3.60F, 0.35F, 0.60F)
                .texOffs(101, 13).addBox(-3.95F, -1.30F, 3.55F, 7.90F, 0.35F, 0.60F)
                .texOffs(0, 23).addBox(-3.98F, -1.30F, -3.50F, 0.52F, 0.35F, 7.00F)
                .texOffs(17, 23).addBox(3.46F, -1.30F, -3.50F, 0.52F, 0.35F, 7.00F)

                // Six visibly segmented woven rows show the empty MOLLE face without fake pouches.
                .texOffs(34, 23).addBox(-3.45F, 1.95F, -2.65F, 6.90F, 0.25F, 0.19F)
                .texOffs(50, 23).addBox(-3.45F, 3.30F, -2.77F, 6.90F, 0.25F, 0.19F)
                .texOffs(66, 23).addBox(-3.45F, 4.55F, -2.77F, 6.90F, 0.25F, 0.19F)
                .texOffs(82, 23).addBox(-3.45F, 5.80F, -2.77F, 6.90F, 0.25F, 0.19F)
                .texOffs(98, 23).addBox(-3.45F, 7.05F, -2.77F, 6.90F, 0.25F, 0.19F)
                .texOffs(0, 32).addBox(-3.45F, 8.30F, -2.77F, 6.90F, 0.25F, 0.19F)
                .texOffs(16, 32).addBox(-3.80F, 9.40F, -2.88F, 7.60F, 1.10F, 0.30F)
                .texOffs(33, 32).addBox(-3.80F, 9.40F, 2.58F, 7.60F, 1.10F, 0.30F)
                .texOffs(50, 32).addBox(-3.94F, 9.40F, -2.20F, 0.36F, 1.10F, 4.40F)
                .texOffs(61, 32).addBox(3.58F, 9.40F, -2.20F, 0.36F, 1.10F, 4.40F)
                .texOffs(72, 32).addBox(-1.40F, 0.75F, -2.70F, 2.80F, 1.00F, 0.22F);
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
