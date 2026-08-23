package com.miningdim.job;

/**
 * Public job-framework contract for a job-specific daily XP curve.
 *
 * <p>Concrete job modules own their balancing rules and register an implementation through
 * {@link JobXpPolicies}. The framework can then apply the rule without importing the job's
 * implementation package.</p>
 */
public interface JobXpPolicy {

    /** Returns the effective XP granted for the supplied raw XP at the current daily position. */
    double applyDailyDecayExact(double currentDailyXp, long rawXp);

    /** Returns the final full-rate/decay boundary displayed by shared job UI and commands. */
    long dailySoftCap();
}
