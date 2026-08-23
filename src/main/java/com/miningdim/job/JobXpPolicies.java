package com.miningdim.job;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry and router for job-owned daily-XP policies.
 *
 * <p>The default curve remains {@link JobXpCurve}. A concrete job module may register an
 * override during mod construction. Reads use an immutable snapshot so gameplay threads never
 * observe a partially updated registry.</p>
 */
public final class JobXpPolicies {
    private static final JobXpPolicy DEFAULT_POLICY = new JobXpPolicy() {
        @Override
        public double applyDailyDecayExact(double currentDailyXp, long rawXp) {
            return JobXpCurve.applyDailyDecayExact(currentDailyXp, rawXp);
        }

        @Override
        public long dailySoftCap() {
            return JobXpCurve.DAILY_SOFTCAP;
        }
    };

    private static volatile Map<JobId, JobXpPolicy> policies = Map.of();

    private JobXpPolicies() {
    }

    /**
     * Registers a policy owned by a concrete job module.
     *
     * <p>Registering the same singleton twice is harmless, which keeps repeated test bootstrap
     * deterministic. Registering a different policy for an occupied job fails fast.</p>
     */
    public static synchronized void register(JobId job, JobXpPolicy policy) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(policy, "policy");
        JobXpPolicy existing = policies.get(job);
        if (existing == policy) {
            return;
        }
        if (existing != null) {
            throw new IllegalStateException("XP policy already registered for job " + job.id());
        }
        EnumMap<JobId, JobXpPolicy> updated = new EnumMap<>(JobId.class);
        updated.putAll(policies);
        updated.put(job, policy);
        policies = Map.copyOf(updated);
    }

    public static double applyDailyDecayExact(JobId job, double currentDailyXp, long rawXp) {
        return policy(job).applyDailyDecayExact(currentDailyXp, rawXp);
    }

    public static long dailySoftCap(JobId job) {
        return policy(job).dailySoftCap();
    }

    /** Returns whether a concrete module has replaced the shared default for this job. */
    public static boolean hasCustomPolicy(JobId job) {
        return policies.containsKey(Objects.requireNonNull(job, "job"));
    }

    private static JobXpPolicy policy(JobId job) {
        return policies.getOrDefault(Objects.requireNonNull(job, "job"), DEFAULT_POLICY);
    }
}
