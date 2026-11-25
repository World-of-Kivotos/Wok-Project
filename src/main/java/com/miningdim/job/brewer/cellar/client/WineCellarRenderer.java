package com.miningdim.job.brewer.cellar.client;

import com.miningdim.job.brewer.cellar.WineCellarBlock;
import com.miningdim.job.brewer.cellar.WineCellarBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 酒窖展示渲染器。酒窖横向占两格，12 个酒槽按 3 行 x 4 列逐格显示。
 *
 * 酒瓶直接复用各酒物品的独立像素图标，并旋转为完整侧视横放。这样正面观察时能同时读出瓶口、瓶身、
 * 标签和瓶底，不会再像旧模型那样沿 Z 轴塞进柜体、最终只能看到瓶口。柜体本身提供亮背板，酒瓶使用
 * 满亮展示光照。这里的满亮仅影响柜内物品模型的可读性，不向世界额外发光；用于避免物品模型面光照在柜体
 * 内凹空间里再次把酒瓶压暗。
 */
public final class WineCellarRenderer implements BlockEntityRenderer<WineCellarBlockEntity> {

    private static final int COLUMNS = 4;
    private static final float FIRST_COLUMN_X = -0.25F;
    private static final float COLUMN_SPACING = 0.50F;
    private static final float TOP_ROW_Y = 0.815F;
    private static final float ROW_SPACING = 0.305F;
    private static final float DISPLAY_Z = -0.438F;
    private static final float BOTTLE_SCALE = 0.78F;

    private final ItemRenderer itemRenderer;

    public WineCellarRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(WineCellarBlockEntity cellar, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (cellar.getBlockState().getValue(WineCellarBlock.PART) == WineCellarBlock.Part.LEGACY) {
            return;
        }
        Direction facing = cellar.getBlockState().getValue(WineCellarBlock.FACING);

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(rotationFor(facing)));

        for (int slot = 0; slot < WineCellarBlockEntity.WINE_SLOTS; slot++) {
            ItemStack stack = cellar.displayedBottle(slot);
            if (stack.isEmpty()) {
                continue;
            }
            renderBottle(cellar, slot, stack, poseStack, buffers);
        }

        poseStack.popPose();
    }

    private void renderBottle(WineCellarBlockEntity cellar, int slot, ItemStack stack,
                              PoseStack poseStack, MultiBufferSource buffers) {
        int row = slot / COLUMNS;
        int column = slot % COLUMNS;

        poseStack.pushPose();
        poseStack.translate(
                FIRST_COLUMN_X + column * COLUMN_SPACING,
                TOP_ROW_Y - row * ROW_SPACING,
                DISPLAY_Z);

        // 相邻格交替瓶口方向，避免 12 个图标像整齐复制的 UI；仍保持完整侧视轮廓。
        poseStack.mulPose(Axis.ZP.rotationDegrees((slot & 1) == 0 ? 90.0F : -90.0F));
        poseStack.scale(BOTTLE_SCALE, BOTTLE_SCALE, BOTTLE_SCALE);

        itemRenderer.renderStatic(
                stack,
                ItemDisplayContext.FIXED,
                LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY,
                poseStack,
                buffers,
                cellar.getLevel(),
                slot);
        poseStack.popPose();
    }

    private static float rotationFor(Direction facing) {
        return switch (facing) {
            case EAST -> -90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 90.0F;
            default -> 0.0F;
        };
    }
}
