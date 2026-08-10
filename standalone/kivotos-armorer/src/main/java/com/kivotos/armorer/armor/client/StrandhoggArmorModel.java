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

/** Open-sided FirstSpear Strandhogg carrier shared by its green and black-camouflage variants. */
public final class StrandhoggArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_strandhogg"), "main");

    public StrandhoggArmorModel(ModelPart root) {
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
                .addBox(-3.30F, 0.70F, -2.48F, 6.60F, 4.0F, 0.48F)
                .texOffs(25, 0)
                .addBox(-3.70F, 4.65F, -2.55F, 7.40F, 5.75F, 0.55F)
                .texOffs(50, 0)
                .addBox(-3.30F, 0.70F, 2.0F, 6.60F, 4.0F, 0.48F)
                .texOffs(75, 0)
                .addBox(-3.70F, 4.65F, 2.0F, 7.40F, 5.75F, 0.55F)
                .texOffs(100, 0)
                .addBox(-4.10F, 3.10F, -2.05F, 0.45F, 7.27F, 4.10F)
                .texOffs(100, 0)
                .addBox(3.65F, 3.10F, -2.05F, 0.45F, 7.27F, 4.10F)
                .texOffs(0, 22)
                .addBox(-3.35F, -0.15F, -2.53F, 1.30F, 3.90F, 0.50F)
                .texOffs(0, 22)
                .addBox(2.05F, -0.15F, -2.53F, 1.30F, 3.90F, 0.50F)
                .texOffs(25, 22)
                .addBox(-3.35F, -0.15F, 2.03F, 1.30F, 3.90F, 0.50F)
                .texOffs(25, 22)
                .addBox(2.05F, -0.15F, 2.03F, 1.30F, 3.90F, 0.50F)
                .texOffs(50, 22)
                .addBox(-3.32F, -0.38F, -2.08F, 1.24F, 0.55F, 4.16F)
                .texOffs(50, 22)
                .addBox(2.08F, -0.38F, -2.08F, 1.24F, 0.55F, 4.16F)
                .texOffs(25, 44)
                .addBox(-4.65F, 5.20F, -1.35F, 0.60F, 3.70F, 2.70F)
                .texOffs(50, 44)
                .addBox(4.05F, 5.40F, -1.45F, 0.60F, 3.30F, 2.90F)
                .texOffs(75, 44)
                .addBox(-2.30F, 9.95F, -2.95F, 4.60F, 3.85F, 0.48F)
                .texOffs(100, 44)
                .addBox(-4.22F, 4.80F, -2.38F, 0.30F, 1.10F, 0.76F)
                .texOffs(100, 44)
                .addBox(3.92F, 4.80F, -2.38F, 0.30F, 1.10F, 0.76F)
                .texOffs(0, 66)
                .addBox(-3.74F, 9.45F, -2.78F, 7.48F, 0.90F, 0.30F)
                .texOffs(0, 66)
                .addBox(-3.74F, 9.45F, 2.48F, 7.48F, 0.90F, 0.30F);

        for (int row = 0; row < 5; row++) {
            body.texOffs(75, 22).addBox(-2.95F, 1.35F + row * 0.58F, -2.68F, 5.90F, 0.16F, 0.22F);
        }
        for (int column = 0; column < 3; column++) {
            float x = -2.90F + column * 2.05F;
            body.texOffs(100, 22).addBox(x, 5.25F, -3.08F, 1.80F, 4.80F, 0.58F);
            body.texOffs(0, 44).addBox(x - 0.03F, 4.32F, -3.20F, 1.86F, 1.05F, 0.24F);
        }
        return body;
    }
}

