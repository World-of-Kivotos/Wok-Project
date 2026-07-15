package com.miningdim.job.engineer.armor.client;

import com.miningdim.core.MiningConstants;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/** Slim native block-model silhouette for the BNTI Kirasa-N vest. */
public final class KirasaNArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_kirasa_n_green"), "main");

    private static final CubeDeformation SLIM_CARRIER = new CubeDeformation(0.18F);

    public KirasaNArmorModel(ModelPart root) {
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
                .texOffs(0, 0)
                .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, SLIM_CARRIER)
                .texOffs(26, 0)
                .addBox(-3.85F, 0.70F, -2.62F, 7.70F, 10.80F, 0.38F)
                .texOffs(44, 0)
                .addBox(-3.85F, 0.70F, 2.24F, 7.70F, 10.80F, 0.38F)
                .texOffs(62, 0)
                .addBox(-4.38F, 0.70F, -2.05F, 0.40F, 10.0F, 4.10F)
                .texOffs(73, 0)
                .addBox(3.98F, 0.70F, -2.05F, 0.40F, 10.0F, 4.10F)
                .texOffs(0, 18)
                .addBox(-2.90F, -0.80F, -3.0F, 5.80F, 1.45F, 0.50F)
                .texOffs(14, 18)
                .addBox(-2.90F, -0.80F, 2.50F, 5.80F, 1.45F, 0.50F)
                .texOffs(28, 18)
                .addBox(-3.40F, -0.75F, -2.60F, 0.50F, 1.50F, 5.20F)
                .texOffs(41, 18)
                .addBox(2.90F, -0.75F, -2.60F, 0.50F, 1.50F, 5.20F)
                .texOffs(54, 18)
                .addBox(-0.15F, 1.40F, -2.86F, 0.28F, 8.90F, 0.14F)
                .texOffs(58, 18)
                .addBox(-0.20F, 1.25F, -2.98F, 3.80F, 2.0F, 0.22F)
                .texOffs(68, 18)
                .addBox(-4.05F, 10.90F, -2.86F, 8.10F, 0.55F, 0.22F);
    }
}
