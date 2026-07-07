package com.miningdim.marriage.client;

import com.miningdim.marriage.MarriageBackpackMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 共享背包客户端屏幕 (结婚系统 spec 第四章; 仅客户端逻辑端加载, 经 {@link MarriageBackpackClient} + DistExecutor 隔离
 * 注册)。纯视图: 容器槽与玩家背包槽布局由 {@link MarriageBackpackMenu} 在两端对称铺好, 本屏幕只提供底图渲染脚手架,
 * 无额外绘制 (像素/美术留 runClient, 与 agent 面板同 placeholder 路线)。
 *
 * 服务端权威: 取放变更走原版 menu 同步回服务端校验 (容器 canPlaceItem 白名单), 客户端不自算、不对账。
 */
public final class MarriageBackpackScreen extends AbstractMiningScreen<MarriageBackpackMenu> {

    private static final int W = 176;
    private static final int H = 222;

    public MarriageBackpackScreen(MarriageBackpackMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, MarriageBackpackMenu.BG, W, H);
    }
}
