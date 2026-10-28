package com.miningdim.job.chef.fryer;

import com.miningdim.core.MiningConstants;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MiningConstants.MODID)
public final class FryerEvents {
    private FryerEvents() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FryerPlayerData data = FryerPlayerData.get(event.getServer().overworld());
            if (event.getServer().overworld().getGameTime() % 100 == 0) data.retryRefunds();
            data.flushAsync();
        }
    }
}
