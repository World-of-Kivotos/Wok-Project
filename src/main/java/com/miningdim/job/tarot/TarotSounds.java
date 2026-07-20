package com.miningdim.job.tarot;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Spatial sound cues for the tarot casting presentation. */
public final class TarotSounds {

    private static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MiningConstants.MODID);

    public static final RegistryObject<SoundEvent> CAST_START = register("tarot_cast_start");
    public static final RegistryObject<SoundEvent> CRAFT_SUCCESS = register("tarot_craft_success");
    public static final RegistryObject<SoundEvent> CRAFT_GREAT_SUCCESS =
            register("tarot_craft_great_success");
    public static final RegistryObject<SoundEvent> CRAFT_REVERSE = register("tarot_craft_reverse");
    public static final RegistryObject<SoundEvent> CRAFT_SHATTER = register("tarot_craft_shatter");
    public static final RegistryObject<SoundEvent> CRAFT_BIG_SHATTER =
            register("tarot_craft_big_shatter");

    private TarotSounds() {
    }

    private static RegistryObject<SoundEvent> register(String id) {
        return SOUND_EVENTS.register(id, () -> SoundEvent.createVariableRangeEvent(
                new ResourceLocation(MiningConstants.MODID, id)));
    }

    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }
}
