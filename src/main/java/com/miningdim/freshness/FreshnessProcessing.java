package com.miningdim.freshness;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * 加工继承的纯策略 (Spoilage_Granary_DesignSpec「继承规则」「加工与压缩」)。
 *
 * 做成不依赖世界对象的纯函数, 是为了让 mixin 只当薄适配层: 每个注入点把「原料 + 产物 + 现在」交给这里,
 * 判据与数值全部在 GameTest 里直接锁 (不用为了测一条加热加成去搭一台真熔炉)。
 *
 * 三条规则:
 * <ol>
 *   <li>产物剩余比例 = 原料里**最差**的剩余比例 + 加成, 封顶 1 —— 防新货稀释老货 (取最差不取平均);</li>
 *   <li>停表额度取原料里最大的 —— 堵「冻满额度 → 做熟 → 再冻满」(反漏洞 #26);</li>
 *   <li>任何一件原料已过期就不出产物 —— 机器把过期原料换成腐烂物, 合成格直接不出结果 (反漏洞 #5、#7)。</li>
 * </ol>
 */
public final class FreshnessProcessing {

    /** 加热工序的加成: 熔炉、烟熏炉、高炉、营火、FD 煎锅/炉灶/厨锅、refurbished 厨具、本 MOD 炸锅与烤炉。 */
    public static final double HEAT_BONUS = 0.25;

    /** 不给加成的工序: 合成、砧板切配、备餐台、压缩、BnC 发酵桶。 */
    public static final double NO_BONUS = 0.0;

    private FreshnessProcessing() {
    }

    /** 原料里有没有已经过期的; 有就不出产物 (豁免品与不腐烂的东西不参与判定)。 */
    public static boolean anyRotten(List<ItemStack> ingredients, long nowMinute) {
        for (ItemStack ingredient : ingredients) {
            if (ingredient.isEmpty() || !FreshnessCategories.spoils(ingredient)) {
                continue;
            }
            int shelfLifeHours = FreshnessCategories.shelfLifeHours(ingredient);
            if (FreshnessData.isRotten(ingredient, shelfLifeHours, nowMinute)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把原料的新鲜度继承给产物。
     *
     * @param product     产物 (会被就地盖章)
     * @param ingredients 参与继承的原料 (空格子会自动跳过)
     * @param bonus       工序加成, 见 {@link #HEAT_BONUS} / {@link #NO_BONUS}
     * @param nowMinute   现在的分钟序号
     */
    public static void inherit(ItemStack product, List<ItemStack> ingredients, double bonus, long nowMinute) {
        if (product.isEmpty()) {
            return;
        }
        Optional<FreshnessCategory> productCategory = FreshnessCategories.categoryOf(product);
        if (productCategory.isEmpty()) {
            return;
        }
        int productShelfLifeHours = FreshnessConfig.shelfLifeHours(productCategory.get());

        double worstRatio = 1.0D;
        int maxStoppedHours = 0;
        for (ItemStack ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            Optional<FreshnessCategory> ingredientCategory = FreshnessCategories.categoryOf(ingredient);
            if (ingredientCategory.isEmpty()) {
                continue; // 豁免原料不参与继承
            }
            int shelfLifeHours = FreshnessConfig.shelfLifeHours(ingredientCategory.get());
            // 还没盖章的原料按 1 算 (规格「继承规则」)。
            double ratio = 1.0D;
            if (FreshnessData.isStamped(ingredient)) {
                long remaining = FreshnessData.remainingMinutes(ingredient, shelfLifeHours, nowMinute);
                ratio = Math.max(0.0D, Math.min(1.0D, remaining / (shelfLifeHours * 60.0D)));
            }
            worstRatio = Math.min(worstRatio, ratio);
            maxStoppedHours = Math.max(maxStoppedHours, FreshnessData.stoppedHours(ingredient));
        }

        double ratio = Math.min(1.0D, worstRatio + bonus);
        // 产物剩余小时 = 产物保质期 × 比例, 向下取整, 最少 1 小时。
        long productRemainingMinutes = Math.max(60L,
                (long) Math.floor(productShelfLifeHours * 60.0D * ratio));
        long batchMinute = nowMinute + productRemainingMinutes
                - (long) productShelfLifeHours * 60L - (long) maxStoppedHours * 60L;
        FreshnessData.stampAt(product, batchMinute, maxStoppedHours);
    }
}
