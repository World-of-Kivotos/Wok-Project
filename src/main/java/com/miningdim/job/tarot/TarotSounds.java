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
    public static final RegistryObject<SoundEvent> CAST_REVEAL_R = register("tarot_cast_reveal_r");
    public static final RegistryObject<SoundEvent> CAST_REVEAL_SR = register("tarot_cast_reveal_sr");
    public static final RegistryObject<SoundEvent> CAST_REVEAL_SSR = register("tarot_cast_reveal_ssr");
    public static final RegistryObject<SoundEvent> CAST_REVEAL_UR = register("tarot_cast_reveal_ur");
    public static final RegistryObject<SoundEvent> CAST_REVEAL_SHINY = register("tarot_cast_reveal_shiny");
    public static final RegistryObject<SoundEvent> CAST_RESOLVE_UPRIGHT =
            register("tarot_cast_resolve_upright");
    public static final RegistryObject<SoundEvent> CAST_RESOLVE_REVERSED =
            register("tarot_cast_resolve_reversed");
    public static final RegistryObject<SoundEvent> CRAFT_CHARGE = register("tarot_craft_charge");
    public static final RegistryObject<SoundEvent> CRAFT_SUCCESS = register("tarot_craft_success");
    public static final RegistryObject<SoundEvent> CRAFT_GREAT_SUCCESS =
            register("tarot_craft_great_success");
    public static final RegistryObject<SoundEvent> CRAFT_REVERSE = register("tarot_craft_reverse");
    public static final RegistryObject<SoundEvent> CRAFT_SHATTER = register("tarot_craft_shatter");
    public static final RegistryObject<SoundEvent> CRAFT_BIG_SHATTER =
            register("tarot_craft_big_shatter");
    public static final RegistryObject<SoundEvent> PACK_SCAN = register("tarot_pack_scan");
    public static final RegistryObject<SoundEvent> PACK_OPEN = register("tarot_pack_open");
    public static final RegistryObject<SoundEvent> PACK_REVEAL_R = register("tarot_pack_reveal_r");
    public static final RegistryObject<SoundEvent> PACK_REVEAL_SR = register("tarot_pack_reveal_sr");
    public static final RegistryObject<SoundEvent> PACK_REVEAL_SSR = register("tarot_pack_reveal_ssr");
    public static final RegistryObject<SoundEvent> PACK_REVEAL_UR = register("tarot_pack_reveal_ur");
    public static final RegistryObject<SoundEvent> PACK_REVEAL_SHINY =
            register("tarot_pack_reveal_shiny");
    public static final RegistryObject<SoundEvent> PACK_COMPLETE = register("tarot_pack_complete");

    private TarotSounds() {
    }

    private static RegistryObject<SoundEvent> register(String id) {
        return SOUND_EVENTS.register(id, () -> SoundEvent.createVariableRangeEvent(
                new ResourceLocation(MiningConstants.MODID, id)));
    }

    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }

    public static SoundEvent castReveal(TarotQuality quality) {
        return switch (quality) {
            case R -> CAST_REVEAL_R.get();
            case SR -> CAST_REVEAL_SR.get();
            case SSR -> CAST_REVEAL_SSR.get();
            case UR -> CAST_REVEAL_UR.get();
            case SHINY -> CAST_REVEAL_SHINY.get();
        };
    }

    public static SoundEvent packReveal(TarotQuality quality) {
        return switch (quality) {
            case R -> PACK_REVEAL_R.get();
            case SR -> PACK_REVEAL_SR.get();
            case SSR -> PACK_REVEAL_SSR.get();
            case UR -> PACK_REVEAL_UR.get();
            case SHINY -> PACK_REVEAL_SHINY.get();
        };
    }
}
