package com.miningdim.job.engineer.shield;

import java.util.Locale;
import java.util.Optional;

/** The three plasma-shield chassis. The equipped item, not NBT, is the type authority. */
public enum PlasmaShieldType {
    NANO("nano"),
    LIGHT("light"),
    HEAVY_ION("heavy_ion");

    private final String id;

    PlasmaShieldType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "type.miningdim.plasma_shield." + id;
    }

    public String itemId() {
        return "plasma_shield_" + id;
    }

    public static Optional<PlasmaShieldType> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        for (PlasmaShieldType type : values()) {
            if (type.id.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
