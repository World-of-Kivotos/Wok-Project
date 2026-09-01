package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.ModMunitionsBlockEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MiningConstants.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MunitionsBenchClient {

    private MunitionsBenchClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModMunitionsBlockEntities.MUNITIONS_BENCH.get(),
                context -> new MunitionsBenchRenderer());
    }
}
