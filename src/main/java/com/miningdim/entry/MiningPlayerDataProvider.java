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
    private final LazyOptional<IMiningPlayerData> handle = LazyOptional.of(() -> data);

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

    /** 实体卸载时让句柄失效 (AttachCapabilitiesEvent 注册的 invalidate 回调调用)。 */
    public void invalidate() {
        handle.invalidate();
    }
}
