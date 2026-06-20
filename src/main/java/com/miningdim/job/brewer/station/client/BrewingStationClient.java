package com.miningdim.job.brewer.station.client;

import com.miningdim.job.brewer.station.BrewingStationRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * 酿酒台客户端 setup (仅客户端逻辑端加载, 经 {@link com.miningdim.job.brewer.BrewerSystem} 的 FMLClientSetupEvent
 * + DistExecutor 隔离调用, 与厨师 ChefClientSetup 同范式)。把酿酒台 MenuType 绑定到 {@link BrewingStationScreen}。
 *
 * 单列客户端类 (而非内联 lambda 进 BrewerSystem) 以保证客户端类引用集中本客户端包, 专用服务器永不触类。
 */
public final class BrewingStationClient {

    private BrewingStationClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(BrewingStationRegistry.STATION_MENU.get(), BrewingStationScreen::new);
    }
}
