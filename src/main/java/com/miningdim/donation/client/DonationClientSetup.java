package com.miningdim.donation.client;

import com.miningdim.donation.DonationRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * 捐赠箱客户端 setup (仅客户端加载; 模块入口经 FMLClientSetupEvent + DistExecutor 双箭头调用)。
 * 客户端类引用集中在本包, 专用服务器永不触类。
 */
public final class DonationClientSetup {

    private DonationClientSetup() {
    }

    public static void registerScreens() {
        MenuScreens.register(DonationRegistry.DONOR_MENU.get(), DonationDonorScreen::new);
        MenuScreens.register(DonationRegistry.MANAGER_MENU.get(), DonationManagerScreen::new);
    }
}
