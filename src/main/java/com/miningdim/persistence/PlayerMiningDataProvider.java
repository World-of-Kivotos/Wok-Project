package com.miningdim.persistence;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlayerMiningData 的 Capability Provider (1.20.1 Forge, 设计文档 12.5)。
 * 实现 ICapabilitySerializable&lt;CompoundTag&gt;, 内部持一份数据实例与其 LazyOptional 句柄。
 *
 * 生命周期纪律: getCapability 仅对本 mod 的 PlayerMiningCapability.CAPABILITY 返回有效句柄, 其余转 empty。
 * invalidate() 在实体卸载/能力失效时由 Forge 触发 (经事件), 使持有方释放对旧句柄的引用; revive() 供
 * PlayerEvent.Clone 期 reviveCaps() 后临时重读原实体数据 (12.5 1.20.1 强制写法)。
 */
public final class PlayerMiningDataProvider implements ICapabilitySerializable<CompoundTag> {

    private final PlayerMiningData data = new PlayerMiningData();
    private LazyOptional<PlayerMiningData> optional = LazyOptional.of(() -> data);

    /** 直接取数据实例 (Clone 拷贝时读原实体用; 不经 LazyOptional 以避开 invalidate 状态)。 */
    public PlayerMiningData getData() {
        return data;
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == PlayerMiningCapability.CAPABILITY) {
            return optional.cast();
        }
        return LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        return data.save();
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        data.load(tag);
    }

    /** Forge 在能力失效时调用, 使旧 LazyOptional 持有者收到失效回调并释放引用。 */
    public void invalidate() {
        optional.invalidate();
    }

    /**
     * 重新生成 LazyOptional, 使能力在 invalidate 后可再次有效 (Clone 期 reviveCaps 配套)。
     * 仅在已失效时重建, 避免覆盖仍有效的句柄而丢失监听者。
     */
    public void revive() {
        if (!optional.isPresent()) {
            optional = LazyOptional.of(() -> data);
        }
    }
}
