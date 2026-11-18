package com.miningdim.client.social;

import com.miningdim.core.Subsystem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;

/**
 * 客户端社交入口的 Dist 隔离壳。服务端只加载空壳；MCEF 缺失时连键位都不注册，避免玩家点开一个
 * 注定打不开的平板。
 */
public final class SocialClientSubsystem implements Subsystem {

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        if (!ModList.get().isLoaded("mcef")) {
            return;
        }
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SocialClient.init(modBus, forgeBus));
    }
}
