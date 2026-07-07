package com.miningdim.job.brewer.cellar.client;

import com.miningdim.job.brewer.cellar.WineCellarRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * 酒窖箱客户端 setup (仅客户端逻辑端加载; 集成者经 BrewerSystem 的 FMLClientSetupEvent + DistExecutor 隔离调
 * {@link #registerScreens()}, 范式同 {@link com.miningdim.job.chef.client.ChefClientSetup})。把酒窖箱 MenuType
 * 绑定到 {@link WineCellarScreen}。
 *
 * 单列客户端类 (而非内联 lambda 进系统入口) 以保证客户端类引用集中本客户端包, 专用服务器永不触类。
 */
public final class WineCellarClient {

    private WineCellarClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(WineCellarRegistry.WINE_CELLAR_MENU.get(), WineCellarScreen::new);
    }
}
