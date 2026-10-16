package com.miningdim.job.farmer;

import com.miningdim.core.Subsystem;
import com.miningdim.job.JobId;
import com.miningdim.job.JobXpPolicies;
import com.miningdim.job.farmer.block.FarmerBlocks;
import com.miningdim.job.farmer.granary.GranaryConfig;
import com.miningdim.job.farmer.granary.GranarySystem;
import com.miningdim.job.farmer.item.FarmerCreativeTab;
import com.miningdim.job.farmer.item.FarmerItems;
import com.miningdim.progression.ExperienceServices;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public composition root for the WOK farmer module.
 *
 * <p>This is the only farmer type the application layer should instantiate. Registry ownership,
 * job-framework bindings and Forge event handlers are assembled here while all existing registry
 * IDs and saved-data keys remain unchanged.</p>
 */
public final class FarmerModule implements Subsystem {
    public static final String MODULE_ID = "wok-job-farmer";

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/job/farmer");

    private final FarmerSystem runtime = new FarmerSystem();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER,
                GranaryConfig.SPEC, "miningdim-farmer.toml");
        JobXpPolicies.register(JobId.FARMER, FarmerXpCurve.POLICY);
        ExperienceServices.experienceService().registerSource(
                FarmerExperience.HARVEST_SOURCE, FarmerExperience.TRACK_ID);
        ExperienceServices.experienceService().registerSource(
                FarmerExperience.PICK_SOURCE, FarmerExperience.TRACK_ID);
        FarmerBlocks.register(modBus);
        FarmerItems.register(modBus);
        FarmerCreativeTab.register(modBus);
        FarmerLootModifiers.register(modBus);
        // 粮仓子包 (job/farmer/granary): 储粮囤方块、归属与额度裁决。一个模块只有一个公开装配入口,
        // 所以粮仓在这里接进来, 不另占一个子系统 (docs/Spoilage_Granary_DesignSpec.md「模块落位」)。
        GranarySystem.register(modBus, forgeBus);
        forgeBus.register(runtime);
        FarmerWebUiActions.registerAll();
        LOGGER.info("[miningdim] {} registered (5 farmland tiers + crop yield + Farmer's Delight + harvest xp "
                + "+ placement cap + /farmer sell + /farmer admin legacy|recount + 2 job.farmer.* actions "
                + "+ granary storage bins with level-gated caps)", MODULE_ID);
    }

    @Override
    public String name() {
        return MODULE_ID;
    }
}
