package com.miningdim.job.engineer.shield;

import java.util.Locale;
import java.util.Optional;

/** Six ordered shield grades. Declaration order is part of the balance/config contract. */
public enum PlasmaShieldTier {
    I("i"),
    II("ii"),
    III("iii"),
    IV("iv"),
    V("v"),
    VI("vi");

    private final String id;

    PlasmaShieldTier(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "tier.miningdim.plasma_shield." + id;
    }

    public static Optional<PlasmaShieldTier> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        for (PlasmaShieldTier tier : values()) {
            if (tier.id.equals(normalized)) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}
