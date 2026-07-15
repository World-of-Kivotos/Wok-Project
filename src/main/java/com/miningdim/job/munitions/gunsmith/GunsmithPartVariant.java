package com.miningdim.job.munitions.gunsmith;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public enum GunsmithPartVariant {
    BASIC("basic", "gunsmith.variant.basic", "gunsmith.variant.basic.description", 0.0D, 0.0D),
    GEHENNA_HIGH_SPEED_GAS("gehenna_high_speed_gas", "gunsmith.variant.gehenna_high_speed_gas",
            "gunsmith.variant.gehenna_high_speed_gas.description", 0.25D, 1.00D);

    private static final double LEGACY_V3_GEHENNA_MAX_VERTICAL_RECOIL_BONUS = 0.40D;
    private static final double GLOBAL_MIN_COEFFICIENT = GunsmithPartQuality.COMMON.minCoefficient();
    private static final double GLOBAL_MAX_COEFFICIENT = GunsmithPartQuality.LEGENDARY.maxCoefficient();

    private final String id;
    private final String labelKey;
    private final String descriptionKey;
    private final double maxFireRateBonus;
    private final double maxVerticalRecoilBonus;

    GunsmithPartVariant(String id, String labelKey, String descriptionKey,
                        double maxFireRateBonus, double maxVerticalRecoilBonus) {
        this.id = id;
        this.labelKey = labelKey;
        this.descriptionKey = descriptionKey;
        this.maxFireRateBonus = maxFireRateBonus;
        this.maxVerticalRecoilBonus = maxVerticalRecoilBonus;
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

    public String descriptionKey() {
        return descriptionKey;
    }

    public double maxFireRateBonus() {
        return maxFireRateBonus;
    }

    public double maxVerticalRecoilBonus() {
        return maxVerticalRecoilBonus;
    }

    public double fireRateMultiplier(double coefficient) {
        if (!Double.isFinite(coefficient)
                || coefficient < GLOBAL_MIN_COEFFICIENT
                || coefficient > GLOBAL_MAX_COEFFICIENT) {
            throw new IllegalArgumentException("Gunsmith variant coefficient is outside the global quality range: "
                    + coefficient);
        }
        if (maxFireRateBonus == 0.0D) {
            return 1.0D;
        }
        return 1.0D + qualityProgress(coefficient) * maxFireRateBonus;
    }

    public double verticalRecoilMultiplier(double coefficient) {
        return 1.0D + qualityProgress(coefficient) * maxVerticalRecoilBonus;
    }

    double legacyV3VerticalRecoilMultiplier(double coefficient) {
        return switch (this) {
            case BASIC -> 1.0D;
            case GEHENNA_HIGH_SPEED_GAS -> 1.0D
                    + qualityProgress(coefficient) * LEGACY_V3_GEHENNA_MAX_VERTICAL_RECOIL_BONUS;
        };
    }

    private static double qualityProgress(double coefficient) {
        if (!Double.isFinite(coefficient)
                || coefficient < GLOBAL_MIN_COEFFICIENT
                || coefficient > GLOBAL_MAX_COEFFICIENT) {
            throw new IllegalArgumentException("Gunsmith variant coefficient is outside the global quality range: "
                    + coefficient);
        }
        return (coefficient - GLOBAL_MIN_COEFFICIENT)
                / (GLOBAL_MAX_COEFFICIENT - GLOBAL_MIN_COEFFICIENT);
    }

    public double coefficientForStat(GunsmithStat stat, double coefficient) {
        Objects.requireNonNull(stat, "stat");
        if (this == GEHENNA_HIGH_SPEED_GAS && stat == GunsmithStat.RANGE) {
            return 1.0D;
        }
        return coefficient;
    }

    public boolean supports(GunsmithPlatform platform, GunsmithPressPart part) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(part, "part");
        if (!platform.supports(part)) {
            return false;
        }
        return this == BASIC || (this == GEHENNA_HIGH_SPEED_GAS
                && platform == GunsmithPlatform.AR && part == GunsmithPressPart.CORE);
    }

    public static List<GunsmithPartVariant> availableFor(GunsmithPlatform platform, GunsmithPressPart part) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(part, "part");
        List<GunsmithPartVariant> available = new ArrayList<>();
        for (GunsmithPartVariant variant : values()) {
            if (variant.supports(platform, part)) {
                available.add(variant);
            }
        }
        if (available.isEmpty()) {
            throw new IllegalArgumentException("Gunsmith slot has no component variants: "
                    + platform.id() + "/" + part.id());
        }
        return List.copyOf(available);
    }

    public static GunsmithPartVariant byIndex(int index) {
        GunsmithPartVariant[] values = values();
        if (index < 0 || index >= values.length) {
            throw new IllegalArgumentException("Unknown gunsmith part variant index: " + index);
        }
        return values[index];
    }

    public static GunsmithPartVariant byId(String id) {
        for (GunsmithPartVariant variant : values()) {
            if (variant.id.equals(id)) {
                return variant;
            }
        }
        throw new IllegalArgumentException("Unknown gunsmith part variant: " + id);
    }
}
