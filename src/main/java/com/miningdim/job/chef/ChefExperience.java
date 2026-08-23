package com.miningdim.job.chef;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobExperienceTracks;
import com.miningdim.job.JobId;
import com.miningdim.progression.ExperienceGrant;
import com.miningdim.progression.ExperienceServices;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/** Chef-owned experience route declarations. */
final class ChefExperience {
    static final ResourceLocation TRACK_ID = JobExperienceTracks.track(JobId.CHEF);
    static final ResourceLocation SEASONING_COMPLETE_SOURCE =
            new ResourceLocation(MiningConstants.MODID, "chef/seasoning_complete");

    private ChefExperience() {
    }

    static int level(Player player) {
        return ExperienceServices.experienceService().snapshot(player, TRACK_ID).level();
    }

    static long awardSeasoningComplete(Player player, long rawXp) {
        return ExperienceServices.experienceService()
                .award(player, new ExperienceGrant(TRACK_ID, SEASONING_COMPLETE_SOURCE, rawXp))
                .effectiveXp();
    }
}
