package com.miningdim.job.farmer.granary.client;

import com.miningdim.job.farmer.granary.GranaryMenus;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * 粮仓的客户端 setup (仅客户端逻辑端加载; 集成者经 FarmerModule 的 FMLClientSetupEvent + DistExecutor
 * 隔离调 {@link #registerScreens()}, 范式同 ChefClientSetup / WineCellarClient)。把储粮囤的 MenuType
 * 绑到 {@link GranaryBinScreen}。
 */
public final class GranaryClientSetup {

    private GranaryClientSetup() {
    }

    public static void registerScreens() {
        MenuScreens.register(GranaryMenus.BIN.get(), GranaryBinScreen::new);
    }
}
