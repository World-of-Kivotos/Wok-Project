package com.miningdim.job.engineer.shield;

import java.util.Locale;
import java.util.Optional;

/** The trusted registry/network identity of one shield family and grade combination. */
public enum PlasmaShieldVariant {
    NANO_I(PlasmaShieldSeries.NANO, PlasmaShieldTier.I, "nano"),
    NANO_II(PlasmaShieldSeries.NANO, PlasmaShieldTier.II),
    NANO_III(PlasmaShieldSeries.NANO, PlasmaShieldTier.III),
    NANO_IV(PlasmaShieldSeries.NANO, PlasmaShieldTier.IV),
    NANO_V(PlasmaShieldSeries.NANO, PlasmaShieldTier.V),
    NANO_VI(PlasmaShieldSeries.NANO, PlasmaShieldTier.VI),

    STANDARD_I(PlasmaShieldSeries.STANDARD, PlasmaShieldTier.I, "light"),
    STANDARD_II(PlasmaShieldSeries.STANDARD, PlasmaShieldTier.II),
    STANDARD_III(PlasmaShieldSeries.STANDARD, PlasmaShieldTier.III),
    STANDARD_IV(PlasmaShieldSeries.STANDARD, PlasmaShieldTier.IV),
    STANDARD_V(PlasmaShieldSeries.STANDARD, PlasmaShieldTier.V),
    STANDARD_VI(PlasmaShieldSeries.STANDARD, PlasmaShieldTier.VI),

    QUANTUM_I(PlasmaShieldSeries.QUANTUM, PlasmaShieldTier.I, "heavy_ion"),
    QUANTUM_II(PlasmaShieldSeries.QUANTUM, PlasmaShieldTier.II),
    QUANTUM_III(PlasmaShieldSeries.QUANTUM, PlasmaShieldTier.III),
    QUANTUM_IV(PlasmaShieldSeries.QUANTUM, PlasmaShieldTier.IV),
    QUANTUM_V(PlasmaShieldSeries.QUANTUM, PlasmaShieldTier.V),
    QUANTUM_VI(PlasmaShieldSeries.QUANTUM, PlasmaShieldTier.VI);

    private final PlasmaShieldSeries series;
    private final PlasmaShieldTier tier;
    private final String id;
    private final String configId;

    PlasmaShieldVariant(PlasmaShieldSeries series, PlasmaShieldTier tier) {
        this(series, tier, null);
    }

    PlasmaShieldVariant(PlasmaShieldSeries series, PlasmaShieldTier tier, String legacyConfigId) {
        this.series = series;
        this.tier = tier;
        this.id = series.id() + "_" + tier.id();
        this.configId = legacyConfigId == null ? id : legacyConfigId;
    }

    public PlasmaShieldSeries series() {
        return series;
    }

    public PlasmaShieldTier tier() {
        return tier;
    }

    public String id() {
        return id;
    }

    /** Tier-I retains the original config paths so existing server tuning remains effective. */
    public String configId() {
        return configId;
    }

    public String itemId() {
        return "plasma_shield_" + id;
    }

    public String translationKey() {
        return "item.miningdim." + itemId();
    }

    public static Optional<PlasmaShieldVariant> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        for (PlasmaShieldVariant variant : values()) {
            if (variant.id.equals(normalized)) {
                return Optional.of(variant);
            }
        }
        return Optional.empty();
    }

    public static PlasmaShieldVariant of(PlasmaShieldSeries series, PlasmaShieldTier tier) {
        for (PlasmaShieldVariant variant : values()) {
            if (variant.series == series && variant.tier == tier) {
                return variant;
            }
        }
        throw new IllegalArgumentException("unregistered plasma shield family/tier: " + series + "/" + tier);
    }
}
