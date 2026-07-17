package com.miningdim.job.engineer.shield.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.shield.PlasmaShieldVariant;
import com.miningdim.job.engineer.shield.PlasmaShieldVisualProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.EnumMap;
import java.util.Map;

/** Compact plasma-shield charge and heat display placed immediately above the player health row. */
public final class PlasmaShieldHudOverlay {

    public static final IGuiOverlay INSTANCE = PlasmaShieldHudOverlay::render;

    private static final int HUD_WIDTH = 81;
    private static final int HUD_HEIGHT = 14;
    private static final int ICON_SIZE = 14;
    private static final int BAR_WIDTH = 66;
    private static final int MAIN_BAR_HEIGHT = 9;
    private static final int HEAT_BAR_HEIGHT = 4;
    private static final int BAR_INNER_WIDTH = BAR_WIDTH - 2;

    private static final Map<PlasmaShieldVariant, ResourceLocation> VARIANT_TEXTURES =
            createVariantTextures();

    private PlasmaShieldHudOverlay() {
    }

    private static void render(ForgeGui gui,
                               GuiGraphics graphics,
                               float partialTick,
                               int screenWidth,
                               int screenHeight) {
        Minecraft minecraft = gui.getMinecraft();
        ClientPlasmaShieldState.Snapshot state = ClientPlasmaShieldState.snapshot();
        if (!state.active()
                || minecraft.options.hideGui
                || minecraft.player == null
                || minecraft.level == null
                || !gui.shouldDrawSurvivalElements()) {
            return;
        }

        gui.setupOverlayRenderState(true, false);

        int left = screenWidth / 2 - 91;
        // Forge's vanilla rows are nine pixels tall with a ten-pixel reservation.  This HUD is
        // fourteen pixels tall, so move its top up by five pixels before reserving fifteen.
        int top = screenHeight - gui.leftHeight - (HUD_HEIGHT - MAIN_BAR_HEIGHT);
        gui.leftHeight += HUD_HEIGHT + 1;

        graphics.blit(textureFor(state.variant()), left, top, ICON_SIZE, ICON_SIZE,
                0.0F, 0.0F, 64, 64, 64, 64);

        int barLeft = left + HUD_WIDTH - BAR_WIDTH;
        float shieldRatio = ratio(state.shield(), state.maxShield());
        float heatRatio = ratio(state.heat(), state.maxHeat());
        int shieldPixels = filledPixels(shieldRatio);
        int heatPixels = filledPixels(heatRatio);

        drawHeatBar(graphics, minecraft, state, barLeft, top, heatRatio, heatPixels);
        int shieldTop = top + HEAT_BAR_HEIGHT + 1;
        drawShieldBar(graphics, state, barLeft, shieldTop, shieldPixels);
        drawStatusText(graphics, minecraft.font, state, barLeft, shieldTop);
    }

    private static void drawShieldBar(GuiGraphics graphics,
                                      ClientPlasmaShieldState.Snapshot state,
                                      int x,
                                      int y,
                                      int filled) {
        graphics.fill(x, y, x + BAR_WIDTH, y + MAIN_BAR_HEIGHT, 0xE6000000);
        graphics.fill(x + 1, y + 1, x + BAR_WIDTH - 1, y + MAIN_BAR_HEIGHT - 1,
                state.overheated() ? 0xFF292B30 : 0xFF10232D);
        if (filled <= 0) {
            return;
        }

        PlasmaShieldVisualProfile.Style style = PlasmaShieldVisualProfile.style(state.variant());
        int colour = state.overheated() ? 0xFF777D84 : 0xFF000000 | style.primaryRgb();
        int highlight = state.overheated() ? 0xFFAEB3B8 : 0xFF000000 | style.highlightRgb();
        graphics.fill(x + 1, y + 1, x + 1 + filled, y + MAIN_BAR_HEIGHT - 1, colour);
        graphics.fill(x + 1, y + 1, x + 1 + filled, y + 2, highlight);
    }

    private static void drawHeatBar(GuiGraphics graphics,
                                    Minecraft minecraft,
                                    ClientPlasmaShieldState.Snapshot state,
                                    int x,
                                    int y,
                                    float heatRatio,
                                    int filled) {
        graphics.fill(x, y, x + BAR_WIDTH, y + HEAT_BAR_HEIGHT, 0xE6000000);
        graphics.fill(x + 1, y + 1, x + BAR_WIDTH - 1, y + HEAT_BAR_HEIGHT - 1,
                0xFF281914);
        if (filled <= 0) {
            return;
        }

        boolean pulse = ((minecraft.level.getGameTime() / 4L) & 1L) == 0L;
        int colour;
        if (state.overheated()) {
            colour = pulse ? 0xFFF13A2F : 0xFFFF8B2D;
        } else if (heatRatio >= 0.75F) {
            colour = pulse ? 0xFFFF5A32 : 0xFFFFA12E;
        } else {
            colour = 0xFFE6782D;
        }
        graphics.fill(x + 1, y + 1, x + 1 + filled, y + HEAT_BAR_HEIGHT - 1, colour);
    }

    private static void drawStatusText(GuiGraphics graphics,
                                       Font font,
                                       ClientPlasmaShieldState.Snapshot state,
                                       int barLeft,
                                       int top) {
        Component text = state.overheated()
                ? Component.translatable("hud.miningdim.plasma_shield.shutdown")
                : Component.literal(Math.round(state.shield()) + "/" + Math.round(state.maxShield()));
        int textX = barLeft + Math.max(1, (BAR_WIDTH - font.width(text)) / 2);
        graphics.drawString(font, text, textX, top, 0xFFFFFFFF, false);
    }

    private static int filledPixels(float ratio) {
        if (ratio <= 0.0F) {
            return 0;
        }
        return Math.max(1, Math.min(BAR_INNER_WIDTH, Math.round(ratio * BAR_INNER_WIDTH)));
    }

    private static float ratio(float value, float maximum) {
        if (!(maximum > 0.0F)) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, value / maximum));
    }

    private static ResourceLocation textureFor(PlasmaShieldVariant variant) {
        return VARIANT_TEXTURES.get(variant);
    }

    private static Map<PlasmaShieldVariant, ResourceLocation> createVariantTextures() {
        Map<PlasmaShieldVariant, ResourceLocation> textures =
                new EnumMap<>(PlasmaShieldVariant.class);
        for (PlasmaShieldVariant variant : PlasmaShieldVariant.values()) {
            textures.put(variant, new ResourceLocation(
                    MiningConstants.MODID, "textures/item/" + variant.itemId() + ".png"));
        }
        return Map.copyOf(textures);
    }
}
