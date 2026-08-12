package com.kivotos.armorer;

import com.kivotos.armorer.ArmorerMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Sound events owned by the engineer subsystem. */
public final class ArmorerSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ArmorerMod.MODID);

    public static final RegistryObject<SoundEvent> PLASMA_SHIELD_OVERHEAT =
            register("plasma_shield_overheat");
    public static final RegistryObject<SoundEvent> PLASMA_SHIELD_STEAM_VENT =
            register("plasma_shield_steam_vent");
    public static final RegistryObject<SoundEvent> PLASMA_SHIELD_HIT =
            register("plasma_shield_hit");

    private ArmorerSounds() {
    }

    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }

    private static RegistryObject<SoundEvent> register(String id) {
        return SOUND_EVENTS.register(id, () -> SoundEvent.createVariableRangeEvent(
                new ResourceLocation(ArmorerMod.MODID, id)));
    }
}

