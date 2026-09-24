package com.miningdim.job.fisher;

import com.miningdim.job.fisher.quality.FishQuality;
import com.miningdim.job.fisher.size.FishSizeClass;
import com.miningdim.job.fisher.size.FishSizeFormat;
import com.miningdim.job.fisher.size.FishSizeNbt;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Optional;

/**
 * 鱼的 tooltip: 名字下方插入鱼种品质与体型。对任意物品触发, 只认已归档进品质标签或带体型标签的栈。
 *
 * 品质读客户端已同步的物品标签, 体型读已同步的 NBT, 纯渲染不写世界。事件类本身两端都有, 注册放在公共代码里
 * 不会碰客户端专属类 (与厨师 tooltip 同一做法)。
 */
public final class FishingTooltipHandler {

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        FishQuality quality = FishQuality.of(stack);
        Optional<FishSizeNbt.Info> size = FishSizeNbt.read(stack);
        if (quality == null && size.isEmpty()) {
            return;
        }
        List<Component> lines = event.getToolTip();
        int insertAt = Math.min(1, lines.size());
        if (quality != null) {
            lines.add(insertAt++, Component.translatable("tooltip.miningdim.fishing.quality", quality.displayName())
                    .withStyle(ChatFormatting.GRAY));
        }
        if (size.isPresent()) {
            FishSizeNbt.Info info = size.get();
            lines.add(insertAt++, Component.translatable("tooltip.miningdim.fishing.size", info.sizeClass().displayName())
                    .withStyle(ChatFormatting.GRAY));
            if (info.sizeClass() == FishSizeClass.TROPHY) {
                lines.add(insertAt++, Component.translatable("tooltip.miningdim.fishing.trophy",
                                FishSizeFormat.length(info.lengthMm()), FishSizeFormat.weight(info.weightMg()))
                        .withStyle(ChatFormatting.GOLD));
                if (!info.catcherName().isBlank()) {
                    lines.add(insertAt, Component.translatable("tooltip.miningdim.fishing.caught_by", info.catcherName())
                            .withStyle(ChatFormatting.DARK_GRAY));
                }
            }
        }
    }
}
