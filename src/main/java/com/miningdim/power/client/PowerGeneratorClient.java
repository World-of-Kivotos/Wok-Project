package com.miningdim.power.client;

import com.miningdim.power.PowerRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

/** 发电机客户端注册入口，专用服务器不会触及本类。 */
public final class PowerGeneratorClient {

    private PowerGeneratorClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(PowerRegistry.GENERATOR_MENU.get(), GeneratorScreen::new);
    }
}
