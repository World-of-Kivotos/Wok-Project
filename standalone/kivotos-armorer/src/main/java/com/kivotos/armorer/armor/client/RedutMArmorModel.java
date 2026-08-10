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

/** Thick, smooth Redut-M armor with a soft padded collar and layered front skirt. */
public final class RedutMArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(ArmorerMod.MODID, "plate_armor_redut_m"), "main");

    public RedutMArmorModel(ModelPart root) {
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
        CubeListBuilder body = CubeListBuilder.create()
                // Heavy uninterrupted front and rear courses preserve Redut-M's smooth silhouette.
                .texOffs(0, 0)
                .addBox(-3.48F, 0.48F, -2.58F, 6.96F, 3.30F, 0.56F)
                .texOffs(25, 0)
                .addBox(-3.82F, 3.58F, -2.66F, 7.64F, 4.18F, 0.64F)
                .texOffs(50, 0)
                .addBox(-3.92F, 7.52F, -2.72F, 7.84F, 3.68F, 0.70F)
                .texOffs(75, 0)
                .addBox(-3.48F, 0.48F, 2.02F, 6.96F, 3.30F, 0.56F)
                .texOffs(100, 0)
                .addBox(-3.82F, 3.58F, 2.02F, 7.64F, 4.18F, 0.64F)
                .texOffs(0, 22)
                .addBox(-3.92F, 7.52F, 2.04F, 7.84F, 3.68F, 0.70F)
                .texOffs(25, 22)
                .addBox(-3.98F, 3.22F, -2.10F, 0.38F, 7.90F, 4.20F)
                .texOffs(25, 22)
                .addBox(3.60F, 3.22F, -2.10F, 0.38F, 7.90F, 4.20F)

                // Five shallow soft pads form a collar rather than a rigid oversized ring.
                .texOffs(50, 22)
                .addBox(-3.65F, -1.10F, -3.72F, 3.25F, 1.55F, 0.42F)
                .texOffs(50, 22)
                .addBox(0.40F, -1.10F, -3.72F, 3.25F, 1.55F, 0.42F)
                .texOffs(75, 22)
                .addBox(-3.65F, -1.10F, 3.30F, 7.30F, 1.55F, 0.42F)
                .texOffs(100, 22)
                .addBox(-3.95F, -1.07F, -3.30F, 0.40F, 1.50F, 6.60F)
                .texOffs(100, 22)
                .addBox(3.55F, -1.07F, -3.30F, 0.40F, 1.50F, 6.60F)
                .texOffs(0, 44)
                .addBox(-3.22F, 0.31F, -3.30F, 2.62F, 0.44F, 1.30F)
                .texOffs(0, 44)
                .addBox(0.60F, 0.31F, -3.30F, 2.62F, 0.44F, 1.30F)
                .texOffs(0, 44)
                .addBox(-3.22F, 0.31F, 2.00F, 2.62F, 0.44F, 1.30F)
                .texOffs(0, 44)
                .addBox(0.60F, 0.31F, 2.00F, 2.62F, 0.44F, 1.30F)

                // A broad padded belt wraps the shell without adding any external pouches.
                .texOffs(25, 44)
                .addBox(-3.98F, 9.84F, -2.98F, 7.96F, 1.28F, 0.42F)
                .texOffs(50, 44)
                .addBox(-3.98F, 9.84F, 2.56F, 7.96F, 1.28F, 0.42F)
                .texOffs(75, 44)
                .addBox(-3.95F, 9.80F, -2.24F, 0.39F, 1.25F, 4.48F)
                .texOffs(75, 44)
                .addBox(3.56F, 9.80F, -2.24F, 0.39F, 1.25F, 4.48F)

                // A short waist flap opens into a soft skirt that grows wider toward its blunt hem.
                .texOffs(100, 44)
                .addBox(-3.25F, 10.30F, -3.12F, 6.50F, 1.35F, 0.50F)
                .texOffs(0, 66)
                .addBox(-2.75F, 11.48F, -3.20F, 5.50F, 2.25F, 0.46F)
                .texOffs(25, 66)
                .addBox(-3.20F, 13.55F, -3.27F, 6.40F, 2.45F, 0.43F)
                .texOffs(50, 66)
                .addBox(-2.90F, 15.82F, -3.20F, 5.80F, 1.55F, 0.38F)

                // Purple-brown shoulder straps and the small blue chest clasp match the reference trim.
                .texOffs(75, 66)
                .addBox(-3.15F, 0.50F, -2.96F, 1.10F, 2.85F, 0.34F)
                .texOffs(75, 66)
                .addBox(2.05F, 0.50F, -2.96F, 1.10F, 2.85F, 0.34F)
                .texOffs(100, 66)
                .addBox(-0.28F, 1.55F, -2.95F, 0.56F, 0.56F, 0.35F);

        // Fine stitching lives in the coating, avoiding the old stack of raised horizontal rails.
        return body;
    }
}

