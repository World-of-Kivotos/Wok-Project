package com.miningdim.power.compat.jade;

import com.miningdim.power.machine.MetallurgicPurifierBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/** 提纯机的进度和 FE 缓冲由服务端实体同步。 */
public final class PurifierJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    private static final ResourceLocation UID = new ResourceLocation("miningdim", "power_purifier");
    private static final ResourceLocation PROGRESS_BAR = new ResourceLocation("miningdim", "power_purifier_progress");
    private static final ResourceLocation BUFFER_BAR = new ResourceLocation("miningdim", "power_purifier_buffer");
    private static final ResourceLocation INFUSION_BAR = new ResourceLocation("miningdim", "power_purifier_infusion");
    private static final String DATA_KEY = "miningdim_power_purifier";
    private static final String PROGRESS = "progress";
    private static final String DURATION = "duration";
    private static final String BUFFER = "buffer";
    private static final String BUFFER_CAPACITY = "bufferCapacity";
    private static final String INFUSION_UNITS = "infusionUnits";
    private static final String INFUSION_CAPACITY = "infusionCapacity";
    private static final String RECIPE = "recipe";

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag serverData, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof MetallurgicPurifierBlockEntity purifier)) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putInt(PROGRESS, purifier.progress());
        data.putInt(DURATION, purifier.processingTime());
        data.putInt(BUFFER, purifier.storedFe());
        data.putInt(BUFFER_CAPACITY, purifier.energyCapacity());
        data.putInt(INFUSION_UNITS, purifier.infusionUnits());
        data.putInt(INFUSION_CAPACITY, purifier.infusionCapacity());
        if (purifier.activeRecipeId() != null) {
            data.putString(RECIPE, purifier.activeRecipeId().toString());
        }
        serverData.put(DATA_KEY, data);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag serverData = accessor.getServerData();
        if (!serverData.contains(DATA_KEY)) {
            return;
        }
        CompoundTag data = serverData.getCompound(DATA_KEY);
        PowerJadeText.addProgress(tooltip,
                PowerJadeText.metric("jade.miningdim.power.machine.progress",
                        data.getInt(PROGRESS), data.getInt(DURATION)),
                data.getInt(PROGRESS), data.getInt(DURATION),
                PowerJadeText.PROCESS_BRIGHT, PowerJadeText.PROCESS_DARK, PROGRESS_BAR);
        PowerJadeText.addProgress(tooltip,
                PowerJadeText.metric("jade.miningdim.power.machine.buffer",
                        data.getInt(BUFFER), data.getInt(BUFFER_CAPACITY)),
                data.getInt(BUFFER), data.getInt(BUFFER_CAPACITY),
                PowerJadeText.ENERGY_BRIGHT, PowerJadeText.ENERGY_DARK, BUFFER_BAR);
        PowerJadeText.addProgress(tooltip,
                PowerJadeText.metric("jade.miningdim.power.purifier.infusion",
                        data.getInt(INFUSION_UNITS), data.getInt(INFUSION_CAPACITY)),
                data.getInt(INFUSION_UNITS), data.getInt(INFUSION_CAPACITY),
                PowerJadeText.INFUSION_BRIGHT, PowerJadeText.INFUSION_DARK, INFUSION_BAR);
        if (data.contains(RECIPE)) {
            tooltip.add(PowerJadeText.metric("jade.miningdim.power.purifier.recipe", data.getString(RECIPE)));
        }
    }
}
