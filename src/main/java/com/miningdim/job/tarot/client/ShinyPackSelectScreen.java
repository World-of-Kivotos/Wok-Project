package com.miningdim.job.tarot.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.pack.ShinyPackSelectMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 闪耀卡包自选 GUI 客户端屏幕 (TarotReader spec 第七章)。22 张大阿卡纳排成网格按钮 (4 行 x 6 列 = 24 格,
 * 后 2 格空), 点击发 clickMenuButton(cardId) 到服务端给一张该牌 SSR。
 */
public final class ShinyPackSelectScreen extends AbstractMiningScreen<ShinyPackSelectMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/shiny_select.png");
    private static final int W = 176;
    private static final int H = 166;

    private static final int COLS = 6;
    private static final int CELL = 20;
    private static final int GRID_X = 16;
    private static final int GRID_Y = 24;

    public ShinyPackSelectScreen(ShinyPackSelectMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, W, H);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cardId = cardIdAt(mouseX, mouseY);
        if (cardId >= 0) {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, cardId);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int cardIdAt(double mouseX, double mouseY) {
        int gx = this.leftPos + GRID_X;
        int gy = this.topPos + GRID_Y;
        for (int id = 0; id < TarotArcana.COUNT; id++) {
            int col = id % COLS;
            int row = id / COLS;
            int x = gx + col * CELL;
            int y = gy + row * CELL;
            if (mouseX >= x && mouseX < x + CELL - 2 && mouseY >= y && mouseY < y + CELL - 2) {
                return id;
            }
        }
        return -1;
    }

    @Override
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos, int mouseX, int mouseY, float pt) {
        // 每格画牌名首字母占位 (基础贴图由资源包另铺; 此处用文字标号保证无贴图时仍可辨识与点选)。
        int gx = leftPos + GRID_X;
        int gy = topPos + GRID_Y;
        for (int id = 0; id < TarotArcana.COUNT; id++) {
            int col = id % COLS;
            int row = id / COLS;
            int x = gx + col * CELL;
            int y = gy + row * CELL;
            graphics.drawString(this.font, String.valueOf(id), x + 4, y + 6, 0xFFFFFF, false);
        }
    }
}
