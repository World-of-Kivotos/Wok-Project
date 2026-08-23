package com.miningdim.progression;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** A server-authoritative request to award raw XP to one registered progression track. */
public record ExperienceGrant(ResourceLocation trackId, ResourceLocation sourceId, long rawXp) {
    public ExperienceGrant {
        Objects.requireNonNull(trackId, "trackId");
        Objects.requireNonNull(sourceId, "sourceId");
        if (rawXp < 0L) {
            throw new IllegalArgumentException("rawXp must be >= 0, got " + rawXp);
        }
    }
}
