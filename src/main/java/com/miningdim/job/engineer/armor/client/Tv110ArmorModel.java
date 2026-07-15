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

/** Wartech TV-110 coyote carrier with three broad front pouches and a compact radio. */
public final class Tv110ArmorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(MiningConstants.MODID, "plate_armor_tv110_coyote"), "main");

    public Tv110ArmorModel(ModelPart root) { super(root); }

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
                .texOffs(0, 0).addBox(-3.40F, 0.70F, -2.43F, 6.80F, 6.20F, 0.45F)
                .texOffs(16, 0).addBox(-3.40F, 0.70F, 1.98F, 6.80F, 6.20F, 0.45F)
                .texOffs(32, 0).addBox(-4.00F, 4.80F, -2.00F, 0.42F, 6.20F, 4.00F)
                .texOffs(42, 0).addBox(3.58F, 4.80F, -2.00F, 0.42F, 6.20F, 4.00F)
                .texOffs(52, 0).addBox(-3.45F, -0.20F, -2.40F, 1.25F, 1.05F, 4.80F)
                .texOffs(66, 0).addBox(2.20F, -0.20F, -2.40F, 1.25F, 1.05F, 4.80F)
                .texOffs(80, 0).addBox(-3.90F, 6.65F, -2.45F, 7.80F, 2.10F, 0.45F)
                .texOffs(98, 0).addBox(-3.90F, 6.65F, 2.00F, 7.80F, 2.10F, 0.45F)
                .texOffs(116, 0).addBox(-3.94F, 5.90F, -3.16F, 2.00F, 4.10F, 0.78F)
                .texOffs(0, 12).addBox(-1.85F, 5.90F, -3.16F, 2.00F, 4.10F, 0.78F)
                .texOffs(7, 12).addBox(0.25F, 5.70F, -3.05F, 1.40F, 4.30F, 0.70F)
                .texOffs(13, 12).addBox(3.14F, 5.15F, -3.00F, 0.80F, 4.70F, 0.62F)
                .texOffs(17, 12).addBox(-3.15F, 3.30F, -2.53F, 6.30F, 0.30F, 0.16F);
    }
}
