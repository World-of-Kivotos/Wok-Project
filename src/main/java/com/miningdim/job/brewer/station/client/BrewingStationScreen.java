package com.miningdim.job.brewer.station.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.brewer.BrewerConstants;
import com.miningdim.job.brewer.station.BrewingStationMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 酿酒台客户端 Screen (酿酒师 阶段 3; 仅客户端, 经 {@link BrewingStationClient#registerScreens()} 在
 * FMLClientSetupEvent.enqueueWork 内 MenuScreens.register 注册)。
 *
 * 复用 {@link AbstractMiningScreen} 脚手架 (底图 + 标题 + 库存标签); 在 {@link #renderExtra} 叠加一条酿造进度条
 * (数据经 ContainerData 从服务端同步, 客户端只渲染)。
 */
public final class BrewingStationScreen extends AbstractMiningScreen<BrewingStationMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/brewing_station.png");
    private static final int W = 176;
    private static final int H = 166;

    // 进度条像素布局 (投料槽与输出槽之间, 中段)。
    private static final int BAR_X = 80;
    private static final int BAR_Y = 38;
    private static final int BAR_W = 48;
    private static final int BAR_H = 8;

    public BrewingStationScreen(BrewingStationMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, W, H);
    }

    @Override
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos,
                               int mouseX, int mouseY, float partialTick) {
        int x0 = leftPos + BAR_X;
        int y0 = topPos + BAR_Y;
        // 进度条底槽。
        graphics.fill(x0, y0, x0 + BAR_W, y0 + BAR_H, 0xFF202020);
        // 进度填充 (progress / BREW_DURATION_TICKS)。
        int goal = BrewerConstants.BREW_DURATION_TICKS;
        int filled = goal <= 0 ? 0 : (int) ((long) menu.progress() * BAR_W / goal);
        graphics.fill(x0, y0, x0 + Math.min(BAR_W, filled), y0 + BAR_H, 0xFF8E5A2B);
    }
}
