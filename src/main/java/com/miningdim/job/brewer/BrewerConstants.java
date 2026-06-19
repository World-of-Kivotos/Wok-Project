package com.miningdim.job.brewer;

/**
 * 酿酒师全局可调常量 (单一来源, 杜绝跨类各写一份漂移)。数值为 v1 初值, 平衡集中在此一处调。
 *
 * 年份时钟与潮汐 (Tide) mod 同源: 二者都读原版 level 时钟 ({@code getGameTime} / {@code getMoonPhase}),
 * 不引入自定义时钟、零跨 mod 依赖 (见 {@link VintageClock})。
 */
public final class BrewerConstants {

    private BrewerConstants() {
    }

    // ---- 年份时钟 (酒窖箱陈酿) ----

    /** 1 年份对应的原版 gameTime tick 数 (默认 24000 = 一个游戏日)。getGameTime 单调, 不受 /time、睡觉跳夜影响。 */
    public static final long TICKS_PER_VINTAGE_YEAR = 24_000L;

    /** 酒窖箱结算节流: 每多少 tick 结算一次年份/燃料 (摊薄每 tick 开销; 懒结算按 gameTime 差补齐)。 */
    public static final int CELLAR_SETTLE_INTERVAL_TICKS = 100;

    /** 满月 (moonPhase==0) 期间陈酿的额外年份加成比例 (与 Tide 满月稀有鱼同源的潮汐关联; 纯原版 API 读取)。 */
    public static final double FULL_MOON_BONUS = 0.25D;

    // ---- 干小麦燃料门控 (保持阴凉) ----

    /** 每瓶酒每陈酿 1 年份消耗的干小麦数 (要求量大: 长线职业的小麦 sink, 联动农夫经济)。 */
    public static final int DRIED_WHEAT_PER_BOTTLE_YEAR = 16;

    // ---- 闪耀永久增益 (一条命) ----

    /** 同时在身的永久增益上限 (可叠加但封顶; 满则 FIFO 替换最旧)。 */
    public static final int MAX_PERMANENT_BUFFS = 3;

    /** 金酒永久生命上限的硬帽 (额外最大生命, 单位半心=1.0; 防生命叠叠乐, 经 MaxHealthModifierManager 的 capUp 执行)。 */
    public static final double GIN_MAX_HEALTH_CAP = 10.0D;

    /** 伏特加永久减伤比例 (0.20 = 减 20% 伤害; 由受伤结算读取)。 */
    public static final double VODKA_DAMAGE_REDUCTION = 0.20D;
}
