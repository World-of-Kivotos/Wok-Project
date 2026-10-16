package com.miningdim.freshness.client;

import com.miningdim.freshness.FreshnessCategory;
import com.miningdim.freshness.FreshnessCategories;
import com.miningdim.freshness.FreshnessDisplay;
import com.miningdim.freshness.FreshnessShelfLives;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

/**
 * 物品 tooltip 的新鲜度两行 (规格「客户端显示」: 类别与剩余; 阈值 24 / 6 小时与保鲜条共用一份常量)。
 *
 * 只读物品上的章 + 同步下来的钟: 客户端**不做任何判定**, 因此 tooltip 与条、与市场页显示的是同一份算术。
 * 未同步 (刚进服那零点几秒) 时只显示类别, 不显示剩余 —— 编一个数是更坏的选择。
 */
public final class FreshnessTooltipHandler {

    private FreshnessTooltipHandler() {
    }

    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!FreshnessClientState.synced() || event.getItemStack().isEmpty()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (FreshnessCategories.categoryOf(stack).isEmpty()) {
            return; // 不腐烂的东西不加行 (tooltip 已经够长了)
        }
        FreshnessCategory category = FreshnessCategories.categoryOf(stack).orElseThrow();
        int shelfLife = FreshnessShelfLives.hours(category);
        long remaining = FreshnessClientState.remainingMinutes(stack);
        if (remaining == Long.MAX_VALUE) {
            return;
        }
        event.getToolTip().add(Component.translatable(category.translationKey())
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("  " + shelfLife + "h").withStyle(ChatFormatting.DARK_GRAY)));
        event.getToolTip().add(freshnessLine(remaining));
    }

    /** 剩余那一行: 已腐烂 / 数字 + 档位颜色 (颜色与条同一份阈值)。 */
    static Component freshnessLine(long remainingMinutes) {
        FreshnessDisplay.Level level = FreshnessDisplay.level(remainingMinutes, false);
        if (level == FreshnessDisplay.Level.ROTTEN) {
            return Component.translatable(FreshnessTooltipKeys.ROTTEN).withStyle(ChatFormatting.RED);
        }
        long hours = remainingMinutes / 60L;
        Component amount = hours >= 24L
                ? Component.translatable(FreshnessTooltipKeys.DAYS, hours / 24L)
                : Component.translatable(FreshnessTooltipKeys.HOURS, hours);
        return Component.translatable(FreshnessTooltipKeys.REMAINING, amount)
                .withStyle(switch (level) {
                    case FRESH -> ChatFormatting.GREEN;
                    case WARN -> ChatFormatting.YELLOW;
                    default -> ChatFormatting.RED;
                });
    }

    /** 语言键 (集中一处, 免得散落在字符串拼接里)。 */
    static final class FreshnessTooltipKeys {

        static final String REMAINING = "tooltip.miningdim.freshness.remaining";
        static final String HOURS = "tooltip.miningdim.freshness.hours";
        static final String DAYS = "tooltip.miningdim.freshness.days";
        static final String ROTTEN = "tooltip.miningdim.freshness.rotten";

        private FreshnessTooltipKeys() {
        }
    }
}
