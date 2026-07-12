package com.miningdim.champion;

/**
 * 八红线阈值单一权威 (ChampionStarAffix spec 第三章红线 1-8)。所有封顶/聚合逻辑 (净减伤钳制 / DoT 聚合 /
 * 反伤累加 / 控制聚合 / 单击上限 / 召唤封顶) 一律引用本常量, 消灭散落魔数副本 (本工程自述目的: 消除逐字
 * 复制的数值漂移温床)。
 *
 * 纯常量, 无世界引用。配置化推迟 (spec 第十三章 PENDING 5 各 config 默认值待定): 本表为定稿硬值的唯一
 * 拷贝, 一旦 ConfigSystem 暴露对应键应改为读配置 (留待接线), 当前是红线生效所必需的真实数值, 非占位。
 */
public final class ChampionRedlines {

    private ChampionRedlines() {
    }

    // ---- 红线 1: 净减伤全局硬封顶 ≤75% ----

    /**
     * 净减伤上限 (spec 红线 1; 2026-07-07 用户定向 49%→75%): 单点求 keep = ∏(1-rᵢ) 后统一 keep = max(keep, 1-0.75)。
     * 抬帽前提 = 复合装甲改【同源适应】(按伤害类别分桶爬升, 换类别即重置), 玩家有真实反制手段 (换武器/丢雷) 而非
     * 纯血海; 绝无 100% 免伤 (keep 恒 ≥0.25, 枪械 attrition 永远有效)。
     */
    public static final double NET_DAMAGE_REDUCTION_CAP = 0.75D;

    /** 净减伤保底剩余系数 = 1 - {@link #NET_DAMAGE_REDUCTION_CAP} = 0.25 (最终伤害 ≥ 原始 ×0.25)。 */
    public static final double MIN_KEEP_FACTOR = 1.0D - NET_DAMAGE_REDUCTION_CAP;

    // ---- 红线 2: 反伤 (按攻击者 maxHP 的 %) ----

    /** per-attacker 滚动秒窗: 所有反伤源累加后统一 ≤30% attacker maxHP/s (非各源独立)。 */
    public static final double RETALIATION_PER_SECOND_CAP_PCT = 0.30D;

    /** 单次反击窗口累计 ≤40% attacker maxHP。 */
    public static final double RETALIATION_PER_WINDOW_CAP_PCT = 0.40D;

    /** 反击单元单窗口时长上限 (spec 7.4 反击单元: 窗口 ≤5s)。 */
    public static final double RETALIATION_WINDOW_SECONDS = 5.0D;

    // ---- 红线 3: 不秒杀 (单击/连段/可躲技能名义 %maxHP 上限) ----
    // 普通单击三档上限见 StarRank.normalHitCapPct()。以下为跨星统一的名义上限。

    /** 带预兆可躲技能 (蓄力/核弹) 任何单次名义值硬上限 ≤90% maxHP (裸血玩家也不被一击删)。 */
    public static final double TELEGRAPHED_HIT_CAP_PCT = 0.90D;

    /** 连段 (利刃华尔兹) 整套总伤 ≤60% maxHP。 */
    public static final double COMBO_TOTAL_CAP_PCT = 0.60D;

    /** 大额 AOE/核弹命中后给被击玩家的伤害免疫缓冲时长 (秒); 0.5s 内多来源叠加防叠杀。 */
    public static final double DAMAGE_IMMUNITY_BUFFER_SECONDS = 2.0D;

    // ---- 红线 4: 持续伤害聚合封顶 ----

    /** 同一玩家身上所有 DoT 每秒合计硬封顶 ≤15% maxHP/s (按贡献比例衰减)。 */
    public static final double DOT_PER_SECOND_CAP_PCT = 0.15D;

    /** DoT 刷新内 CD (≥1s/源), 禁止逐跳无限维持满层。 */
    public static final double DOT_REFRESH_INTERNAL_CD_SECONDS = 1.0D;

    // ---- 红线 5: 不永控 ----

    /** 减速总量硬封顶 ≤50% (绝不定身)。 */
    public static final double SLOW_TOTAL_CAP_PCT = 0.50D;

    /** 控制聚合滚动窗时长 (秒): 任意 7s 滚动窗内受控总时长 ≤50%。 */
    public static final double CONTROL_ROLLING_WINDOW_SECONDS = 7.0D;

    /** 控制聚合滚动窗内受控总时长占比上限 ≤50%。 */
    public static final double CONTROL_WINDOW_BUSY_RATIO_CAP = 0.50D;

    /** 必须存在的连续完全自由窗最小时长 (秒): ≥2s 可瞄准/开火/移动。 */
    public static final double CONTROL_MIN_FREE_WINDOW_SECONDS = 2.0D;

    // ---- 红线 8: 召唤三重封顶 ----

    /** 召唤星级偏移: 召唤星级 = clamp(自身星 - 2, 1, {@link #SUMMON_STAR_ABSOLUTE_CEIL})。 */
    public static final int SUMMON_STAR_OFFSET = 2;

    /** 召唤星级绝对天花板 (spec 第十三章 PENDING 2 默认 4★; config 暴露)。 */
    public static final int SUMMON_STAR_ABSOLUTE_CEIL = 4;

    /**
     * 把净减伤参与项的剩余系数连乘后夹到红线 1 保底 (spec 红线 1 / 9.2): keep = max(∏(1-rᵢ), 0.25)。
     * 单一受击拦截点调用; 删本钳制后净减伤多源相乘可穿透 75%。
     *
     * @param reductionRates 各减伤源的减伤率 rᵢ (0-1; bullet_resistance + 复合同源适应 ramp + 偏斜 EV + 刚毅折算 +
     *                       缩小化体型折算)。任何 rᵢ &lt;0 或 &gt;1 抛 IllegalArgumentException (不掩盖脏值)。
     * @return 夹断后的剩余系数 keep (∈ [0.25, 1.0]); 最终伤害 = 原始 × keep
     */
    public static double clampNetKeepFactor(double... reductionRates) {
        double keep = 1.0D;
        for (double r : reductionRates) {
            if (r < 0.0D || r > 1.0D) {
                throw new IllegalArgumentException("reduction rate out of [0,1]: " + r);
            }
            keep *= (1.0D - r);
        }
        return Math.max(keep, MIN_KEEP_FACTOR);
    }
}
