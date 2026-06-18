package com.miningdim.job;

/**
 * 共享等级/经验曲线 + 每日有效经验软上限衰减 (JobFramework_Shared_Foundation_DesignSpec 第四章, 唯一数据源)。
 *
 * 逐级断点权威源: FarmingXP 表A (docs/FarmingXP_Mod_DesignSpec.md:38-49) 与 Engineer 7.1
 * (docs/MillenniumEngineer_Mod_DesignSpec.md:166-179), 两表逐字一致, 总 61,900。本类把这份断点收编为
 * 唯一拷贝, 消除现 4-5 份逐字复制的 spec 漂移温床 (框架 spec 第四章自述目的)。
 *
 * 每日有效经验软上限衰减表 (框架 spec 第四章, 2000 系, 取代各职业旧表): 按 "当日已累计有效经验" 落在哪一段
 * 决定本次原始经验的乘系数 —— 玩家当日刷得越多, 后续每点原始经验折算的有效经验越少 (反通胀), UTC 翻日重置。
 * 注意末档系数 0.02 (近乎归零但不为 0, 文档无 0 截断)。
 *
 * 精度纪律 (修复小额逐笔 floor 归零漏洞): 折算引擎 {@link #applyDailyDecayExact} 全程 double 不取整, 由
 * {@link JobProgress} 以 double 累计当日/累计有效经验, 仅在读出/派生等级时再 floor。这保证多笔小额 grant
 * (例农夫单株原始 2, 落 x0.4 档每笔 0.8 有效) 的小数被累加进位, 而非每笔 floor(0.8)=0 被吞光 —— 旧实现
 * 在 long 返回值上逐笔 floor, 使整个软上限带在小额入账路径下产出恒为 0, 进度曲线名存实亡。
 * {@link #applyDailyDecay} 是 floor 版便捷入口 (直接断言/文档口径用), 委派同一引擎, 数值口径一致。
 *
 * ch10 config 化推迟决定: 框架 spec 第十章方向是经验曲线/每日衰减档走 MiningServerConfig ForgeConfigSpec
 * 分职业分段。本表数值已定稿, 故 ch10 的 config 化推迟 —— 本类常量为定稿硬值的唯一拷贝, 非遗漏; 运营期
 * 需改档时再按 ch10 迁入 config 读取。此处显式登记该决定, 避免后人当作缺口。
 *
 * 纯函数/常量, 无世界引用; grant 入账与翻日由 {@link JobProgress#grantXp} 调用 {@link #applyDailyDecayExact}。
 */
public final class JobXpCurve {

    private JobXpCurve() {
    }

    /** 等级取值域下界 (1 级新人)。 */
    public static final int MIN_LEVEL = 1;

    /** 等级取值域上界 (10 级毕业)。 */
    public static final int MAX_LEVEL = 10;

    /**
     * 达到各级所需累计有效经验 (索引 = level - 1; 即 CUMULATIVE_XP[0] 是达到 L1 所需 = 0,
     * CUMULATIVE_XP[9] 是达到 L10 所需 = 61,900)。逐级增量 = 升至下一级所需经验。
     * 校验: 3300+3800+4500+5300+6300+7400+8800+10300+12200 = 61900。
     */
    private static final long[] CUMULATIVE_XP = {
            0L,      // L1 达到
            3_300L,  // L2 达到
            7_100L,  // L3
            11_600L, // L4
            16_900L, // L5
            23_200L, // L6
            30_600L, // L7
            39_400L, // L8
            49_700L, // L9
            61_900L  // L10 毕业
    };

    /** 达到 L10 (毕业) 所需累计有效经验; 满级后超额经验不再升级 (仍累计但封顶等级)。 */
    public static final long GRADUATION_XP = CUMULATIVE_XP[MAX_LEVEL - 1];

    // ---- 每日有效经验软上限衰减分段 (框架 spec 第四章 2000 系) ----
    // 区间 [0,2000) x1.0 / [2000,2800) x0.4 / [2800,3400) x0.2 / [3400,3800) x0.08 / [3800,+inf) x0.02

    private static final long DECAY_T1 = 2_000L;
    private static final long DECAY_T2 = 2_800L;
    private static final long DECAY_T3 = 3_400L;
    private static final long DECAY_T4 = 3_800L;

    private static final double MULT_1 = 1.0D;
    private static final double MULT_2 = 0.4D;
    private static final double MULT_3 = 0.2D;
    private static final double MULT_4 = 0.08D;
    private static final double MULT_5 = 0.02D;

    /**
     * 当日有效经验软上限末档边界 (= {@link #DECAY_T4}): 当日有效经验达此值即进入末档 x0.02 涓流,
     * /job list 的 "当日剩余衰减额度" 以此为分母。单一权威, 供 {@link JobProgress#dailyRemaining()} 引用,
     * 消除 3800 魔数副本 (本框架自述目的即消灭逐字复制的数值漂移温床)。
     */
    public static final long DAILY_SOFTCAP = DECAY_T4;

