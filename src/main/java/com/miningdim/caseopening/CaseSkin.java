package com.miningdim.caseopening;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** One immutable catalogue entry. Ownership is represented by a separate per-opening asset UUID. */
public record CaseSkin(String skinId, String displayName, CaseRarity rarity,
                       ResourceLocation gunId, ResourceLocation displayId) {

    public CaseSkin {
        if (skinId == null || !skinId.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("invalid case skin id: " + skinId);
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("case skin display name must not be blank");
        }
        Objects.requireNonNull(rarity, "rarity");
        Objects.requireNonNull(gunId, "gunId");
        Objects.requireNonNull(displayId, "displayId");
    }

    static CaseSkin create(String skinId, String displayName, CaseRarity rarity, String gunPath) {
        return new CaseSkin(skinId, displayName, rarity,
                new ResourceLocation("tacz", gunPath),
                new ResourceLocation("miningdim", "case_" + skinId + "_display"));
    }
}
