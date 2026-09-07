package com.miningdim.job.munitions.client;

import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public final class MunitionsBenchRenderer extends GeoBlockRenderer<MunitionsBenchBlockEntity> {

    private static final String CAROUSEL_BONE = "carousel";
    /**
     * 一台机器 386-398 个 cube, GeckoLib 逐顶点分配 Vector4f, 单台每帧约 9264 个顶点。默认 64 格视距下
     * 远处的机器连一个像素都占不满却仍然全量绘制, 这里收到 32 格 —— 仍远超机身两格的体量。
     */
    private static final int VIEW_DISTANCE = 32;

    public MunitionsBenchRenderer() {
        super(new MunitionsBenchGeoModel());
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    /**
     * 存量存档里的 LEGACY 台子仍由区块网格画老方块模型, 这里对应的是零 cube 的空骨骼模型, 整条 GeckoLib
     * 管线(取模型、跑动画控制器、建姿态矩阵)都是纯空转。在派发层直接判否, 比进去画个空模型便宜。
     */
    @Override
    public boolean shouldRender(MunitionsBenchBlockEntity animatable, Vec3 cameraPos) {
        return animatable.getBlockState().getValue(MunitionsBenchBlock.LAYOUT)
                != MunitionsBenchBlock.Layout.LEGACY_DEPTH
                && super.shouldRender(animatable, cameraPos);
    }

    @Override
    public void preRender(PoseStack poseStack, MunitionsBenchBlockEntity animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay,
                          float red, float green, float blue, float alpha) {
        if (animatable.getBlockState().getValue(MunitionsBenchBlock.LAYOUT)
                == MunitionsBenchBlock.Layout.WIDE) {
            // GeckoLib 把 bedrock 几何沿 X 取反后模型落在主格的 -X 侧, 这一步把它挪回主格加副格。
            // preRender 跑在 actuallyRender 的 translate(0.5,0,0.5)+rotateBlock 之前, 姿态栈还没旋转,
            // 所以这里用的世界方向步长是对的。
            Direction modelOffset = MunitionsBenchBlock.extensionDirection(animatable.getBlockState());
            poseStack.translate(modelOffset.getStepX(), 0.0D, modelOffset.getStepZ());
        }
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }

    /**
     * 弹盘角度自己管, 不走动画通道。理由见
     * {@link MunitionsBenchBlockEntity#advanceCarouselAngle(float)}: GeckoLib 的 adjustTick 是
     * {@code speed * (tick - tickOffset)}, 用速度当开关只会让弹盘在开停机时瞬间归零再瞬间跳走。
     */
    @Override
    public void renderRecursively(PoseStack poseStack, MunitionsBenchBlockEntity animatable, GeoBone bone,
                                  RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                                  boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                                  float red, float green, float blue, float alpha) {
        if (!isReRender && CAROUSEL_BONE.equals(bone.getName())) {
            bone.setRotY(animatable.carouselAngleDegrees(partialTick) * Mth.DEG_TO_RAD);
        }
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
