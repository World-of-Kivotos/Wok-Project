package com.miningdim.power.client;

import com.miningdim.power.endgame.LowTemperatureControllerBlockEntity;
import com.miningdim.power.endgame.LowTemperatureControllerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 控制器界面只显示必要的液氮槽、剩余时间和冷却状态。 */
public final class LowTemperatureControllerScreen extends AbstractContainerScreen<LowTemperatureControllerMenu> {

    private static final int WIDTH = 176;
    private static final int HEIGHT = 176;
    private static final int BAR_WIDTH = 156;

    public LowTemperatureControllerScreen(LowTemperatureControllerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = leftPos;
        int top = topPos;
        graphics.fill(left, top, left + imageWidth, top + imageHeight, 0xFFB5B5B5);
        graphics.fill(left + 4, top + 4, left + imageWidth - 4, top + 26, 0xFF373737);
        drawSlotFrame(graphics, left + 79, top + 33);
        int remaining = menu.remainingTicks();
        int filled = (int) ((long) BAR_WIDTH * remaining
                / LowTemperatureControllerBlockEntity.COOLING_TICKS_PER_CANISTER);
        graphics.fill(left + 10, top + 65, left + 10 + BAR_WIDTH, top + 70, 0xFF373737);
        graphics.fill(left + 10, top + 65, left + 10 + filled, top + 70,
                menu.isCoolingActive() ? 0xFF67A9E3 : 0xFF6A6A6A);
        Component state = menu.isCoolingActive()
                ? Component.translatable("screen.miningdim.low_temperature_controller.active")
                : Component.translatable("screen.miningdim.low_temperature_controller.inactive");
        graphics.drawString(font, state, left + 10, top + 76, 0xFF252525, false);
        graphics.drawString(font, Component.translatable("screen.miningdim.low_temperature_controller.remaining",
                remaining, LowTemperatureControllerBlockEntity.COOLING_TICKS_PER_CANISTER),
                left + 10, top + 88, 0xFF252525, false);
    }

    private void drawSlotFrame(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF373737);
        graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
    }
}
