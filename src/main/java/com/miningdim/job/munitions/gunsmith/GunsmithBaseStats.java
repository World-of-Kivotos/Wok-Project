package com.miningdim.job.munitions.gunsmith;

public record GunsmithBaseStats(double damage, double headshot, int rpm, double adsTime) {

    public GunsmithBaseStats {
        requirePositiveFinite(damage, "damage");
        requirePositiveFinite(headshot, "headshot");
        if (rpm <= 0) {
            throw new IllegalArgumentException("rpm must be positive");
        }
        requirePositiveFinite(adsTime, "adsTime");
    }

    private static void requirePositiveFinite(double value, String field) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException(field + " must be positive and finite");
        }
    }
}
