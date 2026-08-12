package com.miningdim.job.farmer.item;

import com.miningdim.job.farmer.FarmerCropTable;
import com.miningdim.job.farmer.FarmerTier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Farmland item with a compact output preview sourced from the crop table. */
public final class FarmerFarmlandItem extends BlockItem {
    private final FarmerTier tier;

    public FarmerFarmlandItem(Block block, Properties properties, FarmerTier tier) {
        super(block, properties);
        this.tier = tier;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        FarmerCropTable.Row row = FarmerCropTable.row(tier);
        tooltip.add(Component.translatable("tooltip.miningdim.farmer.unlock", row.unlockLevel())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.farmer.growth", row.growthMinutes())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.farmer.wheat_output",
                        row.yieldMultiplier(), FarmerCropTable.amount(row.farmerWheatPerHour()))
                .withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("tooltip.miningdim.farmer.compat_output", row.yieldMultiplier())
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.miningdim.farmer.byproducts")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
