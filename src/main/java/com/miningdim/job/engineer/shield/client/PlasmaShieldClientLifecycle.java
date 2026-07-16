package com.miningdim.job.engineer.shield.client;

import com.miningdim.core.MiningConstants;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Clears server-owned HUD data whenever the local player/network identity changes. */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlasmaShieldClientLifecycle {

    private PlasmaShieldClientLifecycle() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientPlasmaShieldState.clear();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientPlasmaShieldState.clear();
    }

    @SubscribeEvent
    public static void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        ClientPlasmaShieldState.clear();
    }
}
