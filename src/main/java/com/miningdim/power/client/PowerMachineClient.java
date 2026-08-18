package com.miningdim.power.client;

import com.miningdim.power.PowerMachineRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

/** 能源加工机器的客户端菜单注册入口，专用服务器不会触及本类。 */
public final class PowerMachineClient {

    private PowerMachineClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(PowerMachineRegistry.PURIFIER_MENU.get(), MetallurgicPurifierScreen::new);
        MenuScreens.register(PowerMachineRegistry.AIR_SEPARATOR_MENU.get(), AirSeparationScreen::new);
    }
}
