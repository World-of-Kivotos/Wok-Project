package com.miningdim.job.engineer.armor.client;

import com.miningdim.core.MiningConstants;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 在客户端模型烘焙阶段注册 THOR 的原生人形模型层。 */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ThorIntegratedArmorClientRegistration {

    private ThorIntegratedArmorClientRegistration() {
    }

    @SubscribeEvent
    public static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(ThorIntegratedArmorModel.LAYER, ThorIntegratedArmorModel::createLayer);
    }
}
