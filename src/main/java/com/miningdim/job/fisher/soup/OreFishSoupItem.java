package com.miningdim.job.fisher.soup;

import com.miningdim.job.fisher.ore.OreFishType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;

/** Food item whose ore-fish effect is settled only after vanilla food consumption succeeds. */
public final class OreFishSoupItem extends Item {
    private final OreFishType type;

    public OreFishSoupItem(OreFishType type, Properties properties) {
        super(properties);
        this.type = type;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.miningdim.ore_fish_soup." + type.id() + ".effect")
                .withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("tooltip.miningdim.ore_fish_soup.duration").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.miningdim.ore_fish_soup.dimension").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.miningdim.ore_fish_soup.chef_duration").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        ItemStack consumedStack = stack.copy();
        ItemStack result = super.finishUsingItem(stack, level, entity);
        if (!level.isClientSide && entity instanceof ServerPlayer player) {
            OreSoupEffects.applyConsumedSoup(player, type, consumedStack);
            if (!player.getAbilities().instabuild) {
                if (result.isEmpty()) {
                    return new ItemStack(Items.BOWL);
                }
                ItemStack bowl = new ItemStack(Items.BOWL);
                if (!player.getInventory().add(bowl)) {
                    player.drop(bowl, false);
                }
            }
        }
        return result;
    }
}
