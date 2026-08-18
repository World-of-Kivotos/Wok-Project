package com.miningdim.power;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.mineral.PowerMineral;
import com.miningdim.power.mineral.PowerMineralRegistry;
import com.miningdim.power.rubber.PowerRubberRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** Dedicated creative tab for power-generation blocks. */
public final class PowerCreativeTab {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MiningConstants.MODID);

    public static final RegistryObject<CreativeModeTab> POWER_TAB = TABS.register("power",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.miningdim_power"))
                    .icon(() -> new ItemStack(PowerRegistry.INDUSTRIAL_GENERATOR_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(PowerRegistry.INDUSTRIAL_GENERATOR_ITEM.get());
                        output.accept(PowerRegistry.MODERN_GENERATOR_ITEM.get());
                        output.accept(PowerRegistry.FUTURE_ENERGY_GENERATOR_ITEM.get());
                        for (RegistryObject<Item> cable : PowerRegistry.CABLE_ITEMS.values()) {
                            output.accept(cable.get());
                        }
                        for (RegistryObject<Item> wire : PowerRegistry.WIRE_ITEMS.values()) {
                            output.accept(wire.get());
                        }
                        for (PowerMineral mineral : PowerMineral.values()) {
                            output.accept(PowerMineralRegistry.oreItem(mineral).get());
                            output.accept(PowerMineralRegistry.deepslateOre(mineral).get().asItem());
                            output.accept(PowerMineralRegistry.rawMaterial(mineral).get());
                            if (mineral.hasIngot()) {
                                output.accept(PowerMineralRegistry.ingot(mineral).get());
                            }
                        }
                        output.accept(PowerRubberRegistry.RUBBER_LOG_ITEM.get());
                        output.accept(PowerRubberRegistry.RUBBER_PLANKS_ITEM.get());
                        output.accept(PowerRubberRegistry.RUBBER_LEAVES_ITEM.get());
                        output.accept(PowerRubberRegistry.RUBBER_SAPLING_ITEM.get());
                        output.accept(PowerRubberRegistry.LATEX.get());
                        output.accept(PowerRubberRegistry.RUBBER.get());
                        output.accept(PowerRubberRegistry.INSULATION_PVC.get());
                        output.accept(PowerRubberRegistry.INSULATION_PE.get());
                        output.accept(PowerRubberRegistry.RUBBER_TAPPING_KNIFE.get());
                    })
                    .build());

    private PowerCreativeTab() {
    }

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
