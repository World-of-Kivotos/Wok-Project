package com.miningdim.power.compat.jade;

import com.miningdim.power.machine.AirSeparationUnitBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/** 空分机的数据与提纯机独立注册，避免同方块类型客户端提示混读。 */
public final class AirSeparatorJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    private static final ResourceLocation UID = new ResourceLocation("miningdim", "power_air_separator");
    private static final ResourceLocation PROGRESS_BAR =
            new ResourceLocation("miningdim", "power_air_separator_progress");
    private static final ResourceLocation BUFFER_BAR =
            new ResourceLocation("miningdim", "power_air_separator_buffer");
    private static final String DATA_KEY = "miningdim_power_air_separator";
    private static final String MODE = "mode";
    private static final String PROGRESS = "progress";
    private static final String DURATION = "duration";
    private static final String BUFFER = "buffer";
    private static final String BUFFER_CAPACITY = "bufferCapacity";

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag serverData, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof AirSeparationUnitBlockEntity separator)) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString(MODE, separator.mode().id());
        data.putInt(PROGRESS, separator.progress());
        data.putInt(DURATION, separator.processingTime());
        data.putInt(BUFFER, separator.storedFe());
        data.putInt(BUFFER_CAPACITY, separator.energyCapacity());
        serverData.put(DATA_KEY, data);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag serverData = accessor.getServerData();
        if (!serverData.contains(DATA_KEY)) {
            return;
        }
        CompoundTag data = serverData.getCompound(DATA_KEY);
        tooltip.add(PowerJadeText.metric("jade.miningdim.power.air_separator.mode",
                PowerJadeText.colored(
                        Component.translatable("screen.miningdim.air_separation_unit.mode." + data.getString(MODE)),
                        ChatFormatting.AQUA)));
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
    }
}
