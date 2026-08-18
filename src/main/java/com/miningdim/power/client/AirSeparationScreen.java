package com.miningdim.power.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.machine.AirSeparationMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/** Air separation container with server-authoritative production mode selection. */
public final class AirSeparationScreen extends AbstractPowerMachineScreen<AirSeparationMenu> {

    private static final ResourceLocation BACKGROUND = new ResourceLocation(
            MiningConstants.MODID, "textures/gui/power/air_separation.png");

    private static final int BUTTON_Y = 36;
    private static final int ARGON_BUTTON_X = 20;
    private static final int NITROGEN_BUTTON_X = 114;
    private static final int BUTTON_WIDTH = 84;
    private static final int BUTTON_HEIGHT = 18;
    private static final int METER_X = 20;
    private static final int METER_WIDTH = 178;
    private static final int METER_HEIGHT = 7;
    private static final int PROGRESS_Y = 97;
    private static final int ENERGY_Y = 117;

    public AirSeparationScreen(AirSeparationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BACKGROUND, STANDARD_HEIGHT, 132);
    }

    @Override
    protected void renderMachine(GuiGraphics graphics, int left, int top,
                                 int mouseX, int mouseY, float partialTick) {
        drawModeButton(graphics, left + ARGON_BUTTON_X, top + BUTTON_Y,
                AirSeparationMenu.BUTTON_ARGON, argonText(), mouseX, mouseY);
        drawModeButton(graphics, left + NITROGEN_BUTTON_X, top + BUTTON_Y,
                AirSeparationMenu.BUTTON_LIQUID_NITROGEN, nitrogenText(), mouseX, mouseY);

        drawMeter(graphics, left + METER_X, top + PROGRESS_Y, METER_WIDTH, METER_HEIGHT,
                menu.progress(), menu.processingTime(), PROCESS_COLOR);
        drawMeter(graphics, left + METER_X, top + ENERGY_Y, METER_WIDTH, METER_HEIGHT,
                menu.storedFe(), menu.energyCapacity(), ENERGY_COLOR);
        drawFittedString(graphics, progressText(), left + METER_X, top + 87,
                METER_WIDTH, PRIMARY_TEXT_COLOR);
        drawFittedString(graphics, energyText(), left + METER_X, top + 107,
                METER_WIDTH, PRIMARY_TEXT_COLOR);
    }

    @Override
    protected void renderMachineTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int mode = modeButtonAt(mouseX, mouseY);
        if (mode >= 0) {
            List<Component> lines = new ArrayList<>();
            lines.add((mode == AirSeparationMenu.BUTTON_ARGON ? argonText() : nitrogenText())
                    .copy().withStyle(ChatFormatting.AQUA));
            if (menu.progress() > 0) {
                lines.add(progressText().copy().withStyle(ChatFormatting.GRAY));
            }
            showTooltip(graphics, lines, mouseX, mouseY);
            return;
        }
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
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int modeId = modeButtonAt(mouseX, mouseY);
            if (modeId >= 0) {
                if (menu.progress() == 0 && minecraft != null && minecraft.gameMode != null) {
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, modeId);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void drawModeButton(GuiGraphics graphics, int x, int y, int modeId,
                                Component label, int mouseX, int mouseY) {
        boolean selected = menu.modeOrdinal() == modeId;
        boolean enabled = menu.progress() == 0;
        boolean hovered = inside(mouseX, mouseY, x, y, BUTTON_WIDTH, BUTTON_HEIGHT);
        int fillColor;
        if (!enabled) {
            fillColor = selected ? 0xFF1A4E51 : 0xFF17232C;
        } else if (selected) {
            fillColor = 0xFF176F76;
        } else if (hovered) {
            fillColor = 0xFF2A4652;
        } else {
            fillColor = 0xFF1A2A34;
        }

        graphics.fill(x + 2, y + 2, x + BUTTON_WIDTH - 2, y + BUTTON_HEIGHT - 2, fillColor);
        if (selected) {
            graphics.fill(x + 3, y + BUTTON_HEIGHT - 3,
                    x + BUTTON_WIDTH - 3, y + BUTTON_HEIGHT - 1, ENERGY_COLOR);
        }
        drawFittedCenteredString(graphics, label, x + BUTTON_WIDTH / 2, y + 5,
                BUTTON_WIDTH - 8, enabled ? TITLE_COLOR : MUTED_TEXT_COLOR);
    }

    private int modeButtonAt(double mouseX, double mouseY) {
        if (inside(mouseX, mouseY, leftPos + ARGON_BUTTON_X, topPos + BUTTON_Y,
                BUTTON_WIDTH, BUTTON_HEIGHT)) {
            return AirSeparationMenu.BUTTON_ARGON;
        }
        if (inside(mouseX, mouseY, leftPos + NITROGEN_BUTTON_X, topPos + BUTTON_Y,
                BUTTON_WIDTH, BUTTON_HEIGHT)) {
            return AirSeparationMenu.BUTTON_LIQUID_NITROGEN;
        }
        return -1;
    }

    private Component progressText() {
        return Component.translatable("screen.miningdim.air_separation_unit.progress",
                menu.progress(), menu.processingTime());
    }

    private Component energyText() {
        return Component.translatable("screen.miningdim.air_separation_unit.energy",
                menu.storedFe(), menu.energyCapacity());
    }

    private static Component argonText() {
        return Component.translatable("screen.miningdim.air_separation_unit.mode.argon");
    }

    private static Component nitrogenText() {
        return Component.translatable("screen.miningdim.air_separation_unit.mode.liquid_nitrogen");
    }
}
