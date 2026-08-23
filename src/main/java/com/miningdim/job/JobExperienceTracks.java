package com.miningdim.job;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Stable experience track/source IDs used by the job compatibility adapter. */
public final class JobExperienceTracks {
    private JobExperienceTracks() {
    }

    public static ResourceLocation track(JobId job) {
        return new ResourceLocation(MiningConstants.MODID,
                "job/" + Objects.requireNonNull(job, "job").id());
    }

    /** Source used by job implementations that have not yet adopted a more specific source ID. */
    public static ResourceLocation legacySource(JobId job) {
        return new ResourceLocation(MiningConstants.MODID,
                "legacy/job/" + Objects.requireNonNull(job, "job").id());
    }
}
