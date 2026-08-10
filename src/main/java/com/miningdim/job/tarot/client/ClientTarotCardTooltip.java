package com.miningdim.job.tarot.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.TarotCardTooltip;
import com.miningdim.job.tarot.TarotQuality;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.resources.ResourceLocation;

/** 在物品 tooltip 内绘制 Git 原图的完整竖版卡面。 */
public final class ClientTarotCardTooltip implements ClientTooltipComponent {

    private static final int WIDTH = 84;
    private static final int HEIGHT = 149;
    private static final int TEXTURE_WIDTH = 184;
    private static final int TEXTURE_HEIGHT = 326;

    private final ResourceLocation texture;
    private final TarotQuality quality;
    private final boolean upright;

    public ClientTarotCardTooltip(TarotCardTooltip tooltip) {
        String id = tooltip.cardId() < 10 ? "0" + tooltip.cardId() : Integer.toString(tooltip.cardId());
        this.texture = new ResourceLocation(MiningConstants.MODID, "textures/gui/tarot/cards/" + id + ".png");
        this.quality = tooltip.quality();
        this.upright = tooltip.upright();
    }

    @Override
    public int getHeight() {
        return HEIGHT + 4;
    }

    @Override
    public int getWidth(Font font) {
        return WIDTH;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        int color = qualityColor(quality);
        graphics.fill(x - 2, y - 2, x + WIDTH + 2, y + HEIGHT + 2, 0xE0101B32);
        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, color);
        if (quality == TarotQuality.SHINY) {
            graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFFF7E88A);
        }
        graphics.pose().pushPose();
        if (!upright) {
            graphics.pose().translate(x + WIDTH, y + HEIGHT, 0.0F);
            graphics.pose().mulPose(Axis.ZP.rotationDegrees(180.0F));
            graphics.pose().translate(-x, -y, 0.0F);
        }
        graphics.blit(texture, x, y, WIDTH, HEIGHT,
                0.0F, 0.0F, TEXTURE_WIDTH, TEXTURE_HEIGHT,
                TEXTURE_WIDTH, TEXTURE_HEIGHT);
        graphics.pose().popPose();
    }

    private static int qualityColor(TarotQuality quality) {
        return switch (quality) {
            case R -> 0xFFE0E8F0;
            case SR -> 0xFF4CE2FF;
            case SSR -> 0xFFCB6EFF;
            case UR -> 0xFFFFAE34;
            case SHINY -> 0xFFFFF484;
        };
    }
}