    /**
     * 根据 "达到本级所需累计有效经验" 反查等级 (1-10)。totalXp 落在 [CUMULATIVE_XP[i], CUMULATIVE_XP[i+1])
     * 即为 i+1 级; >= GRADUATION_XP 即 10 级。
     *
     * @param totalXp 累计有效经验 (>=0)
     * @return 对应等级 (钳制在 [MIN_LEVEL, MAX_LEVEL])
     */
    public static int levelForTotalXp(long totalXp) {
        if (totalXp < 0L) {
            throw new IllegalArgumentException("totalXp must be >= 0, got " + totalXp);
        }
        if (totalXp >= GRADUATION_XP) {
            return MAX_LEVEL;
        }
        // 线性扫描 10 档断点 (规模极小, 不必二分): 找最后一个 <= totalXp 的断点索引。
        int level = MIN_LEVEL;
        for (int i = 0; i < CUMULATIVE_XP.length; i++) {
            if (totalXp >= CUMULATIVE_XP[i]) {
                level = i + 1;
            } else {
                break;
            }
        }
        return level;
    }

    /** 达到指定等级所需累计有效经验 (level 在 [MIN_LEVEL, MAX_LEVEL])。 */
    public static long cumulativeXpForLevel(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level out of [1,10]: " + level);
        }
        return CUMULATIVE_XP[level - 1];
    }

    /**
     * 折算引擎 (精确版, 全程 double 不取整): 当日已累计有效经验 currentDailyXp 之上, 把一笔 rawXp 经分段衰减
     * 折算成 "有效经验" 增量 (框架 spec 第四章)。
     *
     * 权威模型 = "有效经验容量模型" (B 解释): 衰减分段边界 (2000/2800/3400/3800) 划分的是 "有效经验" 轴,
     * 每段持有一段 "有效经验容量" (= segEnd - 当前有效指针)。本次原始经验按本段系数折算去 "填满" 该容量 ——
     * 填满一段有效容量所需的原始经验 = effRoom / mult (例: 填 [2000,2800) 这 800 有效经验需 800/0.4 = 2000 原始)。
     * 故每段先按 effRoom/mult 算出可填的原始量, 取 min(剩余原始, 该原始量), 折算有效 = used*mult, 有效指针前移
     * used*mult, 逐段直到原始耗尽或进入末档 x0.02 涓流。
     *
     * 拆分不变性 (修复 Critical 漏洞): 本函数是 "当前有效指针 currentDailyXp" 的确定性函数, 不含任何与切分
     * 粒度相关的取整。故 "一笔 rawXp 横跨多段" 与 "拆成多笔小额逐笔入账 (每笔把上一笔产出的有效经验累进
     * currentDailyXp 再调本函数)" 折算总和完全一致, 杜绝把大额收益拆成小批系统性多刷有效经验。
     *
     * 小额不归零 (修复 Critical 漏洞): 返回不取整的 double; 由 {@link JobProgress} 以 double 累计、仅在读出时
     * floor, 使 x0.4 档单笔 0.8 有效经验被进位累加而非逐笔 floor 吞光。
     *
     * 唯一裁决标准: 农夫 spec FarmingXP_Mod_DesignSpec.md:79-85 "该段累计需要的原始经验" / "每 1 有效经验需
     * 200 原始" 同源确证本模型 (分段边界在有效经验轴, 非原始经验轴)。
     *
     * @param currentDailyXp 当日已结算的有效经验 (>=0; 允许小数, 跨笔累进的精确指针)
     * @param rawXp          本次原始经验 (>=0)
     * @return 本次折算后的有效经验增量 (>=0, 不取整)
     */
    public static double applyDailyDecayExact(double currentDailyXp, long rawXp) {
        if (currentDailyXp < 0.0D || rawXp < 0L) {
            throw new IllegalArgumentException(
                    "currentDailyXp/rawXp must be >= 0, got " + currentDailyXp + "/" + rawXp);
        }
        if (rawXp == 0L) {
            return 0.0D;
        }

        // effPos = 当日有效经验指针 (随折算前移); remaining = 尚未折算的原始经验。全程 double, 不逐段取整。
        double effPos = currentDailyXp;
        double remaining = rawXp;
        double effective = 0.0D;

        long[] bounds = {DECAY_T1, DECAY_T2, DECAY_T3, DECAY_T4};
        double[] mults = {MULT_1, MULT_2, MULT_3, MULT_4};

        for (int seg = 0; seg < bounds.length && remaining > 0.0D; seg++) {
            double segEnd = bounds[seg];
            if (effPos >= segEnd) {
                continue; // 有效指针已越过本段, 本段无容量, 跳过。
            }
            double effRoom = segEnd - effPos;
            double rawToFillSegment = effRoom / mults[seg];
            double rawUsed = Math.min(remaining, rawToFillSegment);
            double effGained = rawUsed * mults[seg];
            effective += effGained;
            effPos += effGained;
            remaining -= rawUsed;
        }

        // [T4, +inf) 末档 x0.02: 前四段有效容量填满后剩余的原始经验全部走末档涓流系数。
        if (remaining > 0.0D) {
            effective += remaining * MULT_5;
        }

        return effective;
    }

    /**
     * {@link #applyDailyDecayExact} 的 floor 版便捷入口 (返回整数有效经验): 直接断言/文档口径用。
     * 业务入账走 {@link JobProgress#grantXp} -> {@link #applyDailyDecayExact} 以保留小数, 不经本方法。
     *
     * @param currentDailyXp 当日已结算的有效经验 (>=0)
     * @param rawXp          本次原始经验 (>=0)
     * @return 本次折算后的有效经验增量 floor 到整数 (>=0)
     */
    public static long applyDailyDecay(long currentDailyXp, long rawXp) {
        return (long) Math.floor(applyDailyDecayExact(currentDailyXp, rawXp));
    }
}
