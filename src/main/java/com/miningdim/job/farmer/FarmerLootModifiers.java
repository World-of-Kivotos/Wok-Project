package com.miningdim.job.farmer;

import com.miningdim.core.MiningConstants;
import com.mojang.serialization.Codec;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Farmer global-loot-modifier registration. */
public final class FarmerLootModifiers {

    private static final DeferredRegister<Codec<? extends IGlobalLootModifier>> MODIFIERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS,
                    MiningConstants.MODID);

    @SuppressWarnings("unused")
    public static final RegistryObject<Codec<? extends IGlobalLootModifier>> CROP_YIELD =
            MODIFIERS.register("farmer_crop_yield", () -> FarmerHarvestLootModifier.CODEC);

    private FarmerLootModifiers() {
    }

    public static void register(IEventBus modBus) {
        MODIFIERS.register(modBus);
    }
}
