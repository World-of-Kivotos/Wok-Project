package com.miningdim.job.munitions.client;

import com.miningdim.job.munitions.gunsmith.GunsmithFactionTooltip;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/** 军火商客户端专用注册入口。 */
public final class MunitionsClientSetup {

    private MunitionsClientSetup() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(MunitionsClientSetup::onRegisterTooltipFactories);
    }

    private static void onRegisterTooltipFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(GunsmithFactionTooltip.class, ClientGunsmithFactionTooltip::new);
    }

    public static boolean isClient() {
        return net.minecraftforge.fml.loading.FMLEnvironment.dist == Dist.CLIENT;
    }
}
