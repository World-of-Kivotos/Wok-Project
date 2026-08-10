package com.kivotos.armorer.shield;

import java.util.Locale;
import java.util.Optional;

/** Legacy item identities retained so old worlds and commands keep resolving after the 18-variant expansion. */
public enum PlasmaShieldType {
    NANO("nano", PlasmaShieldVariant.NANO_I),
    LIGHT("light", PlasmaShieldVariant.STANDARD_I),
    HEAVY_ION("heavy_ion", PlasmaShieldVariant.QUANTUM_I);

    private final String id;
    private final PlasmaShieldVariant variant;

    PlasmaShieldType(String id, PlasmaShieldVariant variant) {
        this.id = id;
        this.variant = variant;
    }

    public String id() {
        return id;
    }

    public String itemId() {
        return "plasma_shield_" + id;
    }

    public PlasmaShieldVariant variant() {
        return variant;
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

