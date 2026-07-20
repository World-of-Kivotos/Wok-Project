package com.kivotos.armorer.shield.client;

import com.kivotos.armorer.ArmorerMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client MOD-bus registration for the plasma-shield HUD. */
@Mod.EventBusSubscriber(modid = ArmorerMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PlasmaShieldClientEvents {

    private PlasmaShieldClientEvents() {
    }

    @SubscribeEvent
    public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.VIGNETTE.id(),
                "plasma_shield_hit", PlasmaShieldHitOverlay.INSTANCE);
        event.registerAbove(VanillaGuiOverlay.PLAYER_HEALTH.id(),
                "plasma_shield", PlasmaShieldHudOverlay.INSTANCE);
    }
}

