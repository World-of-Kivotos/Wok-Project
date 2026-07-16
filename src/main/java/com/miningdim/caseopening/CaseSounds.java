package com.miningdim.caseopening;

import com.miningdim.core.MiningConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Sound event slots used by the case-opening UI. */
public final class CaseSounds {

    private static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MiningConstants.MODID);

    public static final RegistryObject<SoundEvent> UNLOCK = register("case_unlock");
    public static final RegistryObject<SoundEvent> OPEN = register("case_open");
    public static final RegistryObject<SoundEvent> TICK = register("case_tick");
    public static final RegistryObject<SoundEvent> REVEAL_BLUE = register("case_reveal_blue");
    public static final RegistryObject<SoundEvent> REVEAL_PURPLE = register("case_reveal_purple");
    public static final RegistryObject<SoundEvent> REVEAL_PINK = register("case_reveal_pink");
    public static final RegistryObject<SoundEvent> REVEAL_RED = register("case_reveal_red");
    public static final RegistryObject<SoundEvent> REVEAL_GOLD = register("case_reveal_gold");

    private static final Map<String, RegistryObject<SoundEvent>> CUES = Map.of(
            "unlock", UNLOCK,
            "open", OPEN,
            "tick", TICK,
            "reveal_blue", REVEAL_BLUE,
            "reveal_purple", REVEAL_PURPLE,
            "reveal_pink", REVEAL_PINK,
            "reveal_red", REVEAL_RED,
            "reveal_gold", REVEAL_GOLD
    );

    private static final List<SoundInstance> ACTIVE_UI_SOUNDS = new ArrayList<>();

    private CaseSounds() {
    }

    private static RegistryObject<SoundEvent> register(String id) {
        return SOUND_EVENTS.register(id, () -> SoundEvent.createVariableRangeEvent(
                new ResourceLocation(MiningConstants.MODID, id)));
    }

    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }

    @OnlyIn(Dist.CLIENT)
    public static void playClient(String cue) {
        RegistryObject<SoundEvent> sound = CUES.get(cue);
        if (sound == null || !sound.isPresent()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        SoundInstance instance = SimpleSoundInstance.forUI(sound.get(), 1.0F);
        minecraft.getSoundManager().play(instance);
        synchronized (ACTIVE_UI_SOUNDS) {
            ACTIVE_UI_SOUNDS.removeIf(existing -> !minecraft.getSoundManager().isActive(existing));
            ACTIVE_UI_SOUNDS.add(instance);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static void stopClient() {
        Minecraft minecraft = Minecraft.getInstance();
        synchronized (ACTIVE_UI_SOUNDS) {
            ACTIVE_UI_SOUNDS.forEach(minecraft.getSoundManager()::stop);
            ACTIVE_UI_SOUNDS.clear();
        }
    }
}
