package com.miningdim.power.cable;

import com.miningdim.power.PowerRegistry;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/** 客户端导线灰度基底的材料 tint 处理。 */
public final class PowerCableColors {

    private PowerCableColors() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(PowerCableColors::registerItemColors);
    }

    private static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        for (ConductorMaterial material : PowerRegistry.REGISTERED_MATERIALS) {
            event.register((stack, tintIndex) -> tintIndex == 0 ? material.tintColor() : -1,
                    PowerRegistry.WIRE_ITEMS.get(material).get());
        }
    }
}
