package com.miningdim.power.compat.jade;

import com.miningdim.power.generator.GeneratorBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/** 发电机数据只从锚点控制器同步，非锚点外壳没有对应前缀数据。 */
public final class GeneratorJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    private static final ResourceLocation UID = new ResourceLocation("miningdim", "power_generator");
    private static final String DATA_KEY = "miningdim_power_generator";
    private static final String STATE = "state";
    private static final String BUFFER = "buffer";
    private static final String BUFFER_CAPACITY = "bufferCapacity";
    private static final String FUEL_DAMAGE = "fuelDamage";
    private static final String FUEL_MAX_DAMAGE = "fuelMaxDamage";
    private static final String FUEL_PRESENT = "fuelPresent";
    private static final String FUSE = "fuse";
    private static final String TEMPERATURE = "temperature";
    private static final String MELTDOWN_TEMPERATURE = "meltdownTemperature";
    private static final String NETWORK_FAULT = "networkFault";
    private static final String NETWORK_LIMIT = "networkLimit";

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag serverData, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof GeneratorBlockEntity generator)) {
            return;
        }
        ItemStack fuel = generator.fuelCore();
        CompoundTag data = new CompoundTag();
        data.putString(STATE, generator.state().name());
        data.putInt(BUFFER, generator.storedFe());
        data.putInt(BUFFER_CAPACITY, generator.bufferCapacityFe());
        data.putBoolean(FUEL_PRESENT, !fuel.isEmpty());
        if (!fuel.isEmpty()) {
            data.putInt(FUEL_DAMAGE, fuel.getDamageValue());
            data.putInt(FUEL_MAX_DAMAGE, fuel.getMaxDamage());
        }
        data.putString(FUSE, generator.fuseState().name());
        data.putDouble(TEMPERATURE, generator.temperatureC());
        data.putDouble(MELTDOWN_TEMPERATURE, generator.meltdownTemperatureC());
        data.putString(NETWORK_FAULT, generator.networkFault().name());
        data.putString(NETWORK_LIMIT, generator.faultNetworkLimit().name());
        serverData.put(DATA_KEY, data);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag serverData = accessor.getServerData();
        if (!serverData.contains(DATA_KEY)) {
            return;
        }
        CompoundTag data = serverData.getCompound(DATA_KEY);
        tooltip.add(Component.translatable("jade.miningdim.power.generator.state",
                PowerJadeText.enumValue("jade.miningdim.power.generator.state_value", data.getString(STATE))));
        tooltip.add(Component.translatable("jade.miningdim.power.generator.buffer",
                data.getInt(BUFFER), data.getInt(BUFFER_CAPACITY)));
        if (data.getBoolean(FUEL_PRESENT)) {
            int maxDamage = data.getInt(FUEL_MAX_DAMAGE);
            tooltip.add(Component.translatable("jade.miningdim.power.generator.fuel",
                    maxDamage - data.getInt(FUEL_DAMAGE), maxDamage));
        } else {
            tooltip.add(Component.translatable("jade.miningdim.power.generator.fuel_empty"));
        }
        tooltip.add(Component.translatable("jade.miningdim.power.generator.fuse",
                PowerJadeText.enumValue("jade.miningdim.power.generator.fuse_value", data.getString(FUSE))));
        tooltip.add(Component.translatable("jade.miningdim.power.generator.temperature",
                PowerJadeText.oneDecimal(data.getDouble(TEMPERATURE)),
                PowerJadeText.oneDecimal(data.getDouble(MELTDOWN_TEMPERATURE))));
        tooltip.add(Component.translatable("jade.miningdim.power.generator.network_fault",
                PowerJadeText.enumValue("jade.miningdim.power.generator.network_fault_value",
                        data.getString(NETWORK_FAULT)),
                PowerJadeText.enumValue("jade.miningdim.power.voltage", data.getString(NETWORK_LIMIT))));
    }
}
