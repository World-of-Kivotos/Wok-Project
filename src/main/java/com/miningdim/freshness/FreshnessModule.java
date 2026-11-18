package com.miningdim.freshness;

import com.miningdim.core.Subsystem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** 腐烂底座的配置入口；业务事件随完整功能接入。 */
public final class FreshnessModule implements Subsystem {
    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER,
                FreshnessConfig.SPEC, "miningdim-freshness.toml");
    }

    @Override
    public String name() {
        return FreshnessConstants.MODULE_ID;
    }
}
