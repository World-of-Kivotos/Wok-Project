package com.miningdim.job;

import com.miningdim.job.farmer.FarmerXpCurve;

/** Routes each job through its documented daily-XP policy. */
public final class JobXpPolicies {
    private JobXpPolicies() {
    }

    public static double applyDailyDecayExact(JobId job, double currentDailyXp, long rawXp) {
        return job == JobId.FARMER
                ? FarmerXpCurve.applyDailyDecayExact(currentDailyXp, rawXp)
                : JobXpCurve.applyDailyDecayExact(currentDailyXp, rawXp);
    }

    public static long dailySoftCap(JobId job) {
        return job == JobId.FARMER ? FarmerXpCurve.DAILY_SOFTCAP : JobXpCurve.DAILY_SOFTCAP;
    }
}
