package com.miningdim.job.engineer.armor.integration;

import net.minecraftforge.eventbus.api.IEventBus;

/** 仅在 EngineerSystem 确认 TaCZ 已加载后触达，隔离 compileOnly 事件类型。 */
public final class PlateArmorTaczIntegrationBootstrap {

    private PlateArmorTaczIntegrationBootstrap() {
    }

    public static void assemble(IEventBus forgeBus) {
        forgeBus.register(new PlateArmorTaczDurabilityHandler());
    }
}
