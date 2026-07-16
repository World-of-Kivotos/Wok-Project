package com.miningdim.job.engineer.shield.client;

import com.miningdim.core.MiningConstants;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client MOD-bus registration for the plasma-shield HUD. */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PlasmaShieldClientEvents {

    private PlasmaShieldClientEvents() {
    }

    @SubscribeEvent
    public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.PLAYER_HEALTH.id(),
                "plasma_shield", PlasmaShieldHudOverlay.INSTANCE);
    }
}
