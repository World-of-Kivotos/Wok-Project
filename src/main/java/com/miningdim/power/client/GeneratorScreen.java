package com.miningdim.power.client;

import com.miningdim.power.generator.GeneratorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 发电机状态界面。背景、状态条和数值均由代码绘制，不依赖额外 GUI 纹理。 */
public final class GeneratorScreen extends AbstractContainerScreen<GeneratorMenu> {

    private static final int WIDTH = 176;
    private static final int HEIGHT = 222;

    public GeneratorScreen(GeneratorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = leftPos;
        int top = topPos;
        graphics.fill(left, top, left + imageWidth, top + imageHeight, 0xFF20252B);
        graphics.fill(left + 7, top + 7, left + imageWidth - 7, top + 30, 0xFF303840);
        graphics.fill(left + 7, top + 130, left + imageWidth - 7, top + 136, 0xFF303840);
        drawSlotFrame(graphics, left + 61, top + 34);
        drawSlotFrame(graphics, left + 97, top + 34);
        drawEnergyBar(graphics, left + 8, top + 68);
        drawTemperatureBar(graphics, left + 8, top + 87);

        graphics.drawString(font, Component.translatable("screen.miningdim.generator.state",
                stateText()), left + 10, top + 19, 0xFFE8EEF2, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.generator.energy",
                menu.storedFe(), menu.bufferCapacityFe()), left + 10, top + 57, 0xFFD5DDE4, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.generator.temperature",
                String.format("%.2f", menu.temperatureC()), String.format("%.2f", menu.meltdownTemperatureC())),
                left + 10, top + 76, 0xFFD5DDE4, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.generator.fuel",
                menu.fuelDamage(), menu.fuelMaxDamage()), left + 10, top + 96, 0xFFD5DDE4, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.generator.fuse",
                fuseText()), left + 10, top + 107, 0xFFD5DDE4, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.generator.rejection",
                menu.bufferRejectionFe()), left + 10, top + 118, 0xFFD5DDE4, false);
    }

    private Component stateText() {
        String id = switch (menu.stateOrdinal()) {
            case 0 -> "idle";
            case 1 -> "running";
            case 2 -> "scram";
            case 3 -> "meltdown";
            default -> throw new IllegalStateException("unknown generator state ordinal: "
                    + menu.stateOrdinal());
        };
        return Component.translatable("screen.miningdim.generator.state." + id);
    }

    private Component fuseText() {
        String id = switch (menu.fuseStateOrdinal()) {
            case 0 -> "absent";
            case 1 -> "installed";
            case 2 -> "tripped";
            default -> throw new IllegalStateException("unknown generator fuse ordinal: "
                    + menu.fuseStateOrdinal());
        };
        return Component.translatable("screen.miningdim.generator.fuse." + id);
    }

    private void drawSlotFrame(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF111418);
        graphics.fill(x, y, x + 16, y + 16, 0xFF8A9299);
    }

    private void drawEnergyBar(GuiGraphics graphics, int x, int y) {
        int width = 160;
        int filled = menu.bufferCapacityFe() == 0 ? 0
                : (int) ((long) width * menu.storedFe() / menu.bufferCapacityFe());
        graphics.fill(x, y, x + width, y + 5, 0xFF111418);
        graphics.fill(x, y, x + filled, y + 5, 0xFF3D9BE9);
    }

    private void drawTemperatureBar(GuiGraphics graphics, int x, int y) {
        int width = 160;
        int filled = menu.meltdownTemperatureC() == 0.0D ? 0
                : (int) (width * menu.temperatureC() / menu.meltdownTemperatureC());
        graphics.fill(x, y, x + width, y + 5, 0xFF111418);
        graphics.fill(x, y, x + filled, y + 5, 0xFFD36A32);
    }
}
