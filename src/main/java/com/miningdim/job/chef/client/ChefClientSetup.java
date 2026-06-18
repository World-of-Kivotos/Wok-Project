package com.miningdim.job.chef.client;

import com.miningdim.job.chef.ChefMenus;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * 厨师客户端 setup (仅客户端逻辑端加载, 经 {@link com.miningdim.job.chef.ChefSystem} 的 FMLClientSetupEvent
 * + DistExecutor 隔离调用)。把调味台 MenuType 绑定到 {@link SeasoningScreen}。
 *
 * 单列客户端类 (而非内联 lambda 进 ChefSystem) 以保证客户端类引用全集中本客户端包, 专用服务器永不触类。
 */
public final class ChefClientSetup {

    private ChefClientSetup() {
    }

    public static void registerScreens() {
        MenuScreens.register(ChefMenus.SEASONING_MENU.get(), SeasoningScreen::new);
    }
}
