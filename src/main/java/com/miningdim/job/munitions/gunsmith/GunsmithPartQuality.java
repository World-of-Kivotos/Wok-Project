package com.miningdim.job.munitions.gunsmith;

import net.minecraft.util.RandomSource;

public enum GunsmithPartQuality {
    COMMON("common", "gunsmith.quality.common", 1, 600, 0.96D, 1.04D),
    IMPROVED("improved", "gunsmith.quality.improved", 2, 1200, 1.05D, 1.12D),
    MILSPEC("milspec", "gunsmith.quality.milspec", 4, 2400, 1.13D, 1.22D),
    PRECISION("precision", "gunsmith.quality.precision", 7, 4800, 1.23D, 1.35D),
    LEGENDARY("legendary", "gunsmith.quality.legendary", 10, 7200, 1.36D, 1.50D);

    private final String id;
    private final String labelKey;
    private final int materialMultiplier;
    private final int requiredTicks;
    private final double minCoefficient;
    private final double maxCoefficient;

    GunsmithPartQuality(String id, String labelKey, int materialMultiplier, int requiredTicks,
                        double minCoefficient, double maxCoefficient) {
        this.id = id;
        this.labelKey = labelKey;
        this.materialMultiplier = materialMultiplier;
        this.requiredTicks = requiredTicks;
        this.minCoefficient = minCoefficient;
        this.maxCoefficient = maxCoefficient;
    }

    public int index() {
        return ordinal();
    }

    public String id() {
        return id;
    }

    public String labelKey() {
        return labelKey;
    }

    public int materialMultiplier() {
        return materialMultiplier;
    }

    public int requiredTicks() {
        return requiredTicks;
    }

    public double minCoefficient() {
        return minCoefficient;
    }

    public double maxCoefficient() {
        return maxCoefficient;
    }

    public double midpointCoefficient() {
        return (minCoefficient + maxCoefficient) / 2.0D;
    }

    public double rollCoefficient(RandomSource random) {
        RandomSource source = random == null ? RandomSource.create() : random;
        return minCoefficient + source.nextDouble() * (maxCoefficient - minCoefficient);
    }

    public static GunsmithPartQuality byIndex(int index) {
        GunsmithPartQuality[] values = values();
        if (index < 0 || index >= values.length) {
            throw new IllegalArgumentException("Unknown gunsmith part quality index: " + index);
        }
        return values[index];
    }

    public static GunsmithPartQuality byId(String id) {
        for (GunsmithPartQuality quality : values()) {
            if (quality.id.equals(id)) {
                return quality;
            }
        }
        throw new IllegalArgumentException("Unknown gunsmith part quality: " + id);
    }
}
