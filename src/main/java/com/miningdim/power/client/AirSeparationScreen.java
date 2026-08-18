package com.miningdim.power.client;

import com.miningdim.power.machine.AirSeparationMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 空分机容器界面，模式选择走原版菜单按钮包，服务端负责零进度校验。 */
public final class AirSeparationScreen extends AbstractContainerScreen<AirSeparationMenu> {

    private static final int WIDTH = 176;
    private static final int HEIGHT = 222;
    private static final int BAR_WIDTH = 156;
    private static final int ARGON_BUTTON_ID = 0;
    private static final int LIQUID_NITROGEN_BUTTON_ID = 1;
    private static final int BUTTON_X = 10;
    private static final int BUTTON_Y = 43;
    private static final int BUTTON_WIDTH = 74;
    private static final int BUTTON_HEIGHT = 16;

    public AirSeparationScreen(AirSeparationMenu menu, Inventory inventory, Component title) {
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
        graphics.fill(left + 4, top + 130, left + imageWidth - 4, top + 136, 0xFF6A6A6A);
        drawSlotFrame(graphics, left + 79, top + 72);
        drawModeButton(graphics, left + BUTTON_X, top + BUTTON_Y, ARGON_BUTTON_ID,
                Component.translatable("screen.miningdim.air_separation_unit.mode.argon"));
        drawModeButton(graphics, left + BUTTON_X + 82, top + BUTTON_Y, LIQUID_NITROGEN_BUTTON_ID,
                Component.translatable("screen.miningdim.air_separation_unit.mode.liquid_nitrogen"));
        drawBar(graphics, left + 10, top + 102, menu.progress(), menu.processingTime(), 0xFF67A9E3);
        drawBar(graphics, left + 10, top + 122, menu.storedFe(), menu.energyCapacity(), 0xFFE3B34F);
        graphics.drawString(font, Component.translatable("screen.miningdim.air_separation_unit.progress",
                menu.progress(), menu.processingTime()), left + 10, top + 91, 0xFF252525, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.air_separation_unit.energy",
                menu.storedFe(), menu.energyCapacity()), left + 10, top + 111, 0xFF252525, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int modeId = modeButtonAt(mouseX, mouseY);
            if (modeId >= 0) {
                if (minecraft != null && minecraft.gameMode != null) {
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, modeId);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void drawSlotFrame(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF373737);
        graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
    }

    private void drawModeButton(GuiGraphics graphics, int x, int y, int modeId, Component label) {
        boolean selected = menu.modeOrdinal() == modeId;
        int fill = selected ? 0xFF5F8F4B : 0xFF696969;
        graphics.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, 0xFF373737);
        graphics.fill(x + 1, y + 1, x + BUTTON_WIDTH - 1, y + BUTTON_HEIGHT - 1, fill);
        graphics.drawCenteredString(font, label, x + BUTTON_WIDTH / 2, y + 4, 0xFFFFFFFF);
    }

    private void drawBar(GuiGraphics graphics, int x, int y, int value, int capacity, int color) {
        int filled = capacity == 0 ? 0 : (int) ((long) BAR_WIDTH * value / capacity);
        graphics.fill(x, y, x + BAR_WIDTH, y + 5, 0xFF373737);
        graphics.fill(x, y, x + filled, y + 5, color);
    }

    private int modeButtonAt(double mouseX, double mouseY) {
        if (insideButton(mouseX, mouseY, leftPos + BUTTON_X, topPos + BUTTON_Y)) {
            return ARGON_BUTTON_ID;
        }
        if (insideButton(mouseX, mouseY, leftPos + BUTTON_X + 82, topPos + BUTTON_Y)) {
            return LIQUID_NITROGEN_BUTTON_ID;
        }
        return -1;
    }

    private boolean insideButton(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + BUTTON_WIDTH && mouseY >= y && mouseY < y + BUTTON_HEIGHT;
    }
}
