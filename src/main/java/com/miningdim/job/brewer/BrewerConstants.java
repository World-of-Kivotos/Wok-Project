package com.miningdim.job.brewer;

/**
 * 酿酒师全局可调常量 (单一来源, 杜绝跨类各写一份漂移)。数值为 v1 初值, 平衡集中在此一处调。
 *
 * 年份时钟用现实挂钟 (服务端 {@code System.currentTimeMillis()}, 与经济衰减闸的 UTC 时间观同源): 离线/区块卸载
 * 也陈酿 (酒窖本就该你不在也熟), "至少七天周期"字面=现实天, 服务端权威无客户端作弊。潮汐 (Tide) 关联保留在
 * 月相加成上 (同读原版 {@code level.getMoonPhase()}, 这才是 Tide 满月稀有鱼的真味), 见 {@link VintageClock}。
 */
public final class BrewerConstants {

    private BrewerConstants() {
    }

    // ---- 年份时钟 (酒窖箱陈酿; 现实挂钟) ----

    /** 1 年份对应的现实毫秒数 (默认 86_400_000 = 现实一天)。配合软上限锚 ~25 年, 顶级酒满熟约 3-4 周 (硬核长线)。 */
    public static final long MILLIS_PER_VINTAGE_YEAR = 86_400_000L;

    /** 酒窖箱结算节流: 每多少 tick 唤醒一次结算 (摊薄每 tick 开销; 懒结算按现实挂钟差补齐加载/离线期间的年份与燃料)。 */
    public static final int CELLAR_SETTLE_INTERVAL_TICKS = 100;

    /** 满月 (moonPhase==0) 期间陈酿的额外年份加成比例 (与 Tide 满月稀有鱼同源的潮汐关联; 纯原版 API 读取)。 */
    public static final double FULL_MOON_BONUS = 0.25D;

    // ---- 干小麦燃料门控 (保持阴凉) ----

    /** 每瓶酒每陈酿 1 年份的【基础】干小麦耗量 (要求量大: 长线职业的小麦 sink, 联动农夫经济)。实际耗量随年份
     *  递增 (老酒烧钱凶, 自然经济封顶), 递增公式与变质结算落在酒窖箱阶段 (阶段 4)。 */
    public static final int DRIED_WHEAT_PER_BOTTLE_YEAR = 16;

    // ---- 闪耀永久增益 (一条命) ----

    /** 同时在身的永久增益上限 (可叠加但封顶; 满则 FIFO 替换最旧)。 */
    public static final int MAX_PERMANENT_BUFFS = 3;

    /** 金酒永久生命上限的硬帽 (额外最大生命, 单位半心=1.0; 防生命叠叠乐, 经 MaxHealthModifierManager 的 capUp 执行)。 */
    public static final double GIN_MAX_HEALTH_CAP = 10.0D;

    /** 伏特加永久减伤比例 (0.20 = 减 20% 伤害; 由受伤结算读取)。 */
    public static final double VODKA_DAMAGE_REDUCTION = 0.20D;
}
