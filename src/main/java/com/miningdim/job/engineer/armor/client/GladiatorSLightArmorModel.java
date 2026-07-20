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

/** FORT Gladiator-S light carrier with dense closed pouches and a long tapered groin guard. */
public final class GladiatorSLightArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_gladiator_s_light_multicam"), "main");

    public GladiatorSLightArmorModel(ModelPart root) {
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
                // Split front and rear shells keep the light carrier narrow at the shoulders.
                .texOffs(0, 0)
                .addBox(-3.35F, 0.35F, -2.48F, 6.70F, 3.65F, 0.50F)
                .texOffs(25, 0)
                .addBox(-3.86F, 3.82F, -2.55F, 7.72F, 7.55F, 0.56F)
                .texOffs(50, 0)
                .addBox(-3.35F, 0.35F, 1.98F, 6.70F, 3.65F, 0.50F)
                .texOffs(75, 0)
                .addBox(-3.86F, 3.82F, 1.99F, 7.72F, 7.55F, 0.56F)
                .texOffs(100, 0)
                .addBox(-3.93F, 3.62F, -2.05F, 0.45F, 7.70F, 4.10F)
                .texOffs(100, 0)
                .addBox(3.48F, 3.62F, -2.05F, 0.45F, 7.70F, 4.10F)

                // Soft load-bearing yokes replace the large shoulder armor used by heavier Gladiators.
                .texOffs(0, 22)
                .addBox(-3.13F, -0.10F, -2.85F, 1.35F, 4.20F, 0.42F)
                .texOffs(0, 22)
                .addBox(1.78F, -0.10F, -2.85F, 1.35F, 4.20F, 0.42F)
                .texOffs(25, 22)
                .addBox(-3.13F, -0.10F, 2.43F, 1.35F, 4.20F, 0.42F)
                .texOffs(25, 22)
                .addBox(1.78F, -0.10F, 2.43F, 1.35F, 4.20F, 0.42F)
                .texOffs(50, 22)
                .addBox(-3.10F, -0.35F, -2.46F, 1.29F, 0.55F, 4.92F)
                .texOffs(50, 22)
                .addBox(1.81F, -0.35F, -2.46F, 1.29F, 0.55F, 4.92F)

                // Two closed upper utility pouches and four compact magazine pouches fill the front.
                .texOffs(75, 22)
                .addBox(-3.10F, 1.38F, -3.42F, 2.25F, 2.65F, 0.96F)
                .texOffs(75, 22)
                .addBox(0.85F, 1.38F, -3.42F, 2.25F, 2.65F, 0.96F)
                .texOffs(100, 22)
                .addBox(-3.05F, 1.18F, -3.67F, 2.15F, 0.82F, 0.35F)
                .texOffs(100, 22)
                .addBox(0.90F, 1.18F, -3.67F, 2.15F, 0.82F, 0.35F)

                .texOffs(0, 44)
                .addBox(-2.60F, 4.15F, -3.50F, 1.20F, 4.15F, 1.02F)
                .texOffs(0, 44)
                .addBox(-1.30F, 4.15F, -3.50F, 1.20F, 4.15F, 1.02F)
                .texOffs(0, 44)
                .addBox(0.10F, 4.15F, -3.50F, 1.20F, 4.15F, 1.02F)
                .texOffs(0, 44)
                .addBox(1.40F, 4.15F, -3.50F, 1.20F, 4.15F, 1.02F)
                .texOffs(25, 44)
                .addBox(-2.57F, 3.95F, -3.72F, 1.14F, 0.86F, 0.34F)
                .texOffs(25, 44)
                .addBox(-1.27F, 3.95F, -3.72F, 1.14F, 0.86F, 0.34F)
                .texOffs(25, 44)
                .addBox(0.13F, 3.95F, -3.72F, 1.14F, 0.86F, 0.34F)
                .texOffs(25, 44)
                .addBox(1.43F, 3.95F, -3.72F, 1.14F, 0.86F, 0.34F)

                // The left tool bag is broad and stepped; the right side is a shallow grouped pouch bank.
                .texOffs(50, 44)
                .addBox(-5.00F, 5.15F, -3.62F, 2.60F, 4.90F, 1.22F)
                .texOffs(75, 44)
                .addBox(-5.05F, 4.90F, -3.80F, 2.70F, 1.20F, 0.35F)
                .texOffs(15, 88)
                .addBox(-4.78F, 9.72F, -3.52F, 2.10F, 0.72F, 0.92F)

                .texOffs(0, 88)
                .addBox(2.65F, 5.35F, -3.57F, 1.70F, 4.70F, 1.05F)
                .texOffs(8, 88)
                .addBox(2.60F, 5.08F, -3.75F, 1.70F, 1.00F, 0.35F)
                .texOffs(24, 88)
                .addBox(2.72F, 5.75F, -3.96F, 0.72F, 1.85F, 0.45F)
                .texOffs(29, 88)
                .addBox(2.68F, 5.55F, -4.08F, 0.80F, 0.45F, 0.22F)
                .texOffs(34, 88)
                .addBox(3.50F, 6.35F, -3.99F, 0.82F, 2.35F, 0.48F)
                .texOffs(39, 88)
                .addBox(3.46F, 6.12F, -4.11F, 0.90F, 0.48F, 0.22F)

                // Four strictly shrinking stages form the long water-drop-shaped groin guard.
                .texOffs(100, 44)
                .addBox(-3.10F, 9.72F, -3.05F, 6.20F, 1.70F, 0.58F)
                .texOffs(0, 66)
                .addBox(-2.70F, 11.20F, -2.98F, 5.40F, 2.40F, 0.50F)
                .texOffs(47, 88)
                .addBox(-2.25F, 13.38F, -2.91F, 4.50F, 2.15F, 0.46F)
                .texOffs(25, 66)
                .addBox(-1.80F, 15.31F, -2.85F, 3.60F, 1.75F, 0.42F)

                .texOffs(50, 66)
                .addBox(-3.00F, 1.02F, -2.82F, 6.00F, 0.20F, 0.38F)
                .texOffs(50, 66)
                .addBox(-3.00F, 2.10F, -2.82F, 6.00F, 0.20F, 0.38F)
                .texOffs(50, 66)
                .addBox(-3.00F, 3.18F, -2.82F, 6.00F, 0.20F, 0.38F)
                .texOffs(75, 66)
                .addBox(-3.75F, 9.08F, -2.88F, 7.50F, 0.86F, 0.38F)
                .texOffs(75, 66)
                .addBox(-3.75F, 9.08F, 2.50F, 7.50F, 0.86F, 0.38F);

        body.texOffs(100, 66).addBox(-2.10F, 4.62F, -3.86F, 0.20F, 2.10F, 0.22F);
        body.texOffs(100, 66).addBox(-0.80F, 4.62F, -3.86F, 0.20F, 2.10F, 0.22F);
        body.texOffs(100, 66).addBox(0.60F, 4.62F, -3.86F, 0.20F, 2.10F, 0.22F);
        body.texOffs(100, 66).addBox(1.90F, 4.62F, -3.86F, 0.20F, 2.10F, 0.22F);
        body.texOffs(100, 66).addBox(-3.44F, 6.25F, -3.94F, 0.22F, 2.30F, 0.38F);
        body.texOffs(100, 66).addBox(3.25F, 6.25F, -3.90F, 0.22F, 2.30F, 0.38F);
        return body;
    }
}
