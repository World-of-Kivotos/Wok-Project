package com.miningdim.job.tarot.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.craft.TarotCraftMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 合成 GUI 客户端屏幕 (TarotReader spec 第八章)。底图经公共脚手架 {@link AbstractMiningScreen} 绘制;
 * 叠一个 "合成" 按钮区 (点击发 clickMenuButton(BUTTON_CRAFT) 经原版网络到服务端裁决)。
 *
 * 客户端类: 只在 {@link TarotClientSetup} 的 FMLClientSetupEvent.enqueueWork 内经 MenuScreens.register 引用。
 */
public final class TarotCraftScreen extends AbstractMiningScreen<TarotCraftMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/tarot_craft.png");
    private static final int W = 176;
    private static final int H = 166;

    public TarotCraftScreen(TarotCraftMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, W, H);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int mouseButton) {
        return super.hasClickedOutside(mouseX, mouseY, guiLeft, guiTop, mouseButton);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 合成按钮区 (底图中部右侧的箭头按钮区域)。点中即发 clickMenuButton 到服务端。
        int btnX = this.leftPos + 112;
        int btnY = this.topPos + 33;
        int btnW = 22;
        int btnH = 18;
        if (mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH) {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, TarotCraftMenu.BUTTON_CRAFT);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos, int mouseX, int mouseY, float pt) {
        // 合成按钮提示文字 (底图静态, 此处仅在悬停时给一行 tooltip 由 super.renderTooltip 处理; 留扩展点)。
    }
}
