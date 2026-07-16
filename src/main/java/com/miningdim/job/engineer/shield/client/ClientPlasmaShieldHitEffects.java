package com.miningdim.job.engineer.shield.client;

import com.miningdim.job.engineer.shield.PlasmaShieldType;
import com.miningdim.job.engineer.shield.PlasmaShieldVisualProfile;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Client-only cache of short-lived, server-authoritative shield hit flashes. */
public final class ClientPlasmaShieldHitEffects {

    private static final Map<Integer, ActiveEffect> ACTIVE = new HashMap<>();

    private ClientPlasmaShieldHitEffects() {
    }

    public static void accept(int entityId, String typeId, float strength, boolean overloaded) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        PlasmaShieldType type = PlasmaShieldType.fromId(typeId).orElseThrow();
        ActiveEffect previous = ACTIVE.get(entityId);
        float mergedStrength = previous == null
                ? strength
                : Math.max(strength, previous.strength() * 0.75F);
        ACTIVE.put(entityId, new ActiveEffect(
                type, mergedStrength, overloaded, minecraft.level.getGameTime()));
    }

    public static List<Frame> frames(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            clear();
            return List.of();
        }
        float now = minecraft.level.getGameTime() + partialTick;
        List<Frame> frames = new ArrayList<>(ACTIVE.size());
        Iterator<Map.Entry<Integer, ActiveEffect>> iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, ActiveEffect> entry = iterator.next();
            ActiveEffect effect = entry.getValue();
            float age = now - effect.startTick();
            if (age >= PlasmaShieldVisualProfile.durationTicks(effect.overloaded())) {
                iterator.remove();
                continue;
            }
            frames.add(new Frame(
                    entry.getKey(), effect.type(), effect.strength(), effect.overloaded(), age));
        }
        return frames;
    }

    public static Frame frameFor(int entityId, float partialTick) {
        for (Frame frame : frames(partialTick)) {
            if (frame.entityId() == entityId) {
                return frame;
            }
        }
        return null;
    }

    public static void remove(int entityId) {
        ACTIVE.remove(entityId);
    }

    public static void clear() {
        ACTIVE.clear();
    }

    private record ActiveEffect(PlasmaShieldType type,
                                float strength,
                                boolean overloaded,
                                long startTick) {
    }

    public record Frame(int entityId,
                        PlasmaShieldType type,
                        float strength,
                        boolean overloaded,
                        float ageTicks) {

        public float alpha() {
            return PlasmaShieldVisualProfile.alpha(ageTicks, strength, overloaded);
        }

        public float scale() {
            return PlasmaShieldVisualProfile.scale(ageTicks, strength, overloaded);
        }
    }
}
