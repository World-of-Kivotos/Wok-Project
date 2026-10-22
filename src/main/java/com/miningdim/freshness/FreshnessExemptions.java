package com.miningdim.freshness;

import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 按物品数据登记的豁免规则 (规格「豁免清单」判定顺序第 1 条)。
 *
 * 酒、奖杯鱼这类「自带数据就豁免」的判定归各模块所有 (酿酒、渔夫自己登记), 本模块只提供登记位:
 * 静态注册表对装配顺序不敏感, 谁先装配都一样; 同一 owner 只能登记一条, 重复即失败 (避免静默覆盖)。
 */
public final class FreshnessExemptions {

    private static final Map<String, Predicate<ItemStack>> RULES = new ConcurrentHashMap<>();

    private FreshnessExemptions() {
    }

    /**
     * 登记一条豁免规则。
     *
     * @param owner 规则归属 (模块键或子系统名, 例如 {@code wok-job-brewer})
     * @param rule  命中即豁免; 不得依赖世界对象 (背包扫描会在任意维度调用)
     */
    public static void register(String owner, Predicate<ItemStack> rule) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(rule, "rule");
        Predicate<ItemStack> existing = RULES.putIfAbsent(owner, rule);
        if (existing != null && existing != rule) {
            throw new IllegalStateException("Freshness exemption already registered: " + owner);
        }
    }

    /** 物品数据层面的豁免判定; 空物品一律豁免 (没有东西可烂)。 */
    public static boolean isExempt(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        for (Predicate<ItemStack> rule : RULES.values()) {
            if (rule.test(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 已登记的归属 (诊断与自检用)。 */
    public static Set<String> owners() {
        return Set.copyOf(RULES.keySet());
    }

    /** 测试专用: 清空登记表 (GameTest 必须在 finally 里复位)。 */
    public static void clearForTest() {
        RULES.clear();
    }
}
