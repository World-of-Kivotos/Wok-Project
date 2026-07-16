package com.miningdim.caseopening;

import java.util.Locale;

/** The five server-authoritative rarity bands used by the founders case. */
public enum CaseRarity {
    BLUE,
    PURPLE,
    PINK,
    RED,
    GOLD;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CaseRarity byId(String id) {
        for (CaseRarity rarity : values()) {
            if (rarity.id().equalsIgnoreCase(id)) {
                return rarity;
            }
        }
        throw new IllegalArgumentException("unknown case rarity: " + id);
    }
}
