package com.miningdim.power.cable;

import com.miningdim.power.PowerRegistry;
import com.miningdim.power.grid.EnergyNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/**
 * 线缆方块实体。刻意无 ticker: 全部搬电工作由 {@link EnergyNetworkManager} 每 settlement 统一做 (抗掉刻),
 * 本 BE 只负责三件事: onLoad 并网 / setRemoved 拆网 / 对外暴露一个 receive-only 的 FE 能力挂在本网瞬态缓冲上。
 *
 * receive-only (canReceive=true, canExtract=false): 推式发电机可把电推进线缆 -> 汇入本网缓冲;
 * 消费端一律由 manager 主动 push, 不经此 cap 反向抽, 从而杜绝 "端点自拉 + manager push" 双计。
 */
public final class EnergyCableBlockEntity extends BlockEntity {

    private final IEnergyStorage energyView = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            EnergyNetworkManager manager = manager();
            if (manager == null) {
                return 0;
            }
            int capped = Math.min(maxReceive, material().transientBufferCap());
            return manager.receiveIntoNetwork(worldPosition, capped, simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            EnergyNetworkManager manager = manager();
            return manager == null ? 0 : manager.storedAt(worldPosition);
        }

        @Override
        public int getMaxEnergyStored() {
            EnergyNetworkManager manager = manager();
            return manager == null ? 0 : manager.capacityAt(worldPosition);
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    };

    private final LazyOptional<IEnergyStorage> energyCap = LazyOptional.of(() -> energyView);

    public EnergyCableBlockEntity(BlockPos pos, BlockState state) {
        super(PowerRegistry.ENERGY_CABLE_BE.get(), pos, state);
    }

    private ConductorMaterial material() {
        return getBlockState().getBlock() instanceof EnergyCableBlock cable ? cable.material() : ConductorMaterial.IRON;
    }

    @Nullable
    private EnergyNetworkManager manager() {
        return level instanceof ServerLevel serverLevel ? EnergyNetworkManager.get(serverLevel) : null;
    }

    /** 本网网温 (°C), 供 Jade 服务端数据提供者读 (温度是服务端权威, 不可客户端直读)。 */
    public double networkTemperatureC() {
        EnergyNetworkManager manager = manager();
        return manager == null ? com.miningdim.power.grid.CableThermics.AMBIENT_C
                : manager.networkTemperatureAt(worldPosition);
    }

    /** 本网上一 settlement 负载率 (送达/额定), 供 Jade 显示。 */
    public double networkLoadRatio() {
        EnergyNetworkManager manager = manager();
        return manager == null ? 0.0 : manager.networkLoadRatioAt(worldPosition);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        EnergyNetworkManager manager = manager();
        if (manager != null) {
            manager.addCable(worldPosition, material());
        }
    }

    @Override
    public void setRemoved() {
        EnergyNetworkManager manager = manager();
        if (manager != null) {
            manager.removeCable(worldPosition);
        }
        energyCap.invalidate();
        super.setRemoved();
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ENERGY) {
            return energyCap.cast();
        }
        return super.getCapability(cap, side);
    }
}
