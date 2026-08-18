package com.miningdim.power.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.endgame.LowTemperatureControllerBlockEntity;
import com.miningdim.power.endgame.LowTemperatureControllerMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/** Low-temperature controller with coolant lifetime and active-state feedback. */
public final class LowTemperatureControllerScreen
        extends AbstractPowerMachineScreen<LowTemperatureControllerMenu> {

    private static final ResourceLocation BACKGROUND = new ResourceLocation(
            MiningConstants.MODID, "textures/gui/power/low_temperature_controller.png");

    private static final int METER_X = 20;
    private static final int METER_Y = 68;
    private static final int METER_WIDTH = 178;
    private static final int METER_HEIGHT = 7;

    public LowTemperatureControllerScreen(LowTemperatureControllerMenu menu,
                                          Inventory inventory, Component title) {
        super(menu, inventory, title, BACKGROUND, CONTROLLER_HEIGHT, 84);
    }

    @Override
    protected void renderMachine(GuiGraphics graphics, int left, int top,
                                 int mouseX, int mouseY, float partialTick) {
        drawMeter(graphics, left + METER_X, top + METER_Y, METER_WIDTH, METER_HEIGHT,
                menu.remainingTicks(), LowTemperatureControllerBlockEntity.COOLING_TICKS_PER_CANISTER,
                menu.isCoolingActive() ? ENERGY_COLOR : MUTED_TEXT_COLOR);
        drawFittedString(graphics, remainingText(), left + METER_X, top + 58,
                METER_WIDTH, PRIMARY_TEXT_COLOR);
        drawFittedString(graphics, stateText(), left + 112, top + 78,
                86, menu.isCoolingActive() ? PROCESS_COLOR : MUTED_TEXT_COLOR);
    }

    @Override
    protected void renderMachineTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (inside(mouseX, mouseY, leftPos + METER_X, topPos + METER_Y,
                METER_WIDTH, METER_HEIGHT)) {
            showTooltip(graphics, List.of(
                    remainingText().copy().withStyle(ChatFormatting.AQUA),
                    stateText().copy().withStyle(menu.isCoolingActive()
                            ? ChatFormatting.GREEN : ChatFormatting.GRAY)), mouseX, mouseY);
        }
    }

    private Component remainingText() {
        return Component.translatable("screen.miningdim.low_temperature_controller.remaining",
                menu.remainingTicks(), LowTemperatureControllerBlockEntity.COOLING_TICKS_PER_CANISTER);
    }

    private Component stateText() {
        return Component.translatable(menu.isCoolingActive()
                ? "screen.miningdim.low_temperature_controller.active"
                : "screen.miningdim.low_temperature_controller.inactive");
    }
}
