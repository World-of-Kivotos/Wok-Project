package com.miningdim.entry;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 玩家 Capability 提供者 (设计文档 12.5: ICapabilitySerializable + LazyOptional)。每个玩家实体挂一个,
 * 内部持有单实例 {@link MiningPlayerData} 与其 LazyOptional 句柄。
 *
 * 1.20.1 Forge 强制写法 (12.5): Clone 时原实体 caps 已 invalidate, 复制前须 reviveCaps; 实体卸载时
 * invalidateCaps 触发 {@link #invalidate()} 让 LazyOptional 失效, 防止悬挂引用。
 */
public final class MiningPlayerDataProvider implements ICapabilitySerializable<CompoundTag> {

    private final MiningPlayerData data = new MiningPlayerData();
    /** 指向 data 的能力句柄; invalidate 后【重建】(玩家跨维度 invalidateCaps 不销毁实体, 句柄须可重生)。 */
    private LazyOptional<IMiningPlayerData> handle = LazyOptional.of(() -> data);

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return MiningCapabilities.PLAYER_DATA.orEmpty(cap, handle);
    }

    @Override
    public CompoundTag serializeNBT() {
        return data.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        data.deserializeNBT(nbt);
    }

    /** 直接取底层数据 (供 Clone 复制读取原实体数据, 避免 LazyOptional 解包绕路)。 */
    public MiningPlayerData rawData() {
        return data;
    }

    /**
     * Forge invalidateCaps 回调 (AttachCapabilitiesEvent 注册): 旧句柄失效后【重建】一个指向同一 data 的新句柄。
     * 玩家跨维度时 Forge 会 invalidateCaps -> 本方法, 但玩家实体本身持续存在(非销毁); 若不重建, 换维度后
     * getCapability 永远返回失效句柄 -> {@link MiningCapabilities#get} 永空 -> /mining leave 等读 cap 全失败
     * (实测: 进矿洞后退不出)。实体真卸载时也走本方法重建, 但卸载后无人再取 cap, 新句柄随 provider 一起 GC, 无害。
     */
    public void invalidate() {
        handle.invalidate();
        handle = LazyOptional.of(() -> data);
    }
}
