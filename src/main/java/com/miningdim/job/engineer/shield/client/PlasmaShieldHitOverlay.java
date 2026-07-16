package com.miningdim.job.engineer.shield.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.shield.PlasmaShieldVisualProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/** Low-opacity first-person equivalent of the world-space shield flash. */
public final class PlasmaShieldHitOverlay {

    public static final IGuiOverlay INSTANCE = PlasmaShieldHitOverlay::render;

    private static final ResourceLocation TEXTURE = new ResourceLocation(
            MiningConstants.MODID, "textures/entity/plasma_shield_hit.png");

    private PlasmaShieldHitOverlay() {
    }

    private static void render(ForgeGui gui,
                               GuiGraphics graphics,
                               float partialTick,
                               int screenWidth,
                               int screenHeight) {
        Minecraft minecraft = gui.getMinecraft();
        if (minecraft.level == null
                || minecraft.player == null
                || !minecraft.options.getCameraType().isFirstPerson()) {
            return;
        }
        ClientPlasmaShieldHitEffects.Frame frame =
                ClientPlasmaShieldHitEffects.frameFor(minecraft.player.getId(), partialTick);
        if (frame == null) {
            return;
        }

        PlasmaShieldVisualProfile.Style style = PlasmaShieldVisualProfile.style(frame.type());
        int rgb = style.highlightRgb();
        float alpha = frame.alpha() * 0.28F;
        int size = Math.round(Math.min(screenWidth, screenHeight) * 1.12F * frame.scale());
        int left = (screenWidth - size) / 2;
        int top = (screenHeight - size) / 2;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(
                ((rgb >> 16) & 0xFF) / 255.0F,
                ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F,
                alpha);
        graphics.blit(TEXTURE, left, top, size, size,
                0.0F, 0.0F, 256, 256, 256, 256);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }
}
