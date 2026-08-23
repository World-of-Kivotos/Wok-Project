package com.miningdim.job.chef;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** Dynamic datapack veto for foods that must never enter the seasoning transaction. */
final class SeasoningEligibility {
    private static final TagKey<Item> UNSEASONABLE = TagKey.create(ForgeRegistries.ITEMS.getRegistryKey(),
            new ResourceLocation(MiningConstants.MODID, "unseasonable"));

    private SeasoningEligibility() {
    }

    static boolean isUnseasonable(ItemStack stack) {
        return stack.is(UNSEASONABLE);
    }
}
