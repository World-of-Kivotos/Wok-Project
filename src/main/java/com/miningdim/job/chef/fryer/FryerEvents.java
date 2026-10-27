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
        if (event.phase == TickEvent.Phase.END && event.getServer().overworld().getGameTime() % 100 == 0) {
            // 退款不能依赖原炸锅仍处于加载状态，操作者下线也可由持久化账本恢复。
            FryerPlayerData.get(event.getServer().overworld()).retryRefunds();
        }
    }
}
