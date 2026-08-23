package com.miningdim.job.chef;

import com.miningdim.core.Subsystem;
import com.miningdim.progression.ExperienceServices;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Public composition root for the WOK chef module. */
public final class ChefModule implements Subsystem {
    public static final String MODULE_ID = "wok-job-chef";

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/job/chef");

    private final ChefSystem runtime = new ChefSystem();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ExperienceServices.experienceService().registerSource(
                ChefExperience.SEASONING_COMPLETE_SOURCE, ChefExperience.TRACK_ID);
        runtime.register(modBus, forgeBus);
        LOGGER.info("[miningdim] {} registered", MODULE_ID);
    }

    @Override
    public String name() {
        return MODULE_ID;
    }
}
