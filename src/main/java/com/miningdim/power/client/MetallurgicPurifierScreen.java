package com.miningdim.power.client;

import com.miningdim.power.machine.MetallurgicPurifierMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 提纯机容器界面：同步数据只作显示，配方与结算始终由服务端方块实体决定。 */
public final class MetallurgicPurifierScreen extends AbstractContainerScreen<MetallurgicPurifierMenu> {

    private static final int WIDTH = 176;
    private static final int HEIGHT = 222;
    private static final int BAR_WIDTH = 156;

    public MetallurgicPurifierScreen(MetallurgicPurifierMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = leftPos;
        int top = topPos;
        graphics.fill(left, top, left + imageWidth, top + imageHeight, 0xFFB5B5B5);
        graphics.fill(left + 4, top + 4, left + imageWidth - 4, top + 31, 0xFF373737);
        graphics.fill(left + 4, top + 105, left + imageWidth - 4, top + 111, 0xFF6A6A6A);
        drawSlotFrame(graphics, left + 43, top + 36);
        drawSlotFrame(graphics, left + 79, top + 36);
        drawSlotFrame(graphics, left + 115, top + 36);
        drawBar(graphics, left + 10, top + 69, menu.progress(), menu.processingTime(), 0xFF67A9E3);
        drawBar(graphics, left + 10, top + 82, menu.storedFe(), menu.energyCapacity(), 0xFFE3B34F);
        drawBar(graphics, left + 10, top + 95, menu.infusionUnits(), menu.infusionCapacity(), 0xFF8B6FD1);
        graphics.drawString(font, Component.translatable("screen.miningdim.metallurgic_purifier.progress",
                menu.progress(), menu.processingTime()), left + 10, top + 58, 0xFF252525, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.metallurgic_purifier.energy",
                menu.storedFe(), menu.energyCapacity()), left + 10, top + 120, 0xFF252525, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.metallurgic_purifier.infusion",
                menu.infusionUnits(), menu.infusionCapacity()), left + 10, top + 131, 0xFF252525, false);
    }

    private void drawSlotFrame(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF373737);
        graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
    }

    private void drawBar(GuiGraphics graphics, int x, int y, int value, int capacity, int color) {
        int filled = capacity == 0 ? 0 : (int) ((long) BAR_WIDTH * value / capacity);
        graphics.fill(x, y, x + BAR_WIDTH, y + 5, 0xFF373737);
        graphics.fill(x, y, x + filled, y + 5, color);
    }
}
