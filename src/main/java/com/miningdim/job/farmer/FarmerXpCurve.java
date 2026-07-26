package com.miningdim.job.farmer;

/** Farmer daily XP decay from FarmingXP_Mod_DesignSpec table C. */
public final class FarmerXpCurve {
    private static final long[] BOUNDS = {1_500L, 1_800L, 2_000L, 2_150L};
    private static final double[] MULTIPLIERS = {1.0D, 0.30D, 0.10D, 0.03D};
    private static final double DRIP_MULTIPLIER = 0.005D;
    public static final long DAILY_SOFTCAP = 2_150L;

    private FarmerXpCurve() {
    }

    public static double applyDailyDecayExact(double currentDailyXp, long rawXp) {
        if (currentDailyXp < 0.0D || rawXp < 0L) {
            throw new IllegalArgumentException(
                    "currentDailyXp/rawXp must be >= 0, got " + currentDailyXp + "/" + rawXp);
        }
        double effectivePosition = currentDailyXp;
        double remainingRaw = rawXp;
        double effectiveGained = 0.0D;
        for (int segment = 0; segment < BOUNDS.length && remainingRaw > 0.0D; segment++) {
            if (effectivePosition >= BOUNDS[segment]) {
                continue;
            }
            double effectiveRoom = BOUNDS[segment] - effectivePosition;
            double rawUsed = Math.min(remainingRaw, effectiveRoom / MULTIPLIERS[segment]);
            double gained = rawUsed * MULTIPLIERS[segment];
            effectiveGained += gained;
            effectivePosition += gained;
            remainingRaw -= rawUsed;
        }
        return effectiveGained + remainingRaw * DRIP_MULTIPLIER;
    }

    public static long applyDailyDecay(long currentDailyXp, long rawXp) {
        return Math.round(applyDailyDecayExact(currentDailyXp, rawXp));
    }
}
