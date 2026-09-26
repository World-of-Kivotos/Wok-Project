package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.gunsmith.GunsmithFactionTooltip;
import com.miningdim.job.munitions.gunsmith.GunsmithPartRarity;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 绘制组件型号稀有度名牌（学园标签、整牌发光、五档特效阶梯），并在右侧保留可选的制造势力 LOGO。
 * 名牌像素由 {@link GunsmithNameplatePainter} 逐帧算出，写进一张小动态贴图后按一比一绘制；文字仍由
 * 本地化键动态绘制。
 */
public final class ClientGunsmithFactionTooltip implements ClientTooltipComponent {

    private static final int LOGO_SIZE = 24;
    private static final int LOGO_TEXTURE_SIZE = 160;
    private static final int LOGO_GAP = 4;
    /** 同一名牌两帧间隔超过该值即视为重新悬停，动效从头播放。 */
    private static final long HOVER_RESET_MILLIS = 250L;

    /** 按“稀有度 + 文字宽度”复用的动态贴图；切换语言只会多出几张，数量有上限。 */
    private static final Map<String, NameplateTexture> TEXTURES = new HashMap<>();
    @Nullable
    private static GunsmithFactionTooltip hovered;
    private static long hoverStartMillis;
    private static long hoverLastSeenMillis;

    private final GunsmithFactionTooltip tooltip;
    private final Component label;
    private final int labelColor;
    @Nullable
    private final ResourceLocation factionTexture;

    public ClientGunsmithFactionTooltip(GunsmithFactionTooltip tooltip) {
        this.tooltip = tooltip;
        this.label = Component.translatable(tooltip.rarity().labelKey())
                .withStyle(ChatFormatting.BOLD);
        this.labelColor = GunsmithNameplatePainter.labelColor(tooltip.rarity());
        this.factionTexture = tooltip.faction() == null ? null : new ResourceLocation(
                MiningConstants.MODID,
                "textures/gui/gunsmith/factions/" + tooltip.faction().id() + ".png");
    }

    @Override
    public int getHeight() {
        // 无 LOGO 时多留 2 像素，让向上外溢的光晕不碰到物品名
        return (factionTexture == null ? GunsmithNameplatePainter.TAG_HEIGHT + 2 : LOGO_SIZE) + 2;
    }

    @Override
    public int getWidth(Font font) {
        int tagWidth = GunsmithNameplatePainter.tagWidth(font.width(label));
        return factionTexture == null ? tagWidth : tagWidth + LOGO_GAP + LOGO_SIZE;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        int labelWidth = font.width(label);
        int tagY = y + (factionTexture == null ? 1 : (LOGO_SIZE - GunsmithNameplatePainter.TAG_HEIGHT) / 2);
        GunsmithPartRarity rarity = tooltip.rarity();
        NameplateTexture texture = TEXTURES.computeIfAbsent(rarity.id() + "_" + labelWidth,
                key -> new NameplateTexture(key, labelWidth));
        texture.update(rarity, labelWidth, elapsedMillis());

        int margin = GunsmithNameplatePainter.MARGIN;
        graphics.blit(texture.location, x - margin, tagY - margin, 0.0F, 0.0F,
                texture.width, texture.height, texture.width, texture.height);
        graphics.drawString(font, label, x + GunsmithNameplatePainter.LABEL_X,
                tagY + GunsmithNameplatePainter.LABEL_Y, labelColor, true);

        if (factionTexture != null) {
            Minecraft.getInstance().getTextureManager().getTexture(factionTexture)
                    .setFilter(true, false);
            graphics.blit(factionTexture, x + GunsmithNameplatePainter.tagWidth(labelWidth) + LOGO_GAP, y,
                    LOGO_SIZE, LOGO_SIZE, 0.0F, 0.0F, LOGO_TEXTURE_SIZE, LOGO_TEXTURE_SIZE,
                    LOGO_TEXTURE_SIZE, LOGO_TEXTURE_SIZE);
        }
    }

    /**
     * 自本次悬停开始经过的毫秒数；换了名牌或中断超过 {@link #HOVER_RESET_MILLIS} 即从头计时。
     * 玩家把“光效速度”（附魔光效）调到 0 时视为不想要动效，返回 -1 画静止态。
     */
    private long elapsedMillis() {
        long now = Util.getMillis();
        if (!tooltip.equals(hovered) || now - hoverLastSeenMillis > HOVER_RESET_MILLIS) {
            hoverStartMillis = now;
        }
        hovered = tooltip;
        hoverLastSeenMillis = now;
        if (Minecraft.getInstance().options.glintSpeed().get() <= 0.0D) {
            return -1L;
        }
        return now - hoverStartMillis;
    }

    private static final class NameplateTexture {
        final ResourceLocation location;
        final DynamicTexture texture;
        final int width;
        final int height;
        final int[] argb;
        boolean paintedStatic;

        NameplateTexture(String key, int labelWidth) {
            this.width = GunsmithNameplatePainter.imageWidth(labelWidth);
            this.height = GunsmithNameplatePainter.imageHeight();
            this.argb = new int[width * height];
            this.texture = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, width, height, true));
            this.location = new ResourceLocation(MiningConstants.MODID, "dynamic/gunsmith_nameplate/" + key);
            Minecraft.getInstance().getTextureManager().register(location, texture);
        }

        void update(GunsmithPartRarity rarity, int labelWidth, long elapsedMillis) {
            boolean still = elapsedMillis < 0L;
            if (still && paintedStatic) {
                return;
            }
            paintedStatic = still;
            GunsmithNameplatePainter.paint(rarity, labelWidth, elapsedMillis, argb);
            NativeImage image = texture.getPixels();
            if (image == null) {
                return;
            }
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int c = argb[y * width + x];
                    // NativeImage 按 ABGR 存储：交换红蓝通道
                    image.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c >>> 16) & 0xFF) | ((c & 0xFF) << 16));
                }
            }
            texture.upload();
        }
    }
}
