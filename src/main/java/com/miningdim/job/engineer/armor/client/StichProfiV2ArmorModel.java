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

/** Stich Profi V2 black carrier with twin magazines, side equipment and hanging utility pouch. */
public final class StichProfiV2ArmorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_stich_profi_v2_black"), "main");

    public StichProfiV2ArmorModel(ModelPart root) { super(root); }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("body", body(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder body() {
        return CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.40F, 0.70F, -2.43F, 6.80F, 6.10F, 0.45F)
                .texOffs(16, 0).addBox(-3.40F, 0.70F, 1.98F, 6.80F, 6.10F, 0.45F)
                .texOffs(32, 0).addBox(-4.00F, 4.70F, -2.00F, 0.40F, 6.40F, 4.00F)
                .texOffs(42, 0).addBox(3.60F, 4.70F, -2.00F, 0.40F, 6.40F, 4.00F)
                .texOffs(52, 0).addBox(-3.45F, -0.20F, -2.40F, 1.20F, 1.05F, 4.80F)
                .texOffs(65, 0).addBox(2.25F, -0.20F, -2.40F, 1.20F, 1.05F, 4.80F)
                .texOffs(78, 0).addBox(-3.90F, 6.60F, -2.48F, 7.80F, 2.50F, 0.48F)
                .texOffs(96, 0).addBox(-3.90F, 6.60F, 2.00F, 7.80F, 2.50F, 0.48F)
                .texOffs(114, 0).addBox(-1.85F, 5.50F, -3.07F, 1.80F, 4.10F, 0.72F)
                .texOffs(121, 0).addBox(0.05F, 5.50F, -3.07F, 1.80F, 4.10F, 0.72F)
                .texOffs(0, 12).addBox(-3.94F, 5.90F, -3.15F, 1.55F, 4.40F, 0.80F)
                .texOffs(6, 12).addBox(2.94F, 4.80F, -3.00F, 1.00F, 4.80F, 0.65F)
                .texOffs(11, 12).addBox(3.72F, 0.40F, -2.70F, 0.16F, 5.00F, 0.16F)
                .texOffs(13, 12).addBox(-2.40F, 9.00F, -3.18F, 4.80F, 3.80F, 0.75F)
                .texOffs(26, 12).addBox(-3.10F, 3.35F, -2.53F, 6.20F, 0.30F, 0.16F);
    }
}
