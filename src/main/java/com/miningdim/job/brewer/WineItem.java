package com.miningdim.job.brewer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

import java.util.List;

/**
 * 一种酒的 Item (九种酒各一; 类型即物品身份, 品质与年份存 NBT, 见 {@link WineNbt})。可饮 (原版药水范式: 喝完
 * {@link #finishUsingItem} 结算), 喝下按 S = 年份×品质系数 走 {@link BrewEffectEngine}。
 *
 * stacksTo(16): 同品质同年份 (同 NBT) 可堆叠; 年份一异即天然分栈 (酒窖箱里各栈独立陈酿)。
 */
public final class WineItem extends Item {

    private final WineType type;

    public WineItem(Properties properties, WineType type) {
        super(properties.stacksTo(16));
        this.type = type;
    }

    public WineType wineType() {
        return type;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.DRINK;
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 32;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return ItemUtils.startUsingInstantly(level, player, hand);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        Player player = entity instanceof Player p ? p : null;
        if (!level.isClientSide && entity instanceof ServerPlayer serverPlayer) {
            // 服务端权威结算 (闪耀永久增益在阶段 5 接入)。
            BrewEffectEngine.applyOnDrink(serverPlayer, type, stack);
        }
        if (player == null || !player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        // 空瓶返还 (同原版药水)。
        if (player == null || !player.getAbilities().instabuild) {
            if (stack.isEmpty()) {
                return new ItemStack(Items.GLASS_BOTTLE);
            }
            if (player != null && !player.getInventory().add(new ItemStack(Items.GLASS_BOTTLE))) {
                player.drop(new ItemStack(Items.GLASS_BOTTLE), false);
            }
        }
        entity.gameEvent(GameEvent.DRINK);
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        WineQuality quality = WineNbt.readQuality(stack);
        if (quality == null) {
            // 未盖章 (创造直给的空白酒): 无品质/年份显示, 喝下也无效 (强度 0)。
            return;
        }
        tooltip.add(Component.translatable(quality.prefixKey()).withStyle(quality.color()));
        tooltip.add(Component.translatable("tooltip.miningdim.brewer.vintage",
                String.format("%.1f", WineNbt.readVintage(stack))).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.brewer.strength",
                String.format("%.1f", WineNbt.strength(stack))).withStyle(ChatFormatting.DARK_GRAY));
    }
}
