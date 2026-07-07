package com.miningdim.job.agent.client;

import com.miningdim.job.agent.AgentRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * 战术扫描面板客户端 setup (仅客户端逻辑端加载; 经 {@link com.miningdim.job.agent.AgentSystem} 的 FMLClientSetupEvent
 * + DistExecutor 隔离调 {@link #registerScreens()}, 范式同 {@code WineCellarClient} / {@code ChefClientSetup})。
 * 把扫描面板 MenuType 绑定到 {@link AgentScanScreen}。
 *
 * 单列客户端类 (而非内联 lambda 进系统入口) 以保证客户端类引用全集中本客户端包, 专用服务器永不触类。
 */
public final class AgentScanClient {

    private AgentScanClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(AgentRegistry.SCAN_MENU.get(), AgentScanScreen::new);
    }
}
