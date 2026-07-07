package com.miningdim.champion;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 冠军 Capability 提供者 (自研冠军数据挂载器; 范式对齐 {@code entry.MiningPlayerDataProvider})。每个 Mob 实体挂
 * 一个, 内部持单实例 {@link MiningChampionData} 与其 LazyOptional 句柄。
 *
 * 1.20.1 Forge 强制写法: 实体卸载/跨维度 invalidateCaps 触发 {@link #invalidate()} 让旧句柄失效并【重建】指向
 * 同一 data 的新句柄 (实体本身持续存在时句柄须可重生, 否则重载后 getCapability 永远失效; 与玩家 provider 同坑)。
 */
public final class MiningChampionProvider implements ICapabilitySerializable<CompoundTag> {

    private final MiningChampionData data = new MiningChampionData();
    /** 指向 data 的能力句柄; invalidate 后重建 (跨维度/卸载不销毁 data, 句柄须可重生)。 */
    private LazyOptional<MiningChampionData> handle = LazyOptional.of(() -> data);

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return MiningChampions.CHAMPION_DATA.orEmpty(cap, handle);
    }

    @Override
    public CompoundTag serializeNBT() {
        return data.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        data.deserializeNBT(nbt);
    }

    /** 直接取底层数据 (诊断/测试用)。 */
    public MiningChampionData rawData() {
        return data;
    }

    /** Forge invalidateCaps 回调: 旧句柄失效后重建指向同一 data 的新句柄 (与玩家 provider 同范式)。 */
    public void invalidate() {
        handle.invalidate();
        handle = LazyOptional.of(() -> data);
    }
}
