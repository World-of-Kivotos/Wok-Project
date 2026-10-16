package com.miningdim.job.farmer.granary.client;

import com.miningdim.job.farmer.granary.GranaryBinMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 储粮囤的界面 (客户端; 仅 Dist.CLIENT 加载, 经 {@link GranaryClientSetup} + DistExecutor 注册)。
 *
 * 底图**先借用原版潜影盒的**: 27 格 (9x3) 加玩家背包的坐标与它一模一样, 槽位对得上, 不用先画一张。
 * 粮仓自有贴图随美术那一批换掉 (只改这一行的 ResourceLocation)。
 */
public final class GranaryBinScreen extends AbstractMiningScreen<GranaryBinMenu> {

    private static final ResourceLocation BACKGROUND =
            new ResourceLocation("textures/gui/container/shulker_box.png");

    /** 与原版潜影盒一致: 176x166 (27 格 + 玩家背包)。 */
    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;

    public GranaryBinScreen(GranaryBinMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BACKGROUND, IMAGE_WIDTH, IMAGE_HEIGHT);
    }
}
