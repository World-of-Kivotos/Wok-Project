package com.miningdim.freshness;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * 「来源上下文」(Spoilage_Granary_DesignSpec「加工与压缩」「来源上下文」)。
 *
 * 有些工序**没有把原料交到产物那一下**可拿: FD 砧板先按配方算出产物、再统一走
 * {@code ItemUtils.spawnItemEntity} 把东西喷出来 —— 喷出来的那一刻手里只有产物, 原料已经不在参数里了。
 * 所以工序开始时把原料塞进这个上下文, 产物落地那一刻再取出来算继承 (加成 0: 切配不改新鲜度)。
 *
 * <p>用 {@link ThreadLocal} 而不是全局字段: 服务端 tick 是单线程, 但区块生成 / 结构放置里也可能间接触发
 * 同类工序, 用栈保存能把嵌套的那一层还回去。</p>
 *
 * <p>**只用服务端**: 客户端手里的产物不需要章 (显示读的是同步下来的时钟与物品自带的章), 而上下文里的
 * 原料在客户端也没有意义。</p>
 */
public final class FreshnessSourceContext {

    private static final ThreadLocal<ItemStack> CURRENT = new ThreadLocal<>();

    private FreshnessSourceContext() {
    }

    /** 工序开始: 记下这一趟的原料 (空手就清掉, 免得沿用上一层)。 */
    public static void begin(ItemStack ingredient) {
        if (ingredient == null || ingredient.isEmpty()) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(ingredient.copy());
    }

    /** 工序结束: 无论成败都要调 (放在 finally 里, 异常路径也不能把上下文留给下一趟)。 */
    public static void end() {
        CURRENT.remove();
    }

    /** 这一趟的原料 (没有就是 empty)。 */
    public static Optional<ItemStack> current() {
        ItemStack stack = CURRENT.get();
        return stack == null || stack.isEmpty() ? Optional.empty() : Optional.of(stack);
    }

    /**
     * 产物按来源继承 (没有来源就什么都不做)。
     *
     * @param product   产物 (会被就地盖章)
     * @param bonus     工序加成 (砧板是 0)
     * @param nowMinute 现在的分钟序号
     * @return 这一趟真的按来源盖了章吗 (用例与自检据此判断注入点有没有织进去)
     */
    public static boolean inheritInto(ItemStack product, double bonus, long nowMinute) {
        Optional<ItemStack> source = current();
        if (source.isEmpty() || product == null || product.isEmpty()) {
            return false;
        }
        if (!FreshnessCategories.spoils(product)) {
            return false;
        }
        FreshnessProcessing.inherit(product, List.of(source.get()), bonus, nowMinute);
        return true;
    }
}
