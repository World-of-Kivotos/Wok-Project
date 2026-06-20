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

    // ---- 酿造 (酿酒台, 阶段 3) ----

    /** 一次酿造耗时 (tick); 7 天周期的前半段 (后半段为酒窖箱陈酿)。 */
    public static final int BREW_DURATION_TICKS = 2_400;
    /** 一次酿造产出的基酒瓶数。 */
    public static final int BREW_OUTPUT_COUNT = 1;

    // ---- 干小麦燃料门控 + 陈酿/变质 (酒窖箱, 阶段 4) ----

    /** 酒窖箱的陈酿酒槽数 (干小麦燃料槽另算)。12 瓶高年份满产 ≈ 满级 L10 农夫供给 (对标农夫产量, 见设计文档)。 */
    public static final int CELLAR_WINE_SLOTS = 12;

    /** 每瓶酒每陈酿 1 年份的【基础】干小麦耗量 (年份 0 时的量; 实耗随年份二次递增, 见 {@link #FUEL_QUAD_COEF})。 */
    public static final int DRIED_WHEAT_PER_BOTTLE_YEAR = 16;

    /** 干小麦耗量随年份【二次】递增的系数: 一瓶 vintage V 的酒每年实耗 = 基础 + 此值×V² (超线性, 嫩酒便宜、
     *  高年份指数爆炸)。5 => v10 耗 516、v25 耗 3141/瓶/年; 满 12 瓶 v25 ≈ 3.8 万小麦/天 ≈ 满级农夫专职供给。 */
    public static final double FUEL_QUAD_COEF = 5.0D;

    /** 断干小麦时年份的衰退速率 (每现实天倒扣的年份)。200 => v25 满酒断粮约 3 小时归零变质 (全玩家掌控、无 RNG,
     *  替代随机损毁; 逼"高端闪耀酒必须有不间断的农夫供应链, 链一断宝贝几小时就没")。 */
    public static final double SPOILAGE_DECAY_YEARS_PER_DAY = 200.0D;

    // ---- 闪耀永久增益 (一条命) ----

    /** 同时在身的永久增益上限 (可叠加但封顶; 满则 FIFO 替换最旧)。 */
    public static final int MAX_PERMANENT_BUFFS = 3;

    /** 金酒永久生命上限的硬帽 (额外最大生命, 单位半心=1.0; 防生命叠叠乐, 经 MaxHealthModifierManager 的 capUp 执行)。 */
    public static final double GIN_MAX_HEALTH_CAP = 10.0D;

    /** 伏特加永久减伤比例 (0.20 = 减 20% 伤害; 由受伤结算读取)。 */
    public static final double VODKA_DAMAGE_REDUCTION = 0.20D;

    // ---- 喝酒效果缩放 (强度 S = 年份 × 品质系数; "部分软上限"见设计文档第三节) ----
    // 软上限 = 软化强度: S 超过 knee 后只按 diminish 折算 (越往上越难推)。战斗类 (抗性/力量) 收紧, 其余放宽。

    /** 战斗类软上限拐点。 */
    public static final double COMBAT_SOFTCAP_KNEE = 8.0D;
    /** 战斗类超拐点后的递减系数 (0.15 = 超出部分只算 15%)。 */
    public static final double COMBAT_SOFTCAP_DIMINISH = 0.15D;
    /** 续航/工具/经济类软上限拐点。 */
    public static final double LOOSE_SOFTCAP_KNEE = 16.0D;
    /** 续航/工具/经济类超拐点后的递减系数。 */
    public static final double LOOSE_SOFTCAP_DIMINISH = 0.40D;

    /** 持续效果: 每多少"软化强度"提升 1 放大等级 (floor)。 */
    public static final double AMP_PER_SOFT_STRENGTH = 6.0D;
    /** 持续效果基础时长 (tick, 20 秒)。 */
    public static final int EFFECT_BASE_DURATION_TICKS = 400;
    /** 持续效果每点软化强度追加时长 (tick)。 */
    public static final int EFFECT_DURATION_PER_SOFT = 30;
    /** 持续效果时长上限 (tick, 5 分钟)。 */
    public static final int EFFECT_MAX_DURATION_TICKS = 6000;
    /** 战斗类持续效果放大等级上限 (0-indexed; 1 = 等级 II, 直接战力收紧)。 */
    public static final int AMP_CAP_COMBAT = 1;
    /** 续航/工具类持续效果放大等级上限 (0-indexed; 2 = 等级 III)。 */
    public static final int AMP_CAP_LOOSE = 2;

    /** 威士忌瞬间恢复: 每点软化强度恢复的生命 (半心 = 1.0)。 */
    public static final double WHISKEY_HEAL_PER_SOFT = 0.5D;
    /** 茅台: 每点软化强度给的经验值。 */
    public static final int MAOTAI_XP_PER_SOFT = 2;

    /** 月光赌博: 好结果基础概率。 */
    public static final double MOONSHINE_GOOD_BASE_PROB = 0.40D;
    /** 月光赌博: 每点强度对好结果概率的加成 (强度越高越可能好)。 */
    public static final double MOONSHINE_GOOD_PROB_PER_STRENGTH = 0.01D;
    /** 月光赌博: 好结果概率上限 (永远留一线翻车)。 */
    public static final double MOONSHINE_GOOD_PROB_MAX = 0.85D;
    /** 月光赌博: 坏结果效果时长 (tick, 等级 I 的小惩罚, 死亡不掉落服上不致死)。 */
    public static final int MOONSHINE_BAD_DURATION_TICKS = 300;
}
