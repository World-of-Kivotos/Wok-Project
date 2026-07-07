package com.miningdim.marriage.client;

import com.miningdim.marriage.MarriageRegistration;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * 共享背包客户端 setup (仅客户端逻辑端加载; 经 {@link com.miningdim.marriage.MarriageSystem} 的 FMLClientSetupEvent
 * + DistExecutor 隔离调 {@link #registerScreens()}, 范式同 {@code AgentScanClient})。把共享背包 MenuType 绑到
 * {@link MarriageBackpackScreen}。
 *
 * 单列客户端类 (而非内联 lambda 进系统入口) 以保证客户端类引用集中本客户端包, 专用服务器永不触类。
 */
public final class MarriageBackpackClient {

    private MarriageBackpackClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(MarriageRegistration.BACKPACK_MENU.get(), MarriageBackpackScreen::new);
    }
}
