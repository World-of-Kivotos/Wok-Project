package com.miningdim.job.farmer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobExperienceTracks;
import com.miningdim.job.JobId;
import com.miningdim.progression.ExperienceGrant;
import com.miningdim.progression.ExperienceServices;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/** Farmer-owned experience source IDs and access to the server-wide experience route. */
final class FarmerExperience {
    static final ResourceLocation TRACK_ID = JobExperienceTracks.track(JobId.FARMER);
    static final ResourceLocation HARVEST_SOURCE =
            new ResourceLocation(MiningConstants.MODID, "farmer/harvest");
    static final ResourceLocation PICK_SOURCE =
            new ResourceLocation(MiningConstants.MODID, "farmer/pick");

    private FarmerExperience() {
    }

    static int level(Player player) {
        return ExperienceServices.experienceService().snapshot(player, TRACK_ID).level();
    }

    static long awardHarvest(Player player, long rawXp) {
        return award(player, HARVEST_SOURCE, rawXp);
    }

    static long awardPick(Player player, long rawXp) {
        return award(player, PICK_SOURCE, rawXp);
    }

    private static long award(Player player, ResourceLocation sourceId, long rawXp) {
        return ExperienceServices.experienceService()
                .award(player, new ExperienceGrant(TRACK_ID, sourceId, rawXp))
                .effectiveXp();
    }
}
