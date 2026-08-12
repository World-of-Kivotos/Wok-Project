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

/** CQC Osprey MK4A assault carrier with articulated wraparound shoulder guards. */
public final class OspreyMk4AAssaultArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_osprey_mk4a_assault"), "main");

    public OspreyMk4AAssaultArmorModel(ModelPart root) {
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
        root.addOrReplaceChild("right_arm", createRightShoulder(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", createLeftShoulder(), PartPose.offset(5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        return CubeListBuilder.create()
                .texOffs(0, 0)
                .addBox(-3.40F, 0.75F, -2.45F, 6.80F, 3.00F, 0.50F)
                .texOffs(16, 0)
                .addBox(-3.40F, 0.75F, 1.95F, 6.80F, 3.00F, 0.50F)
                .texOffs(32, 0)
                .addBox(-4.05F, 3.72F, -2.50F, 8.10F, 4.03F, 0.54F)
                .texOffs(51, 0)
                .addBox(-4.05F, 3.72F, 1.96F, 8.10F, 4.03F, 0.54F)
                .texOffs(70, 0)
                .addBox(-4.15F, 7.72F, -2.52F, 8.30F, 3.73F, 0.58F)
                .texOffs(89, 0)
                .addBox(-4.15F, 7.72F, 1.94F, 8.30F, 3.73F, 0.58F)
                .texOffs(108, 0)
                .addBox(-4.75F, 3.50F, -2.00F, 0.73F, 8.10F, 4.00F)
                .texOffs(118, 0)
                .addBox(4.02F, 3.50F, -2.00F, 0.73F, 8.10F, 4.00F)

                .texOffs(0, 13)
                .addBox(-4.50F, -1.10F, -4.65F, 3.85F, 1.85F, 0.55F)
                .texOffs(10, 13)
                .addBox(0.65F, -1.10F, -4.65F, 3.85F, 1.85F, 0.55F)
                .texOffs(20, 13)
                .addBox(-4.50F, -1.10F, 4.10F, 9.00F, 1.85F, 0.55F)
                .texOffs(41, 13)
                .addBox(-4.65F, -1.05F, -4.16F, 0.55F, 1.77F, 8.32F)
                .texOffs(60, 13)
                .addBox(4.10F, -1.05F, -4.16F, 0.55F, 1.77F, 8.32F)
                .texOffs(79, 13)
                .addBox(-3.50F, 0.45F, -4.16F, 2.87F, 0.36F, 2.23F)
                .texOffs(90, 13)
                .addBox(0.63F, 0.45F, -4.16F, 2.87F, 0.36F, 2.23F)
                .texOffs(101, 13)
                .addBox(-3.50F, 0.45F, 1.93F, 2.87F, 0.36F, 2.23F)
                .texOffs(112, 13)
                .addBox(0.63F, 0.45F, 1.93F, 2.87F, 0.36F, 2.23F)

                // Six tall assault magazines over three broad lower pouches.
                .texOffs(0, 24)
                .addBox(-3.35F, 1.40F, -3.03F, 0.90F, 3.40F, 0.60F)
                .texOffs(4, 24)
                .addBox(-2.25F, 1.40F, -3.03F, 0.90F, 3.40F, 0.60F)
                .texOffs(8, 24)
                .addBox(-1.15F, 1.40F, -3.03F, 0.90F, 3.40F, 0.60F)
                .texOffs(12, 24)
                .addBox(-0.05F, 1.40F, -3.03F, 0.90F, 3.40F, 0.60F)
                .texOffs(16, 24)
                .addBox(1.05F, 1.40F, -3.03F, 0.90F, 3.40F, 0.60F)
                .texOffs(20, 24)
                .addBox(2.15F, 1.40F, -3.03F, 0.90F, 3.40F, 0.60F)
                .texOffs(25, 24)
                .addBox(-3.75F, 5.15F, -3.19F, 2.25F, 3.80F, 0.72F)
                .texOffs(32, 24)
                .addBox(-1.12F, 5.15F, -3.19F, 2.24F, 3.80F, 0.72F)
                .texOffs(39, 24)
                .addBox(1.50F, 5.15F, -3.19F, 2.25F, 3.80F, 0.72F)
                .texOffs(46, 24)
                .addBox(3.72F, 5.18F, -2.70F, 1.00F, 4.00F, 1.40F)

                // The assault reference ends in a wide, short cummerbund; only a
                // small pull tab remains below it, rather than a long groin apron.
                .texOffs(52, 24)
                .addBox(-4.00F, 9.10F, -3.03F, 8.00F, 2.30F, 0.55F)
                .texOffs(70, 24)
                .addBox(-0.90F, 11.25F, -2.90F, 1.80F, 0.85F, 0.45F);
    }

    private static CubeListBuilder createRightShoulder() {
        return CubeListBuilder.create()
                // Open-bottom cloth shell follows the arm without becoming a solid block.
                .texOffs(0, 31)
                .addBox(-3.60F, -2.55F, -2.55F, 4.75F, 0.55F, 5.10F)
                .texOffs(42, 31)
                .addBox(-4.00F, -2.04F, -2.35F, 0.48F, 5.02F, 4.70F)
                .texOffs(66, 31)
                .addBox(-3.58F, -2.05F, -2.75F, 4.68F, 5.05F, 0.42F)
                .texOffs(78, 31)
                .addBox(-3.58F, -2.03F, 2.33F, 4.68F, 4.99F, 0.42F);
    }

    private static CubeListBuilder createLeftShoulder() {
        return CubeListBuilder.create()
                .texOffs(21, 31)
                .addBox(-1.15F, -2.55F, -2.55F, 4.75F, 0.55F, 5.10F)
                .texOffs(54, 31)
                .addBox(3.52F, -2.04F, -2.35F, 0.48F, 5.02F, 4.70F)
                .texOffs(90, 31)
                .addBox(-1.10F, -2.05F, -2.75F, 4.68F, 5.05F, 0.42F)
                .texOffs(102, 31)
                .addBox(-1.10F, -2.03F, 2.33F, 4.68F, 4.99F, 0.42F);
    }
}
