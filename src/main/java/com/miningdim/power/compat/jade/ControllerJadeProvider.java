package com.miningdim.power.compat.jade;

import com.miningdim.power.endgame.LowTemperatureControllerBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/** 低温控制器同步持久的冷却时钟和当前对 NbTi 的覆盖份额。 */
public final class ControllerJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    private static final ResourceLocation UID = new ResourceLocation("miningdim", "power_low_temperature_controller");
    private static final String DATA_KEY = "miningdim_power_low_temperature_controller";
    private static final String ACTIVE = "active";
    private static final String REMAINING = "remaining";
    private static final String COVERAGE = "coverage";

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag serverData, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof LowTemperatureControllerBlockEntity controller)) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putBoolean(ACTIVE, controller.isCoolingActive());
        data.putInt(REMAINING, controller.remainingTicks());
        data.putInt(COVERAGE, controller.activeCoverageSegments());
        serverData.put(DATA_KEY, data);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag serverData = accessor.getServerData();
        if (!serverData.contains(DATA_KEY)) {
            return;
        }
        CompoundTag data = serverData.getCompound(DATA_KEY);
        Component state = data.getBoolean(ACTIVE)
                ? Component.translatable("screen.miningdim.low_temperature_controller.active")
                : Component.translatable("screen.miningdim.low_temperature_controller.inactive");
        tooltip.add(Component.translatable("jade.miningdim.power.controller.state", state));
        tooltip.add(Component.translatable("jade.miningdim.power.controller.remaining", data.getInt(REMAINING)));
        tooltip.add(Component.translatable("jade.miningdim.power.controller.coverage", data.getInt(COVERAGE)));
    }
}
