package com.miningdim.power.mineral;

import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/** 客户端矿脉覆盖层与矿物物品的 tint 处理。 */
public final class PowerMineralColors {

    private PowerMineralColors() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(PowerMineralColors::registerBlockColors);
        modBus.addListener(PowerMineralColors::registerItemColors);
    }

    private static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        for (PowerMineral mineral : PowerMineral.values()) {
            event.register((state, level, pos, tintIndex) -> tintIndex == 0 ? mineral.tintColor() : -1,
                    PowerMineralRegistry.ore(mineral).get(),
                    PowerMineralRegistry.deepslateOre(mineral).get());
        }
    }

    private static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        for (PowerMineral mineral : PowerMineral.values()) {
            event.register((stack, tintIndex) -> tintIndex == 0 ? mineral.tintColor() : -1,
                    PowerMineralRegistry.oreItem(mineral).get(),
                    PowerMineralRegistry.deepslateOre(mineral).get().asItem(),
                    PowerMineralRegistry.rawMaterial(mineral).get());
            if (mineral.hasIngot()) {
                event.register((stack, tintIndex) -> tintIndex == 0 ? mineral.tintColor() : -1,
                        PowerMineralRegistry.ingot(mineral).get());
            }
        }
    }
}
