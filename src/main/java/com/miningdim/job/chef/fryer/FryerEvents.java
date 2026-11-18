package com.miningdim.job.chef.fryer;

import com.miningdim.core.MiningConstants;
import com.mojang.logging.LogUtils;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.UncheckedIOException;

@Mod.EventBusSubscriber(modid = MiningConstants.MODID)
public final class FryerEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private FryerEvents() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FryerPlayerData data = FryerPlayerData.get(event.getServer().overworld());
            if (event.getServer().overworld().getGameTime() % 100 == 0) {
                try {
                    data.retryRefunds();
                } catch (UncheckedIOException failure) {
                    LOGGER.error("Cannot persist fryer transaction queues; deferring settlement", failure);
                }
            }
            data.flushAsync();
        }
    }
}
