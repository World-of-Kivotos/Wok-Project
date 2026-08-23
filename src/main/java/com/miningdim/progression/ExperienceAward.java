package com.miningdim.progression;

import net.minecraft.resources.ResourceLocation;

/** Result returned after a grant has passed through the registered track policy and persistence. */
public record ExperienceAward(ResourceLocation trackId, ResourceLocation sourceId,
                              long rawXp, long effectiveXp, ExperienceSnapshot snapshot) {
}
