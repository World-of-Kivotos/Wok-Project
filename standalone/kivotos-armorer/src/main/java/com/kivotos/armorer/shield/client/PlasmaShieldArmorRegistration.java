package com.kivotos.armorer.shield.client;

import com.kivotos.armorer.ArmorerMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Registers the client-only empty carrier layer without touching dedicated-server class loading. */
@Mod.EventBusSubscriber(modid = ArmorerMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PlasmaShieldArmorRegistration {

    private PlasmaShieldArmorRegistration() {
    }

    @SubscribeEvent
    public static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(PlasmaShieldArmorModel.LAYER, PlasmaShieldArmorModel::createLayer);
    }
}

