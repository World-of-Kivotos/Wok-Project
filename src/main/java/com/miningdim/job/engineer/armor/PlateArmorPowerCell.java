package com.miningdim.job.engineer.armor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 插板护甲的随身电池。电量直接存在 ItemStack 的 NBT 上，因此护甲被丢弃、交易、放进箱子都能带着电走。
 *
 * 走标准 {@link IEnergyStorage} capability 而不是私有 NBT 读写，是为了让任何遵循 Forge 能量契约的
 * 充电手段都能直接给它充电——包括后续接入的 Flux Networks 无线充电（护甲穿在身上，无法接线缆，
 * 无线充电是它唯一合理的补给方式）。
 */
public final class PlateArmorPowerCell implements ICapabilityProvider {

    private static final String K_ENERGY = "PlateArmorEnergy";

    private final ItemStack stack;
    private final LazyOptional<IEnergyStorage> holder = LazyOptional.of(StackEnergy::new);

    public PlateArmorPowerCell(ItemStack stack) {
        this.stack = stack;
    }

    /**
     * 当前电量。NBT 里没有记录时视为出厂满电——新造出来的护甲立刻能用，用过之后才开始需要补给。
     * 若默认为空电，玩家造出第一件护甲会发现它不防弹，而此时他多半还没有任何充电手段。
     */
    public static int storedEnergy(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(K_ENERGY, net.minecraft.nbt.Tag.TAG_INT)) {
            return capacity();
        }
        return Math.max(0, Math.min(tag.getInt(K_ENERGY), capacity()));
    }

    public static int capacity() {
        return PlateArmorConfig.energyCapacity();
    }

    /**
     * 扣电。返回实际扣掉的量，不足时扣光并返回较小值——调用方据此决定护甲是否还能提供防护。
     * 不抛异常：战斗结算路径上抛异常会把一次受击变成崩服。
     */
    public static int consume(ItemStack stack, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int stored = storedEnergy(stack);
        int taken = Math.min(stored, amount);
        if (taken > 0) {
            stack.getOrCreateTag().putInt(K_ENERGY, stored - taken);
        }
        return taken;
    }

    private static void setStored(ItemStack stack, int value) {
        stack.getOrCreateTag().putInt(K_ENERGY, Math.max(0, Math.min(value, capacity())));
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable net.minecraft.core.Direction side) {
        if (capability == ForgeCapabilities.ENERGY) {
            return holder.cast();
        }
        return LazyOptional.empty();
    }

    /** 只进不出：外部只能给护甲充电，护甲的电只在受击时由减伤逻辑内部扣除。 */
    private final class StackEnergy implements IEnergyStorage {

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (maxReceive <= 0) {
                return 0;
            }
            int stored = storedEnergy(stack);
            int accepted = Math.min(maxReceive, capacity() - stored);
            if (accepted <= 0) {
                return 0;
            }
            if (!simulate) {
                setStored(stack, stored + accepted);
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return storedEnergy(stack);
        }

        @Override
        public int getMaxEnergyStored() {
            return capacity();
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }
}
