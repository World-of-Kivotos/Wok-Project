package com.kivotos.armorer.armor.client;

import com.kivotos.armorer.ArmorerMod;
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

/** Shared native geometry for both Kora-Kulon color variants. */
public final class KoraKulonArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_kora_kulon"), "main");

    private static final CubeDeformation SOFT_CARRIER = new CubeDeformation(0.34F);

    public KoraKulonArmorModel(ModelPart root) {
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
                .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, SOFT_CARRIER)
                .texOffs(26, 0)
                .addBox(-3.90F, 0.70F, -2.95F, 7.80F, 8.80F, 0.55F)
                .texOffs(44, 0)
                .addBox(-3.90F, 0.70F, 2.40F, 7.80F, 8.80F, 0.55F)
                .texOffs(62, 0)
                .addBox(-4.55F, 0.90F, -2.20F, 0.65F, 10.0F, 4.40F)
                .texOffs(74, 0)
                .addBox(3.90F, 0.90F, -2.20F, 0.65F, 10.0F, 4.40F)
                .texOffs(0, 18)
                .addBox(-4.0F, -0.30F, -3.20F, 1.80F, 4.50F, 0.45F)
                .texOffs(6, 18)
                .addBox(2.20F, -0.30F, -3.20F, 1.80F, 4.50F, 0.45F)
                .texOffs(12, 18)
                .addBox(-4.0F, -0.30F, 2.75F, 1.80F, 4.50F, 0.45F)
                .texOffs(18, 18)
                .addBox(2.20F, -0.30F, 2.75F, 1.80F, 4.50F, 0.45F)
                .texOffs(24, 18)
                .addBox(-4.15F, -0.35F, -2.50F, 1.90F, 0.70F, 5.0F)
                .texOffs(39, 18)
                .addBox(2.25F, -0.35F, -2.50F, 1.90F, 0.70F, 5.0F)
                .texOffs(54, 18)
                .addBox(-4.40F, 7.0F, -3.40F, 8.80F, 3.20F, 0.65F)
                .texOffs(74, 18)
                .addBox(-4.40F, 7.0F, 2.75F, 8.80F, 3.20F, 0.65F)
                .texOffs(94, 18)
                .addBox(-4.65F, 7.0F, -2.45F, 0.75F, 3.20F, 4.90F)
                .texOffs(107, 18)
                .addBox(3.90F, 7.0F, -2.45F, 0.75F, 3.20F, 4.90F)
                .texOffs(0, 30)
                .addBox(0.60F, 7.30F, -3.75F, 2.20F, 2.60F, 0.30F)
                .texOffs(7, 30)
                .addBox(-3.70F, 10.30F, -2.85F, 7.40F, 2.10F, 0.40F);
    }
}

