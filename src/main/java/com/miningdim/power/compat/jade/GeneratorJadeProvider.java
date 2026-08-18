package com.miningdim.power.compat.jade;

import com.miningdim.power.generator.GeneratorBlockEntity;
import net.minecraft.ChatFormatting;
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
    private static final ResourceLocation BUFFER_BAR = new ResourceLocation("miningdim", "power_generator_buffer");
    private static final ResourceLocation FUEL_BAR = new ResourceLocation("miningdim", "power_generator_fuel");
    private static final ResourceLocation TEMPERATURE_BAR =
            new ResourceLocation("miningdim", "power_generator_temperature");
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
        String state = data.getString(STATE);
        String fuse = data.getString(FUSE);
        PowerJadeText.addPair(tooltip,
                PowerJadeText.metric("jade.miningdim.power.generator.state",
                        PowerJadeText.statusValue("jade.miningdim.power.generator.state_value", state)),
                PowerJadeText.metric("jade.miningdim.power.generator.fuse",
                        PowerJadeText.statusValue("jade.miningdim.power.generator.fuse_value", fuse)));
        PowerJadeText.addProgress(tooltip,
                PowerJadeText.metric("jade.miningdim.power.generator.buffer",
                        data.getInt(BUFFER), data.getInt(BUFFER_CAPACITY)),
                data.getInt(BUFFER), data.getInt(BUFFER_CAPACITY),
                PowerJadeText.ENERGY_BRIGHT, PowerJadeText.ENERGY_DARK, BUFFER_BAR);
        if (data.getBoolean(FUEL_PRESENT)) {
            int maxDamage = data.getInt(FUEL_MAX_DAMAGE);
            int remaining = maxDamage - data.getInt(FUEL_DAMAGE);
            PowerJadeText.addProgress(tooltip,
                    PowerJadeText.metric("jade.miningdim.power.generator.fuel", remaining, maxDamage),
                    remaining, maxDamage, PowerJadeText.FUEL_BRIGHT, PowerJadeText.FUEL_DARK, FUEL_BAR);
        } else {
            tooltip.add(PowerJadeText.colored(
                    Component.translatable("jade.miningdim.power.generator.fuel_empty"), ChatFormatting.GOLD));
        }
        double temperature = data.getDouble(TEMPERATURE);
        double meltdownTemperature = data.getDouble(MELTDOWN_TEMPERATURE);
        PowerJadeText.addTemperatureProgress(tooltip,
                PowerJadeText.metric("jade.miningdim.power.generator.temperature",
                        PowerJadeText.oneDecimal(temperature), PowerJadeText.oneDecimal(meltdownTemperature)),
                temperature, meltdownTemperature, TEMPERATURE_BAR);
        String networkFault = data.getString(NETWORK_FAULT);
        tooltip.add(PowerJadeText.metric("jade.miningdim.power.generator.network_fault",
                PowerJadeText.statusValue("jade.miningdim.power.generator.network_fault_value", networkFault),
                PowerJadeText.colored(
                        PowerJadeText.enumValue("jade.miningdim.power.voltage", data.getString(NETWORK_LIMIT)),
                        ChatFormatting.AQUA)));
    }
}
