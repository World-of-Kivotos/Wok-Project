package com.miningdim.job.munitions.client;

import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public final class MunitionsBenchRenderer extends GeoBlockRenderer<MunitionsBenchBlockEntity> {

    public MunitionsBenchRenderer() {
        super(new MunitionsBenchGeoModel());
    }

    @Override
    public void preRender(PoseStack poseStack, MunitionsBenchBlockEntity animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay,
                          float red, float green, float blue, float alpha) {
        if (animatable.getBlockState().getValue(MunitionsBenchBlock.LAYOUT)
                == MunitionsBenchBlock.Layout.WIDE) {
            Direction modelOffset = MunitionsBenchBlock.extensionDirection(animatable.getBlockState());
            poseStack.translate(modelOffset.getStepX(), 0.0D, modelOffset.getStepZ());
        }
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
