package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.gunsmith.GunsmithFactionTooltip;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** 绘制组件型号稀有度名牌，并在右侧保留可选的制造势力 LOGO。 */
public final class ClientGunsmithFactionTooltip implements ClientTooltipComponent {

    private static final int TOOLTIP_MIN_WIDTH = 104;
    private static final int NAMEPLATE_WIDTH = 76;
    private static final int NAMEPLATE_HEIGHT = 20;
    private static final int NAMEPLATE_TEXTURE_WIDTH = 76;
    private static final int NAMEPLATE_TEXTURE_HEIGHT = 20;
    private static final int LABEL_MAX_WIDTH = 56;
    private static final int LOGO_SIZE = 24;
    private static final int LOGO_TEXTURE_SIZE = 160;
    private static final int LOGO_X = 80;
    private static final int LOGO_Y = 0;

    private final ResourceLocation nameplateTexture;
    private final Component label;
    private final int textColor;
    @Nullable
    private final ResourceLocation factionTexture;

    public ClientGunsmithFactionTooltip(GunsmithFactionTooltip tooltip) {
        this.nameplateTexture = new ResourceLocation(MiningConstants.MODID,
                "textures/gui/gunsmith/rarity/" + tooltip.rarity().id() + ".png");
        this.label = Component.translatable(tooltip.rarity().labelKey())
                .withStyle(ChatFormatting.BOLD);
        this.textColor = tooltip.rarity().textColor();
        this.factionTexture = tooltip.faction() == null ? null : new ResourceLocation(
                MiningConstants.MODID,
                "textures/gui/gunsmith/factions/" + tooltip.faction().id() + ".png");
    }

    @Override
    public int getHeight() {
        return (factionTexture == null ? NAMEPLATE_HEIGHT : LOGO_SIZE) + 2;
    }

    @Override
    public int getWidth(Font font) {
        return factionTexture == null ? NAMEPLATE_WIDTH : TOOLTIP_MIN_WIDTH;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        int nameplateY = y + (factionTexture == null ? 0 : (LOGO_SIZE - NAMEPLATE_HEIGHT) / 2);
        graphics.blit(nameplateTexture, x, nameplateY, NAMEPLATE_WIDTH, NAMEPLATE_HEIGHT,
                0.0F, 0.0F, NAMEPLATE_TEXTURE_WIDTH, NAMEPLATE_TEXTURE_HEIGHT,
                NAMEPLATE_TEXTURE_WIDTH, NAMEPLATE_TEXTURE_HEIGHT);

        int textWidth = font.width(label);
        float labelScale = Math.min(1.0F, LABEL_MAX_WIDTH / (float) Math.max(1, textWidth));
        graphics.pose().pushPose();
        graphics.pose().translate(x + NAMEPLATE_WIDTH / 2.0F,
                nameplateY + NAMEPLATE_HEIGHT / 2.0F, 0.0F);
        graphics.pose().scale(labelScale, labelScale, 1.0F);
        graphics.drawString(font, label, -textWidth / 2, -font.lineHeight / 2,
                textColor, true);
        graphics.pose().popPose();

        if (factionTexture != null) {
            Minecraft.getInstance().getTextureManager().getTexture(factionTexture)
                    .setFilter(true, false);
            graphics.blit(factionTexture, x + LOGO_X, y + LOGO_Y, LOGO_SIZE, LOGO_SIZE,
                0.0F, 0.0F, LOGO_TEXTURE_SIZE, LOGO_TEXTURE_SIZE,
                LOGO_TEXTURE_SIZE, LOGO_TEXTURE_SIZE);
        }
    }
}
