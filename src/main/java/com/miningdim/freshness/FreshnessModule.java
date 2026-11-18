package com.miningdim.freshness;

import com.miningdim.core.Subsystem;
import com.miningdim.freshness.item.FreshnessItems;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** 腐烂底座的配置和类型注册入口；业务事件随完整功能接入。 */
public final class FreshnessModule implements Subsystem {
    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER,
                FreshnessConfig.SPEC, "miningdim-freshness.toml");
        FreshnessItems.register(modBus);
        FreshnessLootModifiers.register(modBus);
    }

    @Override
    public String name() {
        return FreshnessConstants.MODULE_ID;
    }
}
