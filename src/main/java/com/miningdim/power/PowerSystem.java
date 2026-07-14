package com.miningdim.power;

import com.miningdim.core.Subsystem;
import com.miningdim.power.grid.EnergyNetworkManager;
import net.minecraftforge.eventbus.api.IEventBus;

/** 发电系统入口: 三发电机外壳 + 五级有线 FE 线缆网 (线缆网每 level tick 结算, 挂 forgeBus)。 */
public final class PowerSystem implements Subsystem {

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        PowerRegistry.register(modBus);
        PowerCreativeTab.register(modBus);
        EnergyNetworkManager.register(forgeBus);
    }
}
