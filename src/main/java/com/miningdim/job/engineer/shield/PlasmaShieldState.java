package com.miningdim.job.engineer.shield;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** Pure shield/heat state transition plus versioned ItemStack NBT persistence. */
public record PlasmaShieldState(double shield,
                                double totalEnergy,
                                double heat,
                                boolean overheated,
                                int rechargeDelayTicks,
                                int heatCoolDelayTicks) {

    public static final String ROOT_KEY = "MiningDimPlasmaShield";
    private static final int VERSION = 2;
    private static final double EPSILON = 1.0E-7D;

    private static final String K_VERSION = "version";
    private static final String K_SHIELD = "shield";
    private static final String K_TOTAL_ENERGY = "totalEnergy";
    private static final String K_HEAT = "heat";
    private static final String K_OVERHEATED = "overheated";
    private static final String K_RECHARGE_DELAY = "rechargeDelayTicks";
    private static final String K_HEAT_COOL_DELAY = "heatCoolDelayTicks";

    public static PlasmaShieldState full(PlasmaShieldConfig.Stats stats) {
        return new PlasmaShieldState(
                stats.capacity(), stats.maxTotalEnergy(), 0.0D, false, 0, 0);
    }

    /** Reads without mutating the stack. A new or legacy stack starts with a full battery. */
    public static PlasmaShieldState read(ItemStack stack, PlasmaShieldConfig.Stats stats) {
        CompoundTag root = stack.getTagElement(ROOT_KEY);
        if (root == null) {
            return full(stats);
        }
        double shield = number(root, K_SHIELD, stats.capacity());
        double totalEnergy = root.getInt(K_VERSION) >= VERSION
                ? number(root, K_TOTAL_ENERGY, stats.maxTotalEnergy())
                : migratedTotalEnergy(shield, stats);
        double heat = number(root, K_HEAT, 0.0D);
        boolean overheated = root.getBoolean(K_OVERHEATED);
        int rechargeDelay = integer(root, K_RECHARGE_DELAY, 0);
        int heatCoolDelay = integer(root, K_HEAT_COOL_DELAY, 0);
        return sanitize(new PlasmaShieldState(
                shield, totalEnergy, heat, overheated, rechargeDelay, heatCoolDelay), stats);
    }

    /** Initializes missing NBT and clamps old/corrupt/config-shrunk values. */
    public static PlasmaShieldState initialize(ItemStack stack, PlasmaShieldConfig.Stats stats) {
        PlasmaShieldState state = read(stack, stats);
        CompoundTag root = stack.getTagElement(ROOT_KEY);
        if (!matchesPersistedState(root, state)) {
            write(stack, state);
        }
        return state;
    }

    public static void write(ItemStack stack, PlasmaShieldState state) {
        CompoundTag root = stack.getOrCreateTagElement(ROOT_KEY);
        root.putInt(K_VERSION, VERSION);
        root.putDouble(K_SHIELD, state.shield);
        root.putDouble(K_TOTAL_ENERGY, state.totalEnergy);
        root.putDouble(K_HEAT, state.heat);
        root.putBoolean(K_OVERHEATED, state.overheated);
        root.putInt(K_RECHARGE_DELAY, state.rechargeDelayTicks);
        root.putInt(K_HEAT_COOL_DELAY, state.heatCoolDelayTicks);
    }

    /**
     * Absorption is raw one-to-one damage conversion: one absorbed damage consumes one shield energy, with no
     * protection multiplier. Total energy includes the amount currently allocated to the shield layer, so damage
     * lowers shield and total energy together without double-charging the battery. Remaining shield, total energy,
     * and thermal budget are the only bounds, so one combined hit is equivalent to the same hit split into several
     * TaCZ damage segments.
     */
    public static HitResult absorb(PlasmaShieldState input,
                                   PlasmaShieldConfig.Stats stats,
                                   double incomingDamage) {
        PlasmaShieldState state = sanitize(input, stats);
        if (!Double.isFinite(incomingDamage) || incomingDamage <= 0.0D) {
            return new HitResult(state, 0.0D, 0.0D);
        }

        int rechargeDelay = stats.rechargeDelayTicks();
        int heatCoolDelay = state.overheated ? state.heatCoolDelayTicks : stats.heatCoolDelayTicks();
        if (state.overheated || state.shield <= EPSILON || state.totalEnergy <= EPSILON) {
            PlasmaShieldState delayed = new PlasmaShieldState(
                    state.shield, state.totalEnergy, state.heat,
                    state.overheated, rechargeDelay, heatCoolDelay);
            return new HitResult(delayed, 0.0D, incomingDamage);
        }

        double thermalAllowance = Math.max(0.0D,
                (stats.maxHeat() - state.heat) / stats.heatPerDamage());
        double absorbed = Math.min(incomingDamage,
                Math.min(state.shield, Math.min(state.totalEnergy, thermalAllowance)));
        absorbed = Math.max(0.0D, absorbed);

        double nextShield = clamp(state.shield - absorbed, 0.0D, stats.capacity());
        double nextTotalEnergy = clamp(
                state.totalEnergy - absorbed, 0.0D, stats.maxTotalEnergy());
        double nextHeat = clamp(state.heat + absorbed * stats.heatPerDamage(), 0.0D, stats.maxHeat());
        boolean nextOverheated = state.overheated || nextHeat >= stats.maxHeat() - EPSILON;
        if (nextOverheated && nextHeat >= stats.maxHeat() - EPSILON) {
            nextHeat = stats.maxHeat();
        }
        PlasmaShieldState next = new PlasmaShieldState(
                nextShield, nextTotalEnergy, nextHeat,
                nextOverheated, rechargeDelay, heatCoolDelay);
        return new HitResult(next, absorbed, Math.max(0.0D, incomingDamage - absorbed));
    }

    /** Advances cooling and recharge by an exact number of server ticks. */
    public static PlasmaShieldState tick(PlasmaShieldState input,
                                         PlasmaShieldConfig.Stats stats,
                                         int elapsedTicks) {
        PlasmaShieldState state = sanitize(input, stats);
        if (elapsedTicks <= 0) {
            return state;
        }

        double shield = state.shield;
        double totalEnergy = state.totalEnergy;
        double heat = state.heat;
        boolean overheated = state.overheated;
        int rechargeDelay = state.rechargeDelayTicks;
        int heatCoolDelay = state.heatCoolDelayTicks;
        double coolingPerTick = stats.coolingPerSecond() / 20.0D;
        double rechargePerTick = stats.rechargePerSecond() / 20.0D;

        // The configured settlement interval is at most 20 ticks. Stepping its real tick boundaries keeps
        // tick(state, N) exactly equivalent to N calls of tick(state, 1), including delay expiry and restart.
        for (int tick = 0; tick < elapsedTicks; tick++) {
            boolean coolingReady = overheated || heatCoolDelay == 0;
            boolean rechargeReady = !overheated && rechargeDelay == 0;

            if (rechargeDelay > 0) {
                rechargeDelay--;
            }
            if (heatCoolDelay > 0) {
                heatCoolDelay--;
            }
            if (coolingReady) {
                heat = Math.max(0.0D, heat - coolingPerTick);
            }
            if (overheated && heat <= stats.restartHeat() + EPSILON) {
                overheated = false;
            }
            if (rechargeReady) {
                double availableReserve = Math.max(0.0D, totalEnergy - shield);
                shield += Math.min(rechargePerTick,
                        Math.min(stats.capacity() - shield, availableReserve));
            }
        }
        return sanitize(new PlasmaShieldState(
                shield, totalEnergy, heat, overheated, rechargeDelay, heatCoolDelay), stats);
    }

    public static PlasmaShieldState sanitize(PlasmaShieldState state, PlasmaShieldConfig.Stats stats) {
        double shield = finiteClamp(state.shield, 0.0D, stats.capacity(), stats.capacity());
        double totalEnergy = finiteClamp(
                state.totalEnergy, 0.0D, stats.maxTotalEnergy(), stats.maxTotalEnergy());
        shield = Math.min(shield, totalEnergy);
        double heat = finiteClamp(state.heat, 0.0D, stats.maxHeat(), 0.0D);
        boolean overheated = state.overheated || heat >= stats.maxHeat() - EPSILON;
        int rechargeDelay = Math.max(0, state.rechargeDelayTicks);
        int heatCoolDelay = Math.max(0, state.heatCoolDelayTicks);
        return new PlasmaShieldState(
                shield, totalEnergy, heat, overheated, rechargeDelay, heatCoolDelay);
    }

    private static double number(CompoundTag tag, String key, double fallback) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            return fallback;
        }
        double value = tag.getDouble(key);
        return Double.isFinite(value) ? value : fallback;
    }

    private static int integer(CompoundTag tag, String key, int fallback) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            return fallback;
        }
        return Math.max(0, tag.getInt(key));
    }

    private static boolean matchesPersistedState(CompoundTag root, PlasmaShieldState state) {
        if (root == null
                || root.getInt(K_VERSION) != VERSION
                || !root.contains(K_SHIELD, Tag.TAG_ANY_NUMERIC)
                || !root.contains(K_TOTAL_ENERGY, Tag.TAG_ANY_NUMERIC)
                || !root.contains(K_HEAT, Tag.TAG_ANY_NUMERIC)
                || !root.contains(K_OVERHEATED, Tag.TAG_BYTE)
                || !root.contains(K_RECHARGE_DELAY, Tag.TAG_ANY_NUMERIC)
                || !root.contains(K_HEAT_COOL_DELAY, Tag.TAG_ANY_NUMERIC)) {
            return false;
        }
        return Double.compare(root.getDouble(K_SHIELD), state.shield) == 0
                && Double.compare(root.getDouble(K_TOTAL_ENERGY), state.totalEnergy) == 0
                && Double.compare(root.getDouble(K_HEAT), state.heat) == 0
                && root.getBoolean(K_OVERHEATED) == state.overheated
                && root.getInt(K_RECHARGE_DELAY) == state.rechargeDelayTicks
                && root.getInt(K_HEAT_COOL_DELAY) == state.heatCoolDelayTicks;
    }

    private static double finiteClamp(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) ? clamp(value, minimum, maximum) : fallback;
    }

    private static double migratedTotalEnergy(double shield, PlasmaShieldConfig.Stats stats) {
        double sanitizedShield = finiteClamp(shield, 0.0D, stats.capacity(), stats.capacity());
        double spentFromVisibleLayer = stats.capacity() - sanitizedShield;
        return Math.max(sanitizedShield, stats.maxTotalEnergy() - spentFromVisibleLayer);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record HitResult(PlasmaShieldState state, double absorbedDamage, double remainingDamage) {
    }
}
