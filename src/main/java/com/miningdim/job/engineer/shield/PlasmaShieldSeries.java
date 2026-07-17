package com.miningdim.job.engineer.shield;

import java.util.Locale;
import java.util.Optional;

/** Stable family identity shared by all six grades of one plasma-shield design. */
public enum PlasmaShieldSeries {
    NANO("nano"),
    STANDARD("standard"),
    QUANTUM("quantum");

    private final String id;

    PlasmaShieldSeries(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "series.miningdim.plasma_shield." + id;
    }

    public static Optional<PlasmaShieldSeries> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        for (PlasmaShieldSeries series : values()) {
            if (series.id.equals(normalized)) {
                return Optional.of(series);
            }
        }
        return Optional.empty();
    }
}
