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

/** Korund-VM soft armor with an open padded collar and broad split lower apron. */
public final class KorundVmArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_korund_vm_black"), "main");

    public KorundVmArmorModel(ModelPart root) {
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
                // Two stepped soft courses taper at the upper chest instead of forming a rigid slab.
                .texOffs(0, 0).addBox(-3.35F, 0.60F, -2.52F, 6.70F, 4.60F, 0.52F)
                .texOffs(16, 0).addBox(-3.35F, 0.60F, 2.00F, 6.70F, 4.60F, 0.52F)
                .texOffs(32, 0).addBox(-3.85F, 5.08F, -2.62F, 7.70F, 6.05F, 0.60F)
                .texOffs(50, 0).addBox(-3.85F, 5.08F, 2.02F, 7.70F, 6.05F, 0.60F)
                .texOffs(68, 0).addBox(-3.92F, 2.05F, -2.00F, 0.46F, 9.10F, 4.00F)
                .texOffs(78, 0).addBox(3.46F, 2.05F, -2.00F, 0.46F, 9.10F, 4.00F)

                // A wide throat gap and lower front pads keep the collar visibly soft and open.
                .texOffs(88, 0).addBox(-3.35F, -1.20F, -2.95F, 2.35F, 1.75F, 0.52F)
                .texOffs(95, 0).addBox(1.00F, -1.20F, -2.95F, 2.35F, 1.75F, 0.52F)
                .texOffs(102, 0).addBox(-3.35F, -1.30F, 2.40F, 6.70F, 1.85F, 0.55F)
                .texOffs(0, 15).addBox(-3.79F, -1.17F, -2.40F, 0.44F, 1.80F, 4.80F)
                .texOffs(12, 15).addBox(3.35F, -1.17F, -2.40F, 0.44F, 1.80F, 4.80F)

                // Front shoulder straps, metal buckles, and loose strap tails.
                .texOffs(24, 15).addBox(-3.45F, 0.72F, -2.86F, 1.20F, 3.40F, 0.34F)
                .texOffs(29, 15).addBox(2.25F, 0.72F, -2.86F, 1.20F, 3.40F, 0.34F)
                .texOffs(34, 15).addBox(-3.35F, 1.35F, -3.16F, 1.00F, 0.70F, 0.30F)
                .texOffs(38, 15).addBox(2.35F, 1.35F, -3.16F, 1.00F, 0.70F, 0.30F)
                .texOffs(42, 15).addBox(-3.05F, 2.15F, -3.06F, 0.40F, 2.40F, 0.20F)
                .texOffs(45, 15).addBox(2.65F, 2.15F, -3.06F, 0.40F, 2.40F, 0.20F)

                // Raised seams keep the smooth carrier readable without inventing pouches.
                .texOffs(48, 15).addBox(-3.10F, 4.45F, -2.78F, 6.20F, 0.18F, 0.20F)
                .texOffs(62, 15).addBox(-3.55F, 9.00F, -2.88F, 7.10F, 0.20F, 0.24F)
                .texOffs(78, 15).addBox(-0.10F, 5.82F, -2.96F, 0.20F, 5.20F, 0.28F)
                .texOffs(80, 15).addBox(-3.50F, 5.82F, -2.96F, 0.18F, 5.20F, 0.28F)
                .texOffs(82, 15).addBox(3.32F, 5.82F, -2.96F, 0.18F, 5.20F, 0.28F)

                // Broad near-touching panels use inset lower steps to suggest rounded soft hems.
                .texOffs(84, 15).addBox(-3.72F, 11.10F, -2.78F, 3.70F, 3.55F, 0.58F)
                .texOffs(94, 15).addBox(0.02F, 11.10F, -2.78F, 3.70F, 3.55F, 0.58F)
                .texOffs(104, 15).addBox(-3.47F, 14.48F, -2.75F, 3.30F, 1.45F, 0.54F)
                .texOffs(113, 15).addBox(0.17F, 14.48F, -2.75F, 3.30F, 1.45F, 0.54F)
                .texOffs(0, 23).addBox(-3.60F, 10.95F, -3.06F, 7.20F, 0.55F, 0.25F)
                .texOffs(17, 23).addBox(-4.17F, 10.95F, -3.02F, 0.45F, 3.30F, 0.50F)
                .texOffs(20, 23).addBox(3.72F, 10.95F, -3.02F, 0.45F, 3.30F, 0.50F);
    }
}
