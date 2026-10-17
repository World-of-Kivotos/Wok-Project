package com.miningdim.freshness;

/**
 * 兼容 mixin 的织入结论 (读 {@link FreshnessCompatMixinPlugin} 写进系统属性的那几个值)。
 *
 * <p>可选配置的 {@code defaultRequire = 0} 意味着"没织进去也不报错"。这一层把它变成一句能查的结论:
 * 开服时提示一次、用例里断言一次 —— 换了 FD 版本之后"继承悄悄失效"才是真会发生的故障。</p>
 */
public final class FreshnessCompatStatus {

    /** 砧板那一处 (记来源上下文)。 */
    public static final String CUTTING_BOARD = "CuttingBoardMixin";

    /** FD 喷物品那一处 (按来源盖章)。 */
    public static final String ITEM_UTILS = "ItemUtilsMixin";

    private FreshnessCompatStatus() {
    }

    /** 这个 mixin 织进去了吗 (属性没写过 = 目标类还没加载过, 也算没织上)。 */
    public static boolean woven(String mixinSimpleName) {
        return FreshnessCompatMixinPlugin.APPLIED.equals(
                System.getProperty(FreshnessCompatMixinPlugin.PROPERTY_PREFIX + mixinSimpleName));
    }

    /** 目标类加载过、但一处也没织进去 (换 FD 版本最典型的表现)。 */
    public static boolean missing(String mixinSimpleName) {
        return FreshnessCompatMixinPlugin.MISSING.equals(
                System.getProperty(FreshnessCompatMixinPlugin.PROPERTY_PREFIX + mixinSimpleName));
    }
}
