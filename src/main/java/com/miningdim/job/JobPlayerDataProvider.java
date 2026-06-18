package com.miningdim.job;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 玩家职业数据 capability 提供者 (实现手册 "新 Capability" 范式; 对齐 entry.MiningPlayerDataProvider)。
 * 每个玩家实体挂一个, 内部持单实例 {@link JobPlayerData} 与其 LazyOptional 句柄。
 *
 * 1.20.1 Forge 强制写法: Clone 时原实体 caps 已 invalidate, 复制前须 reviveCaps (在 {@link JobCapability});
 * 实体卸载时 invalidateCaps 触发 {@link #invalidate()} 让句柄失效, 防止悬挂引用。
 */
public final class JobPlayerDataProvider implements ICapabilitySerializable<CompoundTag> {

    private final JobPlayerData data = new JobPlayerData();
    private final LazyOptional<IJobPlayerData> handle = LazyOptional.of(() -> data);

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return JobCapability.JOB_DATA.orEmpty(cap, handle);
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
    public JobPlayerData rawData() {
        return data;
    }

    /** 实体卸载时让句柄失效 (AttachCapabilitiesEvent 注册的 invalidate 回调调用)。 */
    public void invalidate() {
        handle.invalidate();
    }
}
