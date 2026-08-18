package com.miningdim.power.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.machine.MetallurgicPurifierMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/** Metallurgic purifier container with three synchronized process meters. */
public final class MetallurgicPurifierScreen
        extends AbstractPowerMachineScreen<MetallurgicPurifierMenu> {

    private static final ResourceLocation BACKGROUND = new ResourceLocation(
            MiningConstants.MODID, "textures/gui/power/metallurgic_purifier.png");

    private static final int METER_X = 20;
    private static final int METER_WIDTH = 178;
    private static final int METER_HEIGHT = 7;
    private static final int PROGRESS_Y = 75;
    private static final int ENERGY_Y = 95;
    private static final int INFUSION_Y = 115;

    public MetallurgicPurifierScreen(MetallurgicPurifierMenu menu,
                                     Inventory inventory, Component title) {
        super(menu, inventory, title, BACKGROUND, STANDARD_HEIGHT, 132);
    }

    @Override
    protected void renderMachine(GuiGraphics graphics, int left, int top,
                                 int mouseX, int mouseY, float partialTick) {
        drawMeter(graphics, left + METER_X, top + PROGRESS_Y, METER_WIDTH, METER_HEIGHT,
                menu.progress(), menu.processingTime(), PROCESS_COLOR);
        drawMeter(graphics, left + METER_X, top + ENERGY_Y, METER_WIDTH, METER_HEIGHT,
                menu.storedFe(), menu.energyCapacity(), ENERGY_COLOR);
        drawMeter(graphics, left + METER_X, top + INFUSION_Y, METER_WIDTH, METER_HEIGHT,
                menu.infusionUnits(), menu.infusionCapacity(), INFUSION_COLOR);

        drawFittedString(graphics, progressText(), left + METER_X, top + 65,
                METER_WIDTH, PRIMARY_TEXT_COLOR);
        drawFittedString(graphics, energyText(), left + METER_X, top + 85,
                METER_WIDTH, PRIMARY_TEXT_COLOR);
        drawFittedString(graphics, infusionText(), left + METER_X, top + 105,
                METER_WIDTH, PRIMARY_TEXT_COLOR);
    }

    @Override
    protected void renderMachineTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (inside(mouseX, mouseY, leftPos + METER_X, topPos + PROGRESS_Y,
                METER_WIDTH, METER_HEIGHT)) {
            showTooltip(graphics, List.of(
                    progressText().copy().withStyle(ChatFormatting.GREEN)), mouseX, mouseY);
            return;
        }
        if (inside(mouseX, mouseY, leftPos + METER_X, topPos + ENERGY_Y,
                METER_WIDTH, METER_HEIGHT)) {
            showTooltip(graphics, List.of(
                    energyText().copy().withStyle(ChatFormatting.AQUA)), mouseX, mouseY);
            return;
        }
        if (inside(mouseX, mouseY, leftPos + METER_X, topPos + INFUSION_Y,
                METER_WIDTH, METER_HEIGHT)) {
            showTooltip(graphics, List.of(
                    infusionText().copy().withStyle(ChatFormatting.LIGHT_PURPLE)), mouseX, mouseY);
        }
    }

    private Component progressText() {
        return Component.translatable("screen.miningdim.metallurgic_purifier.progress",
                menu.progress(), menu.processingTime());
    }

    private Component energyText() {
        return Component.translatable("screen.miningdim.metallurgic_purifier.energy",
                menu.storedFe(), menu.energyCapacity());
    }

    private Component infusionText() {
        return Component.translatable("screen.miningdim.metallurgic_purifier.infusion",
                menu.infusionUnits(), menu.infusionCapacity());
    }
}
