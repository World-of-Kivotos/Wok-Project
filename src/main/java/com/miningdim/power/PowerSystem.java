package com.miningdim.power;

import com.miningdim.core.Subsystem;
import net.minecraftforge.eventbus.api.IEventBus;

/** Registration entry point for the generator shell test subsystem. */
public final class PowerSystem implements Subsystem {

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        PowerRegistry.register(modBus);
        PowerCreativeTab.register(modBus);
    }
}
