package com.miningdim.job.chef.pot;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

/** Keeps FD's normal callback while journalling mutations and locking the active round. */
public final class PotInventoryHandler extends ItemStackHandler {
    private final CookingPotBlockEntity pot;

    public PotInventoryHandler(CookingPotBlockEntity pot) { super(9); this.pot = pot; }

    @Override public ItemStack getStackInSlot(int slot) {
        PotCraftService.ensureHydrated(pot);
        ItemStack stack = super.getStackInSlot(slot);
        return PotCraftService.locked(pot) && !PotCraftService.internal(pot) ? stack.copy() : stack;
    }
    @Override public void setStackInSlot(int slot, ItemStack stack) {
        PotCraftService.ensureHydrated(pot);
        if (PotCraftService.locked(pot) && !PotCraftService.internal(pot)) return;
        super.setStackInSlot(slot, stack);
    }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        PotCraftService.ensureHydrated(pot);
        if (PotCraftService.locked(pot) && !PotCraftService.internal(pot)) return stack;
        return super.insertItem(slot, stack, simulate);
    }
    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
        PotCraftService.ensureHydrated(pot);
        if (PotCraftService.locked(pot) && !PotCraftService.internal(pot)) return ItemStack.EMPTY;
        return super.extractItem(slot, amount, simulate);
    }
    @Override protected void onContentsChanged(int slot) {
        if (slot < 6) ((PotBridge) pot).wok$setRecipeDirty();
        ((PotBridge) pot).wok$notifyInventoryChanged();
    }
}
