package com.miningdim.power.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.generator.PreheatGeneratorMenu;
import com.miningdim.power.grid.CableThermics;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/** 煤炭与地热发电机共用界面：温度决定功率，故温度条是这里的第一读数。 */
public final class PreheatGeneratorScreen extends AbstractPowerMachineScreen<PreheatGeneratorMenu> {

    private static final ResourceLocation BACKGROUND =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/power/preheat_generator.png");
    private static final int INVENTORY_TITLE_Y = 132;

    private static final int METER_X = 20;
    private static final int METER_WIDTH = 178;
    private static final int METER_HEIGHT = 7;
    private static final int TEMPERATURE_METER_Y = 74;
    private static final int ENERGY_METER_Y = 94;
    private static final int BURN_METER_Y = 114;
    private static final int LABEL_X = 20;

    public PreheatGeneratorScreen(PreheatGeneratorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BACKGROUND, STANDARD_HEIGHT, INVENTORY_TITLE_Y);
    }

    @Override
    protected void renderMachine(GuiGraphics graphics, int left, int top,
                                 int mouseX, int mouseY, float partialTick) {
        double temperature = menu.temperatureC();
        double workingTemperature = menu.workingTemperatureC();
        // 温度条按"环境到工作温度"这一段归一, 玩家看到的满格即满功率, 而不是绝对摄氏度。
        long temperatureSpan = Math.max(1L, PreheatGeneratorMenu.centi(workingTemperature - CableThermics.AMBIENT_C));
        long temperatureValue = PreheatGeneratorMenu.centi(temperature - CableThermics.AMBIENT_C);
        drawMeter(graphics, left + METER_X, top + TEMPERATURE_METER_Y, METER_WIDTH, METER_HEIGHT,
                temperatureValue, temperatureSpan, DANGER_COLOR);
        drawMeter(graphics, left + METER_X, top + ENERGY_METER_Y, METER_WIDTH, METER_HEIGHT,
                menu.storedFe(), menu.bufferCapacityFe(), ENERGY_COLOR);
        drawMeter(graphics, left + METER_X, top + BURN_METER_Y, METER_WIDTH, METER_HEIGHT,
                menu.burnTicksRemaining(), menu.burnTicksTotal(), WARNING_COLOR);

        graphics.drawString(font, Component.translatable("gui.miningdim.power.preheat.temperature",
                        String.format("%.1f", temperature), String.format("%.0f", workingTemperature)),
                left + LABEL_X, top + TEMPERATURE_METER_Y - 10, PRIMARY_TEXT_COLOR, false);
        graphics.drawString(font, Component.translatable("gui.miningdim.power.preheat.energy",
                        menu.storedFe(), menu.bufferCapacityFe()),
                left + LABEL_X, top + ENERGY_METER_Y - 10, PRIMARY_TEXT_COLOR, false);
        graphics.drawString(font, Component.translatable("gui.miningdim.power.preheat.output",
                        menu.outputFePerTick()),
                left + LABEL_X, top + BURN_METER_Y - 10, MUTED_TEXT_COLOR, false);
    }

    @Override
    protected void renderMachineTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (inside(mouseX, mouseY, leftPos + METER_X, topPos + TEMPERATURE_METER_Y,
                METER_WIDTH, METER_HEIGHT)) {
            showTooltip(graphics, List.of(
                    Component.translatable("gui.miningdim.power.preheat.temperature",
                            String.format("%.2f", menu.temperatureC()),
                            String.format("%.0f", menu.workingTemperatureC())),
                    Component.translatable("gui.miningdim.power.preheat.output_hint")), mouseX, mouseY);
            return;
        }
        if (inside(mouseX, mouseY, leftPos + METER_X, topPos + ENERGY_METER_Y, METER_WIDTH, METER_HEIGHT)) {
            showTooltip(graphics, List.of(
                    Component.translatable("gui.miningdim.power.preheat.energy",
                            menu.storedFe(), menu.bufferCapacityFe()),
                    Component.translatable("gui.miningdim.power.preheat.output", menu.outputFePerTick())),
                    mouseX, mouseY);
            return;
        }
        if (inside(mouseX, mouseY, leftPos + METER_X, topPos + BURN_METER_Y, METER_WIDTH, METER_HEIGHT)) {
            showTooltip(graphics, List.of(Component.translatable("gui.miningdim.power.preheat.burn",
                    menu.burnTicksRemaining() / 20, menu.burnTicksTotal() / 20)), mouseX, mouseY);
        }
    }
}
