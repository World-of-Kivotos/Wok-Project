package com.miningdim.power.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.storage.PowerCellMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/** 三级储电共用界面：余额是主读数，进出功率各占一条细表。 */
public final class PowerCellScreen extends AbstractPowerMachineScreen<PowerCellMenu> {

    private static final ResourceLocation BACKGROUND =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/power/power_cell.png");
    private static final int INVENTORY_TITLE_Y = 132;

    private static final int METER_X = 20;
    private static final int METER_WIDTH = 178;
    private static final int MAIN_METER_Y = 36;
    private static final int MAIN_METER_HEIGHT = 18;
    private static final int FLOW_METER_HEIGHT = 7;
    private static final int RECEIVED_METER_Y = 84;
    private static final int EXTRACTED_METER_Y = 104;

    public PowerCellScreen(PowerCellMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BACKGROUND, STANDARD_HEIGHT, INVENTORY_TITLE_Y);
    }

    @Override
    protected void renderMachine(GuiGraphics graphics, int left, int top,
                                 int mouseX, int mouseY, float partialTick) {
        drawMeter(graphics, left + METER_X, top + MAIN_METER_Y, METER_WIDTH, MAIN_METER_HEIGHT,
                menu.storedFe(), menu.capacityFe(), ENERGY_COLOR);
        // 进出两条表以"本档最大传输速率"为满格基准, 靠单条表的填充比例就能看出电网是在充还是在放。
        int flowScale = Math.max(1, Math.max(menu.lastReceivedFe(), menu.lastExtractedFe()));
        drawMeter(graphics, left + METER_X, top + RECEIVED_METER_Y, METER_WIDTH, FLOW_METER_HEIGHT,
                menu.lastReceivedFe(), flowScale, PROCESS_COLOR);
        drawMeter(graphics, left + METER_X, top + EXTRACTED_METER_Y, METER_WIDTH, FLOW_METER_HEIGHT,
                menu.lastExtractedFe(), flowScale, WARNING_COLOR);

        graphics.drawString(font, Component.translatable("gui.miningdim.power.cell.stored",
                        menu.storedFe(), menu.capacityFe()),
                left + METER_X, top + MAIN_METER_Y - 10, PRIMARY_TEXT_COLOR, false);
        graphics.drawString(font, Component.translatable("gui.miningdim.power.cell.input",
                        menu.lastReceivedFe()),
                left + METER_X, top + RECEIVED_METER_Y - 10, PRIMARY_TEXT_COLOR, false);
        graphics.drawString(font, Component.translatable("gui.miningdim.power.cell.output",
                        menu.lastExtractedFe()),
                left + METER_X, top + EXTRACTED_METER_Y - 10, MUTED_TEXT_COLOR, false);
    }

    @Override
    protected void renderMachineTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (inside(mouseX, mouseY, leftPos + METER_X, topPos + MAIN_METER_Y,
                METER_WIDTH, MAIN_METER_HEIGHT)) {
            showTooltip(graphics, List.of(
                    Component.translatable("gui.miningdim.power.cell.stored",
                            menu.storedFe(), menu.capacityFe()),
                    Component.translatable("gui.miningdim.power.cell.net",
                            menu.lastReceivedFe() - menu.lastExtractedFe())), mouseX, mouseY);
        }
    }
}
