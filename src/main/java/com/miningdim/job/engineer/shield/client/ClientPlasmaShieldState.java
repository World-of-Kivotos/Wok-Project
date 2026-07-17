package com.miningdim.job.engineer.shield.client;

import com.miningdim.job.engineer.shield.PlasmaShieldVariant;

import java.util.Optional;

/**
 * Client-only, server-authoritative plasma-shield HUD state.
 *
 * <p>The network handler replaces one immutable snapshot at a time.  Rendering therefore never
 * observes a mixture of fields from two packets, even if packet handling and HUD rendering move
 * between client threads in a future Minecraft version.</p>
 */
public final class ClientPlasmaShieldState {

    private static final int MAX_RECHARGE_DELAY_TICKS = 20 * 60 * 60;
    private static volatile Snapshot current = Snapshot.empty();

    private ClientPlasmaShieldState() {
    }

    /** Replaces the complete HUD state received from the authoritative server. */
    public static void update(boolean active,
                              String variantId,
                              float shield,
                              float maxShield,
                              float heat,
                              float maxHeat,
                              boolean overheated,
                              int rechargeDelayTicks) {
        Optional<PlasmaShieldVariant> variant = PlasmaShieldVariant.fromId(variantId);
        current = new Snapshot(active && variant.isPresent(),
                variant.orElse(PlasmaShieldVariant.NANO_I), shield, maxShield, heat, maxHeat,
                overheated, rechargeDelayTicks);
    }

    /** Clears stale state after unequipping, disconnecting, or changing worlds. */
    public static void clear() {
        current = Snapshot.empty();
    }

    /** Returns one coherent immutable snapshot for the current render frame. */
    public static Snapshot snapshot() {
        return current;
    }

    private static float cleanCapacity(float value) {
        return Float.isFinite(value) && value > 0.0F ? value : 0.0F;
    }

    private static float cleanAndClamp(float value, float maximum) {
        if (!Float.isFinite(value) || value <= 0.0F || maximum <= 0.0F) {
            return 0.0F;
        }
        return Math.min(value, maximum);
    }

    /** Immutable values consumed by {@link PlasmaShieldHudOverlay}. */
    public record Snapshot(boolean active,
                           PlasmaShieldVariant variant,
                           float shield,
                           float maxShield,
                           float heat,
                           float maxHeat,
                           boolean overheated,
                           int rechargeDelayTicks) {

        /**
         * Canonical sanitisation boundary.  Invalid packet floats can never reach percentage or
         * pixel calculations: non-finite/negative values become zero and live values are clamped
         * to their declared capacities.  An invalid variant is rejected before construction and
         * an invalid capacity disables the snapshot.
         */
        public Snapshot {
            maxShield = cleanCapacity(maxShield);
            maxHeat = cleanCapacity(maxHeat);
            active = active && maxShield > 0.0F && maxHeat > 0.0F;

            if (!active) {
                variant = PlasmaShieldVariant.NANO_I;
                shield = 0.0F;
                maxShield = 0.0F;
                heat = 0.0F;
                maxHeat = 0.0F;
                overheated = false;
                rechargeDelayTicks = 0;
            } else {
                shield = cleanAndClamp(shield, maxShield);
                heat = cleanAndClamp(heat, maxHeat);
                rechargeDelayTicks = Math.min(Math.max(0, rechargeDelayTicks),
                        MAX_RECHARGE_DELAY_TICKS);
            }
        }

        private static Snapshot empty() {
            return new Snapshot(false, PlasmaShieldVariant.NANO_I, 0.0F, 0.0F,
                    0.0F, 0.0F, false, 0);
        }
    }
}
