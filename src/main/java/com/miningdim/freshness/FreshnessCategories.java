package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 物品 → 保质期类别的唯一判定入口 (规格「保质期与豁免」判定顺序):
 *
 * <ol>
 *   <li>物品自带数据的豁免规则 (本 MOD 的酒、Vinery 带年份的酒、奖杯鱼) —— 各模块登记进 {@link FreshnessExemptions};</li>
 *   <li>豁免标签 {@code miningdim:freshness_exempt};</li>
 *   <li>六个类别标签;</li>
 *   <li>没标类别、但能吃的, 一律归熟食 (48 小时兜底);</li>
 *   <li>其余不腐烂。</li>
 * </ol>
 *
 * 所以「不能直接吃」的东西 (小麦、稻米、南瓜、整块西瓜、奶酪轮) 必须写进类别标签 —— 开服自检逐条核对。
 */
public final class FreshnessCategories {

    /** 豁免标签: 不腐烂、不盖章、粮仓不收。 */
    public static final TagKey<Item> EXEMPT = TagKey.create(Registries.ITEM,
            new ResourceLocation(MiningConstants.MODID, "freshness_exempt"));

    private FreshnessCategories() {
    }

    /** 是否属于「按规则不腐烂」的物品 (豁免标签或模块登记的豁免规则)。 */
    public static boolean isExempt(ItemStack stack) {
        return stack.isEmpty() || stack.is(EXEMPT) || FreshnessExemptions.isExempt(stack);
    }

    /** 类别; 空物品与豁免品返回空。 */
    public static Optional<FreshnessCategory> categoryOf(ItemStack stack) {
        if (stack.isEmpty() || isExempt(stack)) {
            return Optional.empty();
        }
        for (FreshnessCategory category : FreshnessCategory.values()) {
            if (stack.is(category.tag())) {
                return Optional.of(category);
            }
        }
        // 兜底: 能吃但没标类别的, 一律按熟食算 (48 小时), 免得新模组的食物永远不坏。
        return stack.isEdible() ? Optional.of(FreshnessCategory.COOKED) : Optional.empty();
    }

    /** 该物品会不会腐烂。 */
    public static boolean spoils(ItemStack stack) {
        return categoryOf(stack).isPresent();
    }

    /** 该物品的保质期 (小时); 不腐烂返回 0。 */
    public static int shelfLifeHours(ItemStack stack) {
        return categoryOf(stack).map(FreshnessShelfLives::hours).orElse(0);
    }
}
