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

/** Gladiator-S Deathless with a high collar, giant articulated sleeves and red/gold ammunition. */
public final class GladiatorSDeathlessArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_gladiator_s_deathless"), "main");

    public GladiatorSDeathlessArmorModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("body", createBody(), PartPose.ZERO);

        // Layered sleeves widen through the middle, taper below, and remain open at the bottom.
        root.addOrReplaceChild("right_arm", CubeListBuilder.create()
                        .texOffs(0, 88)
                        .addBox(-3.15F, -2.50F, -2.40F, 4.20F, 0.55F, 4.80F)
                        .texOffs(25, 88)
                        .addBox(-3.05F, -2.00F, -2.72F, 4.10F, 1.55F, 0.46F)
                        .texOffs(25, 88)
                        .addBox(-3.05F, -2.00F, 2.26F, 4.10F, 1.55F, 0.46F)
                        .texOffs(50, 88)
                        .addBox(-3.35F, -0.55F, -2.82F, 4.25F, 2.35F, 0.50F)
                        .texOffs(50, 88)
                        .addBox(-3.35F, -0.55F, 2.32F, 4.25F, 2.35F, 0.50F)
                        .texOffs(75, 88)
                        .addBox(-3.05F, 1.70F, -2.70F, 3.55F, 1.75F, 0.42F)
                        .texOffs(75, 88)
                        .addBox(-3.05F, 1.70F, 2.28F, 3.55F, 1.75F, 0.42F)
                        .texOffs(100, 88)
                        .addBox(-3.62F, -1.95F, -2.25F, 0.48F, 2.35F, 4.50F)
                        .texOffs(0, 110)
                        .addBox(-3.55F, 0.30F, -2.00F, 0.42F, 2.90F, 4.00F),
                PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create()
                        .texOffs(0, 88)
                        .addBox(-1.05F, -2.50F, -2.40F, 4.20F, 0.55F, 4.80F)
                        .texOffs(25, 88)
                        .addBox(-1.05F, -2.00F, -2.72F, 4.10F, 1.55F, 0.46F)
                        .texOffs(25, 88)
                        .addBox(-1.05F, -2.00F, 2.26F, 4.10F, 1.55F, 0.46F)
                        .texOffs(50, 88)
                        .addBox(-0.90F, -0.55F, -2.82F, 4.25F, 2.35F, 0.50F)
                        .texOffs(50, 88)
                        .addBox(-0.90F, -0.55F, 2.32F, 4.25F, 2.35F, 0.50F)
                        .texOffs(75, 88)
                        .addBox(-0.50F, 1.70F, -2.70F, 3.55F, 1.75F, 0.42F)
                        .texOffs(75, 88)
                        .addBox(-0.50F, 1.70F, 2.28F, 3.55F, 1.75F, 0.42F)
                        .texOffs(100, 88)
                        .addBox(3.14F, -1.95F, -2.25F, 0.48F, 2.35F, 4.50F)
                        .texOffs(0, 110)
                        .addBox(3.13F, 0.30F, -2.00F, 0.42F, 2.90F, 4.00F),
                PartPose.offset(5.0F, 2.0F, 0.0F));

        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder createBody() {
        CubeListBuilder body = CubeListBuilder.create()
                // Dense three-course shell, intentionally distinct from every other Gladiator configuration.
                .texOffs(0, 0)
                .addBox(-3.48F, 0.55F, -2.60F, 6.96F, 3.25F, 0.56F)
                .texOffs(25, 0)
                .addBox(-3.82F, 3.60F, -2.68F, 7.64F, 4.10F, 0.64F)
                .texOffs(50, 0)
                .addBox(-3.88F, 7.48F, -2.75F, 7.76F, 3.82F, 0.70F)
                .texOffs(75, 0)
                .addBox(-3.48F, 0.55F, 2.04F, 6.96F, 3.25F, 0.56F)
                .texOffs(100, 0)
                .addBox(-3.82F, 3.60F, 2.04F, 7.64F, 4.10F, 0.64F)
                .texOffs(0, 22)
                .addBox(-3.88F, 7.48F, 2.05F, 7.76F, 3.82F, 0.70F)
                .texOffs(25, 22)
                .addBox(-3.92F, 3.28F, -2.08F, 0.40F, 7.94F, 4.16F)
                .texOffs(25, 22)
                .addBox(3.52F, 3.28F, -2.08F, 0.40F, 7.94F, 4.16F)

                // Five tall pads and four roots make the enclosed raised collar unmistakable.
                .texOffs(50, 22)
                .addBox(-3.98F, -1.60F, -4.35F, 3.63F, 2.40F, 0.52F)
                .texOffs(50, 22)
                .addBox(0.35F, -1.60F, -4.35F, 3.63F, 2.40F, 0.52F)
                .texOffs(75, 22)
                .addBox(-3.98F, -1.60F, 3.83F, 7.96F, 2.40F, 0.52F)
                .texOffs(100, 22)
                .addBox(-3.98F, -1.57F, -3.83F, 0.44F, 2.34F, 7.66F)
                .texOffs(100, 22)
                .addBox(3.54F, -1.57F, -3.83F, 0.44F, 2.34F, 7.66F)
                .texOffs(0, 44)
                .addBox(-3.35F, 0.47F, -3.83F, 2.75F, 0.45F, 1.82F)
                .texOffs(0, 44)
                .addBox(0.60F, 0.47F, -3.83F, 2.75F, 0.45F, 1.82F)
                .texOffs(0, 44)
                .addBox(-3.35F, 0.47F, 2.01F, 2.75F, 0.45F, 1.82F)
                .texOffs(0, 44)
                .addBox(0.60F, 0.47F, 2.01F, 2.75F, 0.45F, 1.82F)

                // Six shorter gold rounds retain separate raised caps instead of one tall solid bank.
                .texOffs(25, 44)
                .addBox(-3.30F, 3.25F, -3.40F, 0.82F, 2.75F, 0.80F)
                .texOffs(25, 44)
                .addBox(-2.25F, 3.25F, -3.40F, 0.82F, 2.75F, 0.80F)
                .texOffs(25, 44)
                .addBox(-1.20F, 3.25F, -3.40F, 0.82F, 2.75F, 0.80F)
                .texOffs(25, 44)
                .addBox(0.38F, 3.25F, -3.40F, 0.82F, 2.75F, 0.80F)
                .texOffs(25, 44)
                .addBox(1.43F, 3.25F, -3.40F, 0.82F, 2.75F, 0.80F)
                .texOffs(25, 44)
                .addBox(2.48F, 3.25F, -3.40F, 0.82F, 2.75F, 0.80F)
                .texOffs(50, 44)
                .addBox(-3.23F, 2.95F, -3.50F, 0.68F, 0.50F, 0.92F)
                .texOffs(50, 44)
                .addBox(-2.18F, 2.95F, -3.50F, 0.68F, 0.50F, 0.92F)
                .texOffs(50, 44)
                .addBox(-1.13F, 2.95F, -3.50F, 0.68F, 0.50F, 0.92F)
                .texOffs(50, 44)
                .addBox(0.45F, 2.95F, -3.50F, 0.68F, 0.50F, 0.92F)
                .texOffs(50, 44)
                .addBox(1.50F, 2.95F, -3.50F, 0.68F, 0.50F, 0.92F)
                .texOffs(50, 44)
                .addBox(2.55F, 2.95F, -3.50F, 0.68F, 0.50F, 0.92F)

                // Four independent red rounds form the left-side column; there is no right red pouch.
                .texOffs(75, 44)
                .addBox(-3.72F, 6.35F, -3.60F, 0.92F, 0.62F, 1.00F)
                .texOffs(75, 44)
                .addBox(-3.72F, 7.18F, -3.60F, 0.92F, 0.62F, 1.00F)
                .texOffs(75, 44)
                .addBox(-3.72F, 8.01F, -3.60F, 0.92F, 0.62F, 1.00F)
                .texOffs(75, 44)
                .addBox(-3.72F, 8.84F, -3.60F, 0.92F, 0.62F, 1.00F)

                .texOffs(0, 66)
                .addBox(-3.96F, 9.90F, -3.02F, 7.92F, 1.25F, 0.40F)
                .texOffs(0, 66)
                .addBox(-3.96F, 9.90F, 2.62F, 7.92F, 1.25F, 0.40F)

                // The Deathless apron stays long but finishes as a wider rounded droplet.
                .texOffs(25, 66)
                .addBox(-3.25F, 10.82F, -3.22F, 6.50F, 1.90F, 0.56F)
                .texOffs(50, 66)
                .addBox(-2.90F, 12.55F, -3.16F, 5.80F, 3.20F, 0.50F)
                .texOffs(75, 66)
                .addBox(-2.35F, 15.55F, -3.06F, 4.70F, 2.05F, 0.42F);

        // Three raised shell seams are actual geometry, not flat color placeholders.
        body.texOffs(100, 66).addBox(-3.35F, 1.65F, -2.98F, 6.70F, 0.20F, 0.36F);
        body.texOffs(100, 66).addBox(-3.45F, 7.20F, -3.04F, 6.90F, 0.20F, 0.36F);
        body.texOffs(100, 66).addBox(-3.52F, 8.55F, -3.10F, 7.04F, 0.20F, 0.36F);
        return body;
    }
}

