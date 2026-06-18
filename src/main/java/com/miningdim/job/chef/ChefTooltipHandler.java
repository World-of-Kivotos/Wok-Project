package com.miningdim.job.chef;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * 品质 tooltip 追加 (Chef_Job_DesignSpec 第三章; 订阅 {@link ItemTooltipEvent}, 对任意物品触发, 模组菜也吃)。
 *
 * 读 {@link ChefQualityNbt} 的品质 + 效果列表, 在 tooltip 追加:
 *  - 品质行 (带品质颜色);
 *  - 每个效果一行 (效果名 lang key + 该实例的强度概述)。
 *
 * 纯客户端渲染读已同步的 ItemStack NBT, 不做任何世界写。
 */
public final class ChefTooltipHandler {

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        ChefQuality quality = ChefQualityNbt.readQuality(stack);
        if (quality == null) {
            return;
        }
        List<Component> lines = event.getToolTip();
        lines.add(Component.translatable("chef.tooltip.quality",
                Component.translatable(quality.prefixKey()).withStyle(quality.color())));

        for (ChefEffectInstance inst : ChefQualityNbt.readEffects(stack)) {
            MutableComponent line = Component.literal(" - ")
                    .append(Component.translatable("chef.effect." + inst.type().id()))
                    .withStyle(inst.type().isNegative() ? ChatFormatting.RED
                            : inst.type().isCombat() ? ChatFormatting.GOLD : ChatFormatting.GRAY);
            lines.add(line);
        }
    }
}
