package com.miningdim.power.compat.jade;

import com.miningdim.power.cable.EnergyCableBlockEntity;
import com.miningdim.power.grid.EnergyNetworkSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

import java.util.stream.Collectors;

/** 线缆只发布网络不可变快照，客户端绝不读取本地网络实例。 */
public final class CableJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    private static final ResourceLocation UID = new ResourceLocation("miningdim", "power_cable");
    private static final ResourceLocation BUFFER_BAR = new ResourceLocation("miningdim", "power_cable_buffer");
    private static final ResourceLocation LOAD_BAR = new ResourceLocation("miningdim", "power_cable_load");
    private static final String DATA_KEY = "miningdim_power_cable";
    private static final String TEMPERATURE = "temperature";
    private static final String RATED_CAPACITY = "ratedCapacity";
    private static final String EFFECTIVE_CAPACITY = "effectiveCapacity";
    private static final String BUFFER_CAPACITY = "bufferCapacity";
    private static final String STORED = "stored";
    private static final String LAST_LOAD = "lastLoad";
    private static final String LOAD_RATIO = "loadRatio";
    private static final String LAST_BUFFER_OVERFLOW_LOSS = "lastBufferOverflowLoss";
    private static final String TOTAL_BUFFER_OVERFLOW_LOSS = "totalBufferOverflowLoss";
    private static final String LAST_DISTANCE_LOSS = "lastDistanceLoss";
    private static final String TOTAL_DISTANCE_LOSS = "totalDistanceLoss";
    private static final String VOLTAGE = "voltage";
    private static final String FAULTS = "faults";
    private static final String COOLING = "cooling";

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag serverData, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof EnergyCableBlockEntity cable)) {
            return;
        }
        cable.networkSnapshot().ifPresent(snapshot -> serverData.put(DATA_KEY, snapshotData(snapshot)));
    }

    private static CompoundTag snapshotData(EnergyNetworkSnapshot snapshot) {
        CompoundTag data = new CompoundTag();
        data.putDouble(TEMPERATURE, snapshot.temperatureC());
        data.putInt(RATED_CAPACITY, snapshot.ratedCapacityFe());
        data.putInt(EFFECTIVE_CAPACITY, snapshot.effectiveCapacityFe());
        data.putInt(BUFFER_CAPACITY, snapshot.bufferCapacityFe());
        data.putInt(STORED, snapshot.storedFe());
        data.putInt(LAST_LOAD, snapshot.lastLoadFe());
        data.putDouble(LOAD_RATIO, snapshot.loadRatio());
        data.putInt(LAST_BUFFER_OVERFLOW_LOSS, snapshot.lastBufferOverflowLossFe());
        data.putLong(TOTAL_BUFFER_OVERFLOW_LOSS, snapshot.totalBufferOverflowLossFe());
        data.putInt(LAST_DISTANCE_LOSS, snapshot.lastDistanceLossFe());
        data.putLong(TOTAL_DISTANCE_LOSS, snapshot.totalDistanceLossFe());
        data.putString(VOLTAGE, snapshot.voltageLimit().name());
        data.putString(FAULTS, snapshot.faults().stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(",")));
        data.putString(COOLING, snapshot.coolingState().name());
        return data;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag serverData = accessor.getServerData();
        if (!serverData.contains(DATA_KEY)) {
            return;
        }
        CompoundTag data = serverData.getCompound(DATA_KEY);
        String voltage = data.getString(VOLTAGE);
        String cooling = data.getString(COOLING);
        PowerJadeText.addPair(tooltip,
                PowerJadeText.metric("jade.miningdim.power.cable.voltage",
                        PowerJadeText.colored(PowerJadeText.enumValue("jade.miningdim.power.voltage", voltage),
                                ChatFormatting.AQUA)),
                PowerJadeText.metric("jade.miningdim.power.cable.cooling",
                        PowerJadeText.statusValue("jade.miningdim.power.cooling", cooling)));
        PowerJadeText.addPair(tooltip,
                PowerJadeText.metric("jade.miningdim.power.cable.temperature",
                        PowerJadeText.oneDecimal(data.getDouble(TEMPERATURE))),
                PowerJadeText.metric("jade.miningdim.power.cable.capacity",
                        data.getInt(EFFECTIVE_CAPACITY), data.getInt(RATED_CAPACITY)));
        PowerJadeText.addProgress(tooltip,
                PowerJadeText.metric("jade.miningdim.power.cable.buffer",
                        data.getInt(STORED), data.getInt(BUFFER_CAPACITY)),
                data.getInt(STORED), data.getInt(BUFFER_CAPACITY),
                PowerJadeText.ENERGY_BRIGHT, PowerJadeText.ENERGY_DARK, BUFFER_BAR);
        PowerJadeText.addProgress(tooltip,
                PowerJadeText.metric("jade.miningdim.power.cable.load",
                        data.getInt(LAST_LOAD), PowerJadeText.oneDecimal(data.getDouble(LOAD_RATIO) * 100.0D)),
                data.getDouble(LOAD_RATIO), 1.0D,
                PowerJadeText.PROCESS_BRIGHT, PowerJadeText.PROCESS_DARK, LOAD_BAR);
        tooltip.add(PowerJadeText.metric("jade.miningdim.power.cable.faults",
                PowerJadeText.enumList("jade.miningdim.power.network_fault", data.getString(FAULTS))));
        tooltip.add(PowerJadeText.metric("jade.miningdim.power.cable.buffer_overflow_loss",
                data.getInt(LAST_BUFFER_OVERFLOW_LOSS), data.getLong(TOTAL_BUFFER_OVERFLOW_LOSS)));
        tooltip.add(PowerJadeText.metric("jade.miningdim.power.cable.distance_loss",
                data.getInt(LAST_DISTANCE_LOSS), data.getLong(TOTAL_DISTANCE_LOSS)));
    }
}
