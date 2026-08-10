package com.kivotos.armorer.armor.client;

import com.kivotos.armorer.ArmorerMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 在客户端模型烘焙阶段注册已完成的插板护甲模型层。 */
@Mod.EventBusSubscriber(modid = ArmorerMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PlateArmorClientRegistration {

    private PlateArmorClientRegistration() {
    }

    @SubscribeEvent
    public static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        PlateArmorModelDefinition.registerLayers(event);
    }
}

