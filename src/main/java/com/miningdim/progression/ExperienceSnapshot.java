package com.miningdim.progression;

/** Current server-authoritative state of one experience track for one player. */
public record ExperienceSnapshot(long totalXp, int level) {
    public ExperienceSnapshot {
        if (totalXp < 0L) {
            throw new IllegalArgumentException("totalXp must be >= 0, got " + totalXp);
        }
        if (level < 0) {
            throw new IllegalArgumentException("level must be >= 0, got " + level);
        }
    }
}
