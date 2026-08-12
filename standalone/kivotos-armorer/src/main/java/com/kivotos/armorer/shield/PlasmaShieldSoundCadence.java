package com.kivotos.armorer.shield;

import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player pacing for positional shield sounds. Identity checks keep a replacement chest stack from inheriting
 * the previous unit's schedule, while game-time rollback handling makes dimension/test clock changes safe.
 */
public final class PlasmaShieldSoundCadence {

    public static final int HIT_INTERVAL_TICKS = 2;
    public static final int VENT_INTERVAL_TICKS = 70;
    public static final int OVERHEAT_TO_FIRST_VENT_TICKS = 18;

    private final Map<UUID, HitSchedule> hitSchedules = new HashMap<>();
    private final Map<UUID, VentSchedule> ventSchedules = new HashMap<>();

    /** Coalesces high-rate and same-tick segmented hits into one audible and visual response. */
    public boolean shouldEmitHit(UUID playerId, ItemStack stack, long gameTime) {
        HitSchedule previous = hitSchedules.get(playerId);
        if (previous == null || previous.stack() != stack || gameTime < previous.lastObservedGameTime()) {
            hitSchedules.put(playerId, new HitSchedule(
                    stack, gameTime + HIT_INTERVAL_TICKS, gameTime));
            return true;
        }
        if (gameTime < previous.nextHitGameTime()) {
            hitSchedules.put(playerId, new HitSchedule(
                    stack, previous.nextHitGameTime(), gameTime));
            return false;
        }
        hitSchedules.put(playerId, new HitSchedule(
                stack, gameTime + HIT_INTERVAL_TICKS, gameTime));
        return true;
    }

    /** Keeps the warning transient clear before the first emergency steam release. */
    public void onOverheated(UUID playerId, ItemStack stack, long gameTime) {
        long warningEnd = gameTime + OVERHEAT_TO_FIRST_VENT_TICKS;
        ventSchedules.put(playerId, new VentSchedule(
                stack, warningEnd, gameTime));
    }

    /** Called only for a settlement batch that cooled a shield which was already overheated. */
    public boolean shouldPlayVent(UUID playerId, ItemStack stack, long gameTime) {
        VentSchedule previous = ventSchedules.get(playerId);
        if (previous == null || previous.stack() != stack || gameTime < previous.lastObservedGameTime()) {
            ventSchedules.remove(playerId);
            return false;
        }
        if (gameTime < previous.nextVentGameTime()) {
            ventSchedules.put(playerId, new VentSchedule(
                    stack, previous.nextVentGameTime(), gameTime));
            return false;
        }
        ventSchedules.put(playerId, new VentSchedule(
                stack, gameTime + VENT_INTERVAL_TICKS, gameTime));
        return true;
    }

    public void clear(UUID playerId) {
        hitSchedules.remove(playerId);
        ventSchedules.remove(playerId);
    }

    public void clearAll() {
        hitSchedules.clear();
        ventSchedules.clear();
    }

    private record HitSchedule(ItemStack stack, long nextHitGameTime, long lastObservedGameTime) {
    }

    private record VentSchedule(ItemStack stack, long nextVentGameTime, long lastObservedGameTime) {
    }
}

