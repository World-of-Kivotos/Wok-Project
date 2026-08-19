package com.miningdim.power.storage;

import com.miningdim.power.PowerRegistry;
import com.miningdim.power.grid.VoltageAwareEnergyStorage;
import com.miningdim.power.grid.VoltageClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 三级储电的控制器。储电是把开环发电系统闭合起来的枢纽：发电是连续的、消费是脉冲的
 * （造弹按小时结算、护甲按战斗触发），两者时间尺度不匹配，中间必须有缓冲。
 *
 * 整数溢出铁律：内部余额一律用 long。单个储电的容量被配置上限压在 int 安全区内，但
 * "把网络里所有储电加起来" 这类统计极易越界（三个三级储电就是 26.5 亿，已超 int 上限），
 * 因此凡是跨端点求和的调用方必须拿 {@link #storedFeLong()} 而不是 capability 的 int 值。
 */
public final class PowerCellBlockEntity extends BlockEntity implements MenuProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/power");
    private static final String K_STORED = "storedFe";

    private final PowerCellSpec spec;
    private final CellStorage energy = new CellStorage();
    private final LazyOptional<VoltageAwareEnergyStorage> energyCap = LazyOptional.of(() -> energy);

    private long storedFe;
    private int receivedThisTick;
    private int extractedThisTick;
    private int lastReceivedFe;
    private int lastExtractedFe;

    public PowerCellBlockEntity(BlockPos pos, BlockState state) {
        super(PowerRegistry.POWER_CELL_BE.get(), pos, state);
        this.spec = PowerCellBlock.specOf(state.getBlock());
    }

    public PowerCellSpec spec() {
        return spec;
    }

    public PowerCellSpec.Runtime runtime() {
        return spec.runtime();
    }

    /** 跨端点求和必须用这个而不是 capability 的 int 读数，否则多储电统计会溢出。 */
    public long storedFeLong() {
        return storedFe;
    }

    public int storedFe() {
        return saturate(storedFe);
    }

    public int capacityFe() {
        return runtime().capacityFe();
    }

    public int lastReceivedFe() {
        return lastReceivedFe;
    }

    public int lastExtractedFe() {
        return lastExtractedFe;
    }

    /** 每 tick 把进出量结算成上一 tick 的快照，界面据此显示实时功率而不是累计值。 */
    public void serverTick() {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        if (lastReceivedFe != receivedThisTick || lastExtractedFe != extractedThisTick) {
            lastReceivedFe = receivedThisTick;
            lastExtractedFe = extractedThisTick;
            boolean active = lastReceivedFe > 0 || lastExtractedFe > 0;
            if (getBlockState().getValue(PowerCellBlock.LIT) != active) {
                level.setBlock(worldPosition, getBlockState().setValue(PowerCellBlock.LIT, active), 3);
            }
        }
        receivedThisTick = 0;
        extractedThisTick = 0;
    }

    /** 玩家手动给随身装备充电用的抽取口, 与电网抽取共用同一账本与限速。 */
    public int extractForCharging(int maxExtract) {
        return extractInternal(maxExtract, false);
    }

    private int receiveInternal(int maxReceive, boolean simulate) {
        if (maxReceive <= 0) {
            return 0;
        }
        PowerCellSpec.Runtime runtime = runtime();
        long room = (long) runtime.capacityFe() - storedFe;
        if (room <= 0L) {
            return 0;
        }
        // 传输速率钳制单次调用: 电网每 tick 调一次, 这就等价于每 tick 的进线功率上限。
        int accepted = (int) Math.min(Math.min(maxReceive, runtime.transferFePerTick()), room);
        if (!simulate && accepted > 0) {
            storedFe += accepted;
            receivedThisTick += accepted;
            setChanged();
        }
        return accepted;
    }

    private int extractInternal(int maxExtract, boolean simulate) {
        if (maxExtract <= 0 || storedFe <= 0L) {
            return 0;
        }
        PowerCellSpec.Runtime runtime = runtime();
        int extracted = (int) Math.min(Math.min(maxExtract, runtime.transferFePerTick()), storedFe);
        if (!simulate && extracted > 0) {
            storedFe -= extracted;
            extractedThisTick += extracted;
            setChanged();
        }
        return extracted;
    }

    /** 容量被配置上限压在 int 安全区内，这里的饱和只是防御性写法，正常路径不会触发。 */
    private static int saturate(long value) {
        return (int) Math.max(0L, Math.min(value, Integer.MAX_VALUE));
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ENERGY) {
            return energyCap.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        energyCap.invalidate();
    }

    /** 保存路径不抛异常，理由同前期发电机：任何观察者都可能在任意时机触发一次保存。 */
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong(K_STORED, storedFe);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        long raw = tag.contains(K_STORED, Tag.TAG_LONG) ? tag.getLong(K_STORED) : 0L;
        long clamped = Math.max(0L, Math.min(raw, runtime().capacityFe()));
        if (clamped != raw) {
            LOGGER.warn("power cell NBT out of range at {}; clamped stored {} -> {}",
                    worldPosition, raw, clamped);
        }
        storedFe = clamped;
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
        return new PowerCellMenu(windowId, playerInventory, worldPosition);
    }

    /** 储电是双向端点：既收也发，这正是它与发电机（只出）和机器（只进）的区别。 */
    private final class CellStorage implements VoltageAwareEnergyStorage {

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return receiveInternal(maxReceive, simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return extractInternal(maxExtract, simulate);
        }

        @Override
        public int getEnergyStored() {
            return saturate(storedFe);
        }

        @Override
        public int getMaxEnergyStored() {
            return runtime().capacityFe();
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return true;
        }

        @Override
        public VoltageClass outputVoltage() {
            return spec.voltageClass();
        }

        @Override
        public void reportOvervoltage(VoltageClass networkLimit) {
            LOGGER.warn("power cell at {} received an overvoltage report against network limit {}",
                    worldPosition, networkLimit);
        }
    }
}
