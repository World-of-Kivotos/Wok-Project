package com.miningdim.job.chef.pot;

import net.minecraftforge.items.ItemStackHandler;

/** Access supplied by the FD pot mixin; no reflective access to FD internals. */
public interface PotBridge {
    ItemStackHandler wok$rawInventory();
    void wok$setRecipeDirty();
    void wok$setMealContainer(net.minecraft.world.item.ItemStack container);
    net.minecraft.world.item.ItemStack wok$mealContainer();
    void wok$setCookTime(int time);
    void wok$notifyInventoryChanged();
}
