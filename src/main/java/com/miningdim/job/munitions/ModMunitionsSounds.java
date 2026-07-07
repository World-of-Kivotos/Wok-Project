package com.miningdim.job.munitions;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMunitionsSounds {

    private ModMunitionsSounds() {
    }

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MiningConstants.MODID);

    public static final RegistryObject<SoundEvent> MUNITIONS_BENCH_WELD =
            SOUND_EVENTS.register("munitions_bench_weld",
                    () -> SoundEvent.createVariableRangeEvent(
                            new ResourceLocation(MiningConstants.MODID, "munitions_bench_weld")));

    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }
}
