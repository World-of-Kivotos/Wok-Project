package com.miningdim.power.client;

import com.miningdim.power.PowerRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

/** P3 控制器客户端菜单注册入口，专用服务器不会触及本类。 */
public final class PowerEndgameClient {

    private PowerEndgameClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(PowerRegistry.LOW_TEMPERATURE_CONTROLLER_MENU.get(), LowTemperatureControllerScreen::new);
    }
}
