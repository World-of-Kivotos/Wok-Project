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

/** Tapered HighCom Trooper TFO soft carrier with a clean chest patch and no pouches. */
public final class TrooperTfoArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_trooper_tfo_multicam"), "main");

    public TrooperTfoArmorModel(ModelPart root) {
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
                .texOffs(0, 0)
                .addBox(-3.10F, 0.80F, -2.38F, 6.20F, 4.20F, 0.38F)
                .texOffs(25, 0)
                .addBox(-3.70F, 4.95F, -2.42F, 7.40F, 6.35F, 0.42F)
                .texOffs(50, 0)
                .addBox(-3.10F, 0.80F, 2.0F, 6.20F, 4.20F, 0.38F)
                .texOffs(75, 0)
                .addBox(-3.70F, 4.95F, 2.0F, 7.40F, 6.35F, 0.42F)
                .texOffs(100, 0)
                .addBox(-3.98F, 3.0F, -2.05F, 0.38F, 8.27F, 4.10F)
                .texOffs(100, 0)
                .addBox(3.60F, 3.0F, -2.05F, 0.38F, 8.27F, 4.10F)
                .texOffs(0, 22)
                .addBox(-3.25F, -0.12F, -2.43F, 1.25F, 3.90F, 0.42F)
                .texOffs(0, 22)
                .addBox(2.0F, -0.12F, -2.43F, 1.25F, 3.90F, 0.42F)
                .texOffs(25, 22)
                .addBox(-3.25F, -0.12F, 2.01F, 1.25F, 3.90F, 0.42F)
                .texOffs(25, 22)
                .addBox(2.0F, -0.12F, 2.01F, 1.25F, 3.90F, 0.42F)
                .texOffs(50, 22)
                .addBox(-3.22F, -0.34F, -2.05F, 1.19F, 0.50F, 4.10F)
                .texOffs(50, 22)
                .addBox(2.03F, -0.34F, -2.05F, 1.19F, 0.50F, 4.10F)
                .texOffs(75, 22)
                .addBox(-2.90F, 1.35F, -2.68F, 5.80F, 2.30F, 0.34F)
                .texOffs(75, 44)
                .addBox(-3.45F, 4.82F, -2.58F, 6.90F, 0.20F, 0.20F)
                .texOffs(75, 44)
                .addBox(-3.45F, 4.82F, 2.38F, 6.90F, 0.20F, 0.20F)
                .texOffs(0, 44)
                .addBox(-3.72F, 10.65F, -2.69F, 7.44F, 0.72F, 0.26F)
                .texOffs(0, 44)
                .addBox(-3.72F, 10.65F, 2.43F, 7.44F, 0.72F, 0.26F)
                .texOffs(25, 44)
                .addBox(-4.10F, 7.0F, -2.95F, 0.26F, 0.75F, 0.84F)
                .texOffs(25, 44)
                .addBox(3.84F, 7.0F, -2.95F, 0.26F, 0.75F, 0.84F)
                .texOffs(50, 44)
                .addBox(-0.12F, 4.98F, -2.70F, 0.24F, 5.55F, 0.24F)
                .texOffs(50, 44)
                .addBox(-0.12F, 4.98F, 2.46F, 0.24F, 5.55F, 0.24F);

        for (int row = 0; row < 4; row++) {
            body.texOffs(100, 22).addBox(-3.25F, 5.12F + row * 1.35F, -2.62F, 6.50F, 0.28F, 0.24F);
        }
        return body;
    }
}

