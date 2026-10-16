package com.miningdim.freshness.client;

import com.miningdim.freshness.FreshnessDisplay;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.IItemDecorator;

/**
 * 格子上的保鲜条与角落数字 (规格「客户端显示」的渲染一节)。
 *
 * 注册方式: {@code RegisterItemDecorationsEvent} 给**物品注册表里的每个物品**注册同一个装饰器, 于是
 * 快捷栏 HUD、背包、各种容器、粮仓、主 MOD 自绘界面全都自动生效 (装饰器返回 false: 不影响别的角标)。
 *
 * 画两样东西:
 * <ul>
 *   <li>顶部 1–2 像素的条: 轨道灰 + 彩色部分, 长度 = 剩余 ÷ 保质期;</li>
 *   <li>左上角半尺寸字体的数字: 5h / 2d / &lt;1h。</li>
 * </ul>
 *
 * 颜色阈值 24 / 6 小时与 tooltip 共用 {@link FreshnessDisplay} 里那一份常量, 因此条的颜色与 tooltip 的
 * 措辞不可能各说各话。数据一律来自 {@link FreshnessClientState} (同步下来的钟 + 保质期), 这里不做任何判定。
 */
public final class FreshnessItemDecorator implements IItemDecorator {

    /** 条高 (像素): 规格的 1–2 像素, 取 2 才在缩放后有得看。 */
    private static final int BAR_HEIGHT = 2;

    private static final int TRACK_COLOR = 0x50000000;
    private static final int FRESH_COLOR = 0xFF4CD964;
    private static final int WARN_COLOR = 0xFFFFC107;
    private static final int DANGER_COLOR = 0xFFFF3B30;
    private static final int ROTTEN_COLOR = 0xFF7A7A7A;

    @Override
    public boolean render(GuiGraphics graphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (stack == null || stack.isEmpty() || !FreshnessClientState.synced()) {
            return false;
        }
        FreshnessClientState.SlotDisplay display = FreshnessClientState.displayOf(stack);
        if (!display.drawable() && display.label().isEmpty()) {
            return false;
        }
        if (display.drawable()) {
            drawBar(graphics, xOffset, yOffset, display);
        }
        drawLabel(graphics, font, xOffset, yOffset, display);
        return false; // 不影响别的装饰器
    }

    private void drawBar(GuiGraphics graphics, int xOffset, int yOffset, FreshnessClientState.SlotDisplay display) {
        int width = (int) Math.round(16.0D * display.fraction());
        graphics.fill(xOffset, yOffset + 16 - BAR_HEIGHT, xOffset + 16, yOffset + 16, TRACK_COLOR);
        if (width > 0) {
            graphics.fill(xOffset, yOffset + 16 - BAR_HEIGHT, xOffset + width, yOffset + 16, colorOf(display.level()));
        }
    }

    /** 角落数字: 半尺寸字体画在左上角; 没有可显示的数字时什么都不画 (不占地方也不留空框)。 */
    private void drawLabel(GuiGraphics graphics, Font font, int xOffset, int yOffset,
                           FreshnessClientState.SlotDisplay display) {
        String label = display.label();
        if (label.isEmpty()) {
            return;
        }
        if (FreshnessDisplay.Level.ROTTEN == display.level()) {
            label = "rot";
        } else if (!FreshnessClientConfig.slotNumbersVisible(display.remainingMinutes())) {
            return;
        }
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(xOffset, yOffset, 200.0F);
        pose.scale(0.5F, 0.5F, 1.0F);
        graphics.drawString(font, label, 2, 2, colorOf(display.level()), true);
        pose.popPose();
    }

    private static int colorOf(FreshnessDisplay.Level level) {
        return switch (level) {
            case FRESH -> FRESH_COLOR;
            case WARN -> WARN_COLOR;
            case DANGER -> DANGER_COLOR;
            case ROTTEN -> ROTTEN_COLOR;
            case UNTIMED -> FRESH_COLOR;
        };
    }
}
