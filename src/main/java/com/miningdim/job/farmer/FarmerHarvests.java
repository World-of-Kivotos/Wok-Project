package com.miningdim.job.farmer;

import com.miningdim.job.farmer.block.FarmerFarmlandBlock;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Set;

/** Shared crop recognition for harvest XP, loot multiplication, and Farmer's Delight picking. */
public final class FarmerHarvests {

    private static final ResourceLocation FD_TOMATOES = id("farmersdelight", "tomatoes");
    private static final ResourceLocation FD_TOMATOES_ON_ROPE = id("farmersdelight", "tomatoes_on_rope");

    private static final Map<ResourceLocation, Set<ResourceLocation>> PRODUCE_BY_CROP = Map.ofEntries(
            Map.entry(id("minecraft", "wheat"), Set.of(id("minecraft", "wheat"))),
            Map.entry(id("minecraft", "carrots"), Set.of(id("minecraft", "carrot"))),
            Map.entry(id("minecraft", "potatoes"), Set.of(id("minecraft", "potato"))),
            Map.entry(id("minecraft", "beetroots"), Set.of(id("minecraft", "beetroot"))),
            Map.entry(id("farmersdelight", "cabbages"), Set.of(id("farmersdelight", "cabbage"))),
            Map.entry(id("farmersdelight", "onions"), Set.of(id("farmersdelight", "onion"))),
            Map.entry(FD_TOMATOES, Set.of(id("farmersdelight", "tomato"))),
            Map.entry(FD_TOMATOES_ON_ROPE, Set.of(id("farmersdelight", "tomato"))),
            Map.entry(id("farmersdelight", "rice_panicles"), Set.of(
                    id("farmersdelight", "rice"), id("farmersdelight", "rice_panicle")))
    );

    private static final Set<ResourceLocation> VERTICAL_FD_CROP_COLUMN = Set.of(
            id("farmersdelight", "rice"),
            id("farmersdelight", "rice_panicles"),
            id("farmersdelight", "budding_tomatoes"),
            FD_TOMATOES,
            FD_TOMATOES_ON_ROPE
    );

    private FarmerHarvests() {
    }

    public static boolean isSupportedMatureCrop(BlockState state) {
        ResourceLocation cropId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return PRODUCE_BY_CROP.containsKey(cropId)
                && state.getBlock() instanceof CropBlock crop
                && crop.isMaxAge(state);
    }

    public static boolean isSupportedCrop(BlockState state) {
        ResourceLocation cropId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return PRODUCE_BY_CROP.containsKey(cropId) || VERTICAL_FD_CROP_COLUMN.contains(cropId);
    }

    public static boolean isPickableFarmersDelightTomato(BlockState state) {
        ResourceLocation cropId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return (FD_TOMATOES.equals(cropId) || FD_TOMATOES_ON_ROPE.equals(cropId))
                && state.getBlock() instanceof CropBlock crop
                && crop.isMaxAge(state);
    }

    public static FarmerTier tierFor(BlockGetter level, BlockPos cropPos, BlockState cropState) {
        ResourceLocation cropId = ForgeRegistries.BLOCKS.getKey(cropState.getBlock());
        int maxDepth = VERTICAL_FD_CROP_COLUMN.contains(cropId) ? 4 : 1;
        for (int depth = 1; depth <= maxDepth; depth++) {
            BlockState below = level.getBlockState(cropPos.below(depth));
            if (below.getBlock() instanceof FarmerFarmlandBlock farmland) {
                return farmland.tier();
            }
            ResourceLocation belowId = ForgeRegistries.BLOCKS.getKey(below.getBlock());
            if (!VERTICAL_FD_CROP_COLUMN.contains(belowId)) {
                return null;
            }
        }
        return null;
    }

    public static void multiplyProduce(ObjectArrayList<ItemStack> loot, BlockState cropState, FarmerTier tier) {
        Set<ResourceLocation> produceIds = PRODUCE_BY_CROP.get(
                ForgeRegistries.BLOCKS.getKey(cropState.getBlock()));
        if (produceIds == null) {
            return;
        }
        for (ItemStack stack : loot) {
            if (produceIds.contains(ForgeRegistries.ITEMS.getKey(stack.getItem()))) {
                stack.setCount(stack.getCount() * tier.yieldPerHarvest());
            }
        }
    }

    private static ResourceLocation id(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }
}
