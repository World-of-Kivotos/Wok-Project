package com.miningdim.job.engineer.shield;

import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player pacing for positional shield sounds. Identity checks keep a replacement chest stack from inheriting
 * the previous unit's schedule, while game-time rollback handling makes dimension/test clock changes safe.
 */
public final class PlasmaShieldSoundCadence {

    public static final int VENT_INTERVAL_TICKS = 40;
    public static final int OVERHEAT_TO_FIRST_VENT_TICKS = 30;

    private final Map<UUID, VentSchedule> ventSchedules = new HashMap<>();

    /** Keeps the warning transient clear before the first emergency steam release. */
    public void onOverheated(UUID playerId, ItemStack stack, long gameTime) {
        VentSchedule previous = ventSchedules.get(playerId);
        long warningEnd = gameTime + OVERHEAT_TO_FIRST_VENT_TICKS;
        long nextVent = previous != null
                && previous.stack() == stack
                && gameTime >= previous.lastObservedGameTime()
                ? Math.max(warningEnd, previous.nextVentGameTime())
                : warningEnd;
        ventSchedules.put(playerId, new VentSchedule(
                stack, nextVent, gameTime));
    }

    /** Called only for a settlement batch that actually reduced heat. */
    public boolean shouldPlayVent(UUID playerId, ItemStack stack, long gameTime) {
        VentSchedule previous = ventSchedules.get(playerId);
        if (previous == null || previous.stack() != stack || gameTime < previous.lastObservedGameTime()) {
            ventSchedules.put(playerId, new VentSchedule(
                    stack, gameTime + VENT_INTERVAL_TICKS, gameTime));
            return true;
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
        ventSchedules.remove(playerId);
    }

    public void clearAll() {
        ventSchedules.clear();
    }

    private record VentSchedule(ItemStack stack, long nextVentGameTime, long lastObservedGameTime) {
    }
}
