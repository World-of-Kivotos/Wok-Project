package com.miningdim.progression;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.Set;

/**
 * Server-wide experience contract used by every gameplay module.
 *
 * <p>Tracks identify progression destinations; sources identify why XP was awarded. This keeps
 * balancing, auditing and future global/season progression in one route without forcing existing
 * job data to change its NBT layout.</p>
 */
public interface IExperienceService {
    void registerTrack(ResourceLocation trackId, ExperienceTrackHandler handler);

    void registerSource(ResourceLocation sourceId, ResourceLocation trackId);

    boolean hasTrack(ResourceLocation trackId);

    boolean hasSource(ResourceLocation sourceId);

    Set<ResourceLocation> trackIds();

    ExperienceSnapshot snapshot(Player player, ResourceLocation trackId);

    ExperienceAward award(Player player, ExperienceGrant grant);
}
