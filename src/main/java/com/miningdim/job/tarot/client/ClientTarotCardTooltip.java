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

    private static final int CARD_WIDTH = 84;
    private static final int CARD_HEIGHT = 149;
    private static final int CARD_TEXTURE_WIDTH = 184;
    private static final int CARD_TEXTURE_HEIGHT = 326;

    /*
     * The item frame lives on a 256x256 canvas around a 140x248 card. Crop only
     * the unused side gutters, then scale at the same factor as the preview card.
     * This preserves the SSR crystals and Shiny halo without making the tooltip
     * as wide as the complete square item atlas.
     */
    private static final int FRAME_TEXTURE_SIZE = 256;
    private static final int FRAME_SOURCE_X = 24;
    private static final int FRAME_SOURCE_WIDTH = 208;
    private static final int WIDTH = 125;
    private static final int HEIGHT = 154;
    private static final int CARD_X = 20;
    private static final int CARD_Y = 2;

    private final ResourceLocation texture;
    private final ResourceLocation frameTexture;
    private final TarotQuality quality;
    private final boolean upright;

    public ClientTarotCardTooltip(TarotCardTooltip tooltip) {
        String id = tooltip.cardId() < 10 ? "0" + tooltip.cardId() : Integer.toString(tooltip.cardId());
        this.texture = new ResourceLocation(MiningConstants.MODID, "textures/gui/tarot/cards/" + id + ".png");
        this.quality = tooltip.quality();
        this.frameTexture = new ResourceLocation(MiningConstants.MODID,
                "textures/item/tarot/border_" + quality.id()
                        + (tooltip.upright() ? "" : "_reversed") + ".png");
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
        graphics.fill(x + CARD_X - 3, y + CARD_Y - 3,
                x + CARD_X + CARD_WIDTH + 3, y + CARD_Y + CARD_HEIGHT + 3,
                0xE0101B32);
        graphics.fill(x + CARD_X - 1, y + CARD_Y - 1,
                x + CARD_X + CARD_WIDTH + 1, y + CARD_Y + CARD_HEIGHT + 1,
                color);

        graphics.pose().pushPose();
        if (!upright) {
            graphics.pose().translate(
                    x * 2.0F + CARD_X * 2.0F + CARD_WIDTH,
                    y * 2.0F + CARD_Y * 2.0F + CARD_HEIGHT,
                    0.0F);
            graphics.pose().mulPose(Axis.ZP.rotationDegrees(180.0F));
        }
        graphics.blit(texture, x + CARD_X, y + CARD_Y, CARD_WIDTH, CARD_HEIGHT,
                0.0F, 0.0F, CARD_TEXTURE_WIDTH, CARD_TEXTURE_HEIGHT,
                CARD_TEXTURE_WIDTH, CARD_TEXTURE_HEIGHT);
        graphics.pose().popPose();

        graphics.blit(frameTexture, x, y, WIDTH, HEIGHT,
                (float) FRAME_SOURCE_X, 0.0F,
                FRAME_SOURCE_WIDTH, FRAME_TEXTURE_SIZE,
                FRAME_TEXTURE_SIZE, FRAME_TEXTURE_SIZE);
    }

    private static int qualityColor(TarotQuality quality) {
        return switch (quality) {
            case R -> 0xFFF0F7FF;
            case SR -> 0xFF347EFF;
            case SSR -> 0xFFA64FFF;
            case UR -> 0xFFFF69B8;
            case SHINY -> 0xFFFF313E;
        };
    }
}
