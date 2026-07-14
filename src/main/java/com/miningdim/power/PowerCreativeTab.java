package com.miningdim.power;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.cable.CableTier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
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
                        for (CableTier tier : CableTier.values()) {
                            output.accept(PowerRegistry.CABLE_ITEMS.get(tier).get());
                        }
                    })
                    .build());

    private PowerCreativeTab() {
    }

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
