package com.miningdim.power.machine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.function.IntSupplier;

/** 机器内部 FE 缓冲，仅允许网络或外部电源注入，库存状态由持有 BE 落盘。 */
public final class MachineEnergyStorage implements IEnergyStorage {

    private static final String K_ENERGY = "energy";

    private final IntSupplier capacity;
    private final Runnable changed;
    private int energy;

    public MachineEnergyStorage(IntSupplier capacity, Runnable changed) {
        this.capacity = capacity;
        this.changed = changed;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        if (maxReceive <= 0) {
            return 0;
        }
        int configuredCapacity = capacity.getAsInt();
        if (energy >= configuredCapacity) {
            return 0;
        }
        int available = configuredCapacity - energy;
        int accepted = Math.min(maxReceive, available);
        if (!simulate && accepted > 0) {
            energy += accepted;
            changed.run();
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        return 0;
    }

    @Override
    public int getEnergyStored() {
        return energy;
    }

    @Override
    public int getMaxEnergyStored() {
        return Math.max(capacity.getAsInt(), energy);
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    public boolean hasAtLeast(int amount) {
        return energy >= amount;
    }

    public void consume(int amount) {
        if (amount < 0 || amount > energy) {
            throw new IllegalArgumentException("invalid machine FE consumption: " + amount);
        }
        if (amount > 0) {
            energy -= amount;
            changed.run();
        }
    }

    public void save(CompoundTag tag) {
        tag.putInt(K_ENERGY, energy);
    }

    public void load(CompoundTag tag) {
        if (!tag.contains(K_ENERGY, Tag.TAG_INT)) {
            throw new IllegalStateException("machine FE NBT is missing energy");
        }
        int restored = tag.getInt(K_ENERGY);
        if (restored < 0) {
            throw new IllegalStateException("machine FE NBT is negative");
        }
        energy = restored;
    }
}
