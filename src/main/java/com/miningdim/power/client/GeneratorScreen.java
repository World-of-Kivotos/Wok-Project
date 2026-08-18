package com.miningdim.power.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.generator.GeneratorMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.Locale;

/** Pixel-art status console shared by all three generator tiers. */
public final class GeneratorScreen extends AbstractPowerMachineScreen<GeneratorMenu> {

    private static final ResourceLocation BACKGROUND = new ResourceLocation(
            MiningConstants.MODID, "textures/gui/power/generator.png");

    private static final int METER_X = 20;
    private static final int METER_WIDTH = 178;
    private static final int METER_HEIGHT = 7;
    private static final int ENERGY_Y = 74;
    private static final int TEMPERATURE_Y = 94;
    private static final int STATUS_X = 12;
    private static final int STATUS_Y = 105;
    private static final int STATUS_WIDTH = 193;
    private static final int STATUS_HEIGHT = 24;

    public GeneratorScreen(GeneratorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BACKGROUND, STANDARD_HEIGHT, 132);
    }

    @Override
    protected void renderMachine(GuiGraphics graphics, int left, int top,
                                 int mouseX, int mouseY, float partialTick) {
        drawMeter(graphics, left + METER_X, top + ENERGY_Y, METER_WIDTH, METER_HEIGHT,
                menu.storedFe(), menu.bufferCapacityFe(), ENERGY_COLOR);
        drawMeter(graphics, left + METER_X, top + TEMPERATURE_Y, METER_WIDTH, METER_HEIGHT,
                Math.round(menu.temperatureC() * 100.0D),
                Math.round(menu.meltdownTemperatureC() * 100.0D), temperatureColor());

        drawFittedString(graphics, energyText(), left + METER_X, top + 64,
                METER_WIDTH, PRIMARY_TEXT_COLOR);
        drawFittedString(graphics, temperatureText(), left + METER_X, top + 84,
                METER_WIDTH, PRIMARY_TEXT_COLOR);

        drawFittedString(graphics, stateText(), left + 18, top + 108,
                112, stateColor());
        drawFittedString(graphics, networkFaultText(), left + 140, top + 108,
                58, menu.networkFaultOrdinal() == 0 ? MUTED_TEXT_COLOR : DANGER_COLOR);
        drawFittedString(graphics, fuelText(), left + 18, top + 118,
                114, PRIMARY_TEXT_COLOR);
        drawFittedString(graphics, fuseText(), left + 138, top + 118,
                60, fuseColor());
    }

    @Override
    protected void renderMachineTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (inside(mouseX, mouseY, leftPos + METER_X, topPos + ENERGY_Y,
                METER_WIDTH, METER_HEIGHT)) {
            showTooltip(graphics, List.of(
                    energyText().copy().withStyle(ChatFormatting.AQUA),
                    rejectionText().copy().withStyle(ChatFormatting.GRAY),
                    networkFaultText().copy().withStyle(menu.networkFaultOrdinal() == 0
                            ? ChatFormatting.DARK_GRAY : ChatFormatting.RED)), mouseX, mouseY);
            return;
        }
        if (inside(mouseX, mouseY, leftPos + METER_X, topPos + TEMPERATURE_Y,
                METER_WIDTH, METER_HEIGHT)) {
            showTooltip(graphics, List.of(
                    temperatureText().copy().withStyle(ChatFormatting.GOLD),
                    stateText().copy().withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
            return;
        }
        if (inside(mouseX, mouseY, leftPos + STATUS_X, topPos + STATUS_Y,
                STATUS_WIDTH, STATUS_HEIGHT)) {
            showTooltip(graphics, List.of(
                    stateText(), fuelText(), fuseText(), rejectionText(), networkFaultText()),
                    mouseX, mouseY);
        }
    }

    private Component energyText() {
        return Component.translatable("screen.miningdim.generator.energy",
                menu.storedFe(), menu.bufferCapacityFe());
    }

    private Component temperatureText() {
        return Component.translatable("screen.miningdim.generator.temperature",
                formatTemperature(menu.temperatureC()), formatTemperature(menu.meltdownTemperatureC()));
    }

    private Component fuelText() {
        return Component.translatable("screen.miningdim.generator.fuel",
                menu.fuelRemainingDurability(), menu.fuelMaxDamage());
    }

    private Component rejectionText() {
        return Component.translatable("screen.miningdim.generator.rejection", menu.bufferRejectionFe());
    }

    private Component stateText() {
        String id = switch (menu.stateOrdinal()) {
            case 0 -> "idle";
            case 1 -> "running";
            case 2 -> "scram";
            case 3 -> "meltdown";
            default -> throw new IllegalStateException(
                    "unknown generator state ordinal: " + menu.stateOrdinal());
        };
        return Component.translatable("screen.miningdim.generator.state",
                Component.translatable("screen.miningdim.generator.state." + id));
    }

    private Component fuseText() {
        String id = switch (menu.fuseStateOrdinal()) {
            case 0 -> "absent";
            case 1 -> "installed";
            case 2 -> "tripped";
            default -> throw new IllegalStateException(
                    "unknown generator fuse ordinal: " + menu.fuseStateOrdinal());
        };
        return Component.translatable("screen.miningdim.generator.fuse",
                Component.translatable("screen.miningdim.generator.fuse." + id));
    }

    private Component networkFaultText() {
        String id = switch (menu.networkFaultOrdinal()) {
            case 0 -> "none";
            case 1 -> "over_voltage";
            default -> throw new IllegalStateException(
                    "unknown generator network fault ordinal: " + menu.networkFaultOrdinal());
        };
        return Component.translatable("jade.miningdim.power.generator.network_fault_value." + id);
    }

    private int temperatureColor() {
        double limit = menu.meltdownTemperatureC();
        if (limit <= 0.0D) {
            return MUTED_TEXT_COLOR;
        }
        double ratio = menu.temperatureC() / limit;
        if (ratio >= 0.85D) {
            return DANGER_COLOR;
        }
        return ratio >= 0.65D ? WARNING_COLOR : ENERGY_COLOR;
    }

    private int stateColor() {
        return switch (menu.stateOrdinal()) {
            case 0 -> MUTED_TEXT_COLOR;
            case 1 -> PROCESS_COLOR;
            case 2 -> WARNING_COLOR;
            case 3 -> DANGER_COLOR;
            default -> PRIMARY_TEXT_COLOR;
        };
    }

    private int fuseColor() {
        return switch (menu.fuseStateOrdinal()) {
            case 0 -> MUTED_TEXT_COLOR;
            case 1 -> PROCESS_COLOR;
            case 2 -> DANGER_COLOR;
            default -> PRIMARY_TEXT_COLOR;
        };
    }

    private static String formatTemperature(double temperature) {
        return String.format(Locale.ROOT, "%.2f", temperature);
    }
}
