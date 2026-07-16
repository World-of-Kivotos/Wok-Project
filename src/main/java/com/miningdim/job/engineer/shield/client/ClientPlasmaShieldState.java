package com.miningdim.job.engineer.shield.client;

/**
 * Client-only, server-authoritative plasma-shield HUD state.
 *
 * <p>The network handler replaces one immutable snapshot at a time.  Rendering therefore never
 * observes a mixture of fields from two packets, even if packet handling and HUD rendering move
 * between client threads in a future Minecraft version.</p>
 */
public final class ClientPlasmaShieldState {

    public static final String TYPE_NANO = "nano";
    public static final String TYPE_LIGHT = "light";
    public static final String TYPE_HEAVY_ION = "heavy_ion";

    private static final int MAX_RECHARGE_DELAY_TICKS = 20 * 60 * 60;
    private static volatile Snapshot current = Snapshot.empty();

    private ClientPlasmaShieldState() {
    }

    /** Replaces the complete HUD state received from the authoritative server. */
    public static void update(boolean active,
                              String typeId,
                              float shield,
                              float maxShield,
                              float heat,
                              float maxHeat,
                              boolean overheated,
                              int rechargeDelayTicks) {
        current = new Snapshot(active, typeId, shield, maxShield, heat, maxHeat,
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

    private static boolean validType(String typeId) {
        return TYPE_NANO.equals(typeId) || TYPE_LIGHT.equals(typeId) || TYPE_HEAVY_ION.equals(typeId);
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
                           String typeId,
                           float shield,
                           float maxShield,
                           float heat,
                           float maxHeat,
                           boolean overheated,
                           int rechargeDelayTicks) {

        /**
         * Canonical sanitisation boundary.  Invalid packet floats can never reach percentage or
         * pixel calculations: non-finite/negative values become zero and live values are clamped
         * to their declared capacities.  An invalid type or capacity disables the snapshot.
         */
        public Snapshot {
            boolean validType = ClientPlasmaShieldState.validType(typeId);
            maxShield = cleanCapacity(maxShield);
            maxHeat = cleanCapacity(maxHeat);
            active = active && validType && maxShield > 0.0F && maxHeat > 0.0F;

            if (!active) {
                typeId = TYPE_NANO;
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
            return new Snapshot(false, TYPE_NANO, 0.0F, 0.0F,
                    0.0F, 0.0F, false, 0);
        }
    }
}
