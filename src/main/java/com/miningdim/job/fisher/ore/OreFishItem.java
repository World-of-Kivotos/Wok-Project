package com.miningdim.job.fisher.ore;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

public final class OreFishItem extends Item {
    private final OreFishType type;

    public OreFishItem(OreFishType type, Properties properties) {
        super(properties);
        this.type = type;
    }

    public OreFishType type() {
        return type;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.miningdim.ore_fish.habitat"));
        lines.add(Component.translatable("tooltip.miningdim.ore_fish.uses"));
        if (type == OreFishType.DARK_GOLD) {
            lines.add(Component.translatable("tooltip.miningdim.ore_fish.highest"));
        }
    }
}
