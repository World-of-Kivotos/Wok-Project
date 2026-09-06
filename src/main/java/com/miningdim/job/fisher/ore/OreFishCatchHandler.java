package com.miningdim.job.fisher.ore;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Shared weighted replacement used only after a fishing implementation has produced a successful catch list. */
public final class OreFishCatchHandler {
    private OreFishCatchHandler() {
    }

    public static boolean replaceDrops(List<ItemStack> drops, int roll) {
        OreFishType type = typeForRoll(roll);
        if (type == null) {
            return false;
        }
        drops.clear();
        drops.add(new ItemStack(OreFishingItems.FISH.get(type).get()));
        return true;
    }

    public static OreFishType typeForRoll(int roll) {
        if (roll < 0 || roll >= 10_000) {
            throw new IllegalArgumentException("roll must be in [0, 10000), got " + roll);
        }
        int cumulative = 0;
        for (OreFishType type : OreFishType.values()) {
            cumulative += OreFishingConfig.catchWeight(type);
            if (roll < cumulative) {
                return type;
            }
        }
        return null;
    }
}
