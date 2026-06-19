package com.miningdim.job.agent;

/**
 * 特勤干员五大技能支线 × 1-10 级总表 (SpecialAgent_Job_DesignSpec 第四章, 唯一权威拷贝)。
 *
 * 结构 = 固定每级解锁 (非点数分配), 每升一级解锁第四章总表对应行, 无空级。本类把第四章总表的全部数值
 * 收编为唯一拷贝, 消除 spec 漂移 (同框架 {@link com.miningdim.job.JobXpCurve} 自述目的): 五支线的探测/封印/
 * 加强奖励/权限/伤害加成查表全走本类纯函数, GameTest 直断言, 删表必挂。
 *
 * 五支线有效阶数 (第四章末段):
 *  - 探测 10 阶 (扫描深度由 {@link AgentScanTier} 解密, 范围 + 脉冲 CD 在本表);
 *  - 封印 8 阶 (L3 起; 可封星级/类别门/窗口时长/CD/槽位在本表, 选词条 + 不叠加裁决在 {@link SealPlan}/{@link SealRegistry});
 *  - 加强奖励 10 阶 (倍率在本表, 按初始星级折算每击杀额外信用点在 {@link AgentEnhancedReward});
 *  - 日常周常权限 (日/周悬赏槽位 + 可接星级 + 世界 BOSS 悬赏门, 在本表);
 *  - 伤害加成 10 阶 (vs 精英百分比, 在本表)。
 *
 * 数值口径锚定 spec 第四章总表。绝对数值 (XP/信用点/青辉石底值) 不在本表 —— 升级曲线走 JobXpCurve,
 * 奖励底值走 economy config; 本表只承载第四章总表的"结构与曲线断点" (探测范围/脉冲 CD/封印窗口+CD/可封星级/
 * 加强倍率/悬赏槽位/可接星级/世界 BOSS 门/伤害加成%), 这些是 DECIDED 的结构数值非 PENDING 的绝对量。
 *
 * 全静态纯函数 + 常量, 无世界引用, dev GameTest 触达安全。等级一律先经 {@link #clampLevel} 夹到 [1,10]
 * (防越界查表)。
 */
public final class AgentSkillTable {

    private AgentSkillTable() {
    }

    /** 干员等级合法区间 (第一章: 1-10, 与精英星级同尺度)。 */
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 10;

    /** 封印支线起始等级 (第四章: L3 起可封被动词条)。 */
    public static final int SEAL_UNLOCK_LEVEL = 3;

    /** 机制/核心类词条可封起始等级 (第四章 L8: 命定之死/小男孩/天雷仅 L8+ 可短暂封印)。 */
    public static final int MECHANIC_SEAL_UNLOCK_LEVEL = 8;

    /** 周常悬赏 (含青辉石) 解锁等级 (第四章 L4: 周常 + 青辉石解锁)。 */
    public static final int WEEKLY_BOUNTY_UNLOCK_LEVEL = 4;

    /** 世界 BOSS 悬赏开放等级 (第四章 L8: 世界 BOSS 悬赏开)。 */
    public static final int WORLD_BOSS_BOUNTY_UNLOCK_LEVEL = 8;

    /** 第二封印槽解锁等级 (第四章 L9: 2 槽; 仅高星用得上, 8★+ 精英才占 2 槽)。 */
    public static final int SECOND_SEAL_SLOT_UNLOCK_LEVEL = 9;

    // ============================================================
    // 探测支线: 扫描范围 (格) + 脉冲 CD (tick); 扫描深度的字段解密在 AgentScanTier
    // 第四章范围列: 64/96/128/160/200/256/320/384/448/跨区块
    // ============================================================

    /** 跨区块标记 (第四章 L10 范围 = 跨区块, 非定值格数): 范围查表返回此值表示不按格数限制 (集成层按区块判)。 */
    public static final int SCAN_RANGE_CROSS_CHUNK = -1;

    private static final int[] SCAN_RANGE_BLOCKS = {
            64,   // L1
            96,   // L2
            128,  // L3
            160,  // L4
            200,  // L5
            256,  // L6
            320,  // L7
            384,  // L8
            448,  // L9
            SCAN_RANGE_CROSS_CHUNK // L10 跨区块
    };

    /** 探测脉冲 CD 上界 (第五章: 60s, 防全图刷新) / 下界 (L10: 30s)。线性每级缩 ~3.33s。 */
    private static final int SCAN_PULSE_CD_MAX_SECONDS = 60;
    private static final int SCAN_PULSE_CD_MIN_SECONDS = 30;

    // ============================================================
    // 封印支线 (L3 起 8 阶): 被动窗口 8s->12s / 机制窗口 3s->5s / 被动 CD 30s->18s / 机制 CD 仅 L10=45s
    // 第四章封印列: L3=8s/CD30 · L4=9s · L5=9s/CD26 · L6=10s/CD24 · L7=11s/CD22 · L8=被动11s/CD20+机制3s ·
    //               L9=机制4s · L10=机制5s/CD(被动18/机制45)
    // ============================================================

    /** 被动封印窗口时长 (tick); L<3 = 0 (不可封)。第四章被动窗口列 8/9/9/10/11/11/11/12 (L3..L10)。 */
    private static final int[] PASSIVE_SEAL_WINDOW_SECONDS = {
            0,  // L1
            0,  // L2
            8,  // L3
            9,  // L4
            9,  // L5
            10, // L6
            11, // L7
            11, // L8
            11, // L9
            12  // L10
    };

    /** 被动封印 CD (tick); L<3 = 0。第四章 CD 列 30/30/26/24/22/20/20/18 (L3..L10)。 */
    private static final int[] PASSIVE_SEAL_CD_SECONDS = {
            0,  // L1
            0,  // L2
            30, // L3
            30, // L4 (第四章未列 CD, 沿用 L3 30s 直到 L5 缩)
            26, // L5
            24, // L6
            22, // L7
            20, // L8
            20, // L9 (第四章 2 槽行未列 CD, 沿用 L8 20s 直到 L10 缩)
            18  // L10
    };

    /** 机制封印窗口时长 (tick); L<8 = 0 (机制类仅 L8+ 可封)。第四章机制窗口 3/4/5 (L8/L9/L10)。 */
    private static final int[] MECHANIC_SEAL_WINDOW_SECONDS = {
            0, 0, 0, 0, 0, 0, 0, // L1-L7
            3, // L8
            4, // L9
            5  // L10
    };

    /** 机制封印 CD (tick); 第四章仅 L10 给机制 CD=45s (L8/L9 机制 CD 未单列, 沿用被动 CD 同档)。 */
    private static final int[] MECHANIC_SEAL_CD_SECONDS = {
            0, 0, 0, 0, 0, 0, 0, // L1-L7
            20, // L8 (沿用被动 CD 20s)
            20, // L9 (沿用被动 CD 20s)
            45  // L10 (第四章: 机制 CD 45s)
    };

    // ============================================================
    // 加强奖励支线 (10 阶): 倍率 ×1.0 -> ×3.0
    // 第四章列 1.0/1.25/1.5/1.75/2.0/2.25/2.5/2.7/2.85/3.0 (L7->L8 起斜率放缓)
    // ============================================================

    private static final double[] ENHANCED_REWARD_MULTIPLIER = {
            1.0D,  // L1
            1.25D, // L2
            1.5D,  // L3
            1.75D, // L4
            2.0D,  // L5
            2.25D, // L6
            2.5D,  // L7
            2.7D,  // L8
            2.85D, // L9
            3.0D   // L10
    };

    // ============================================================
    // 日常周常权限支线: 日槽位 1->5 / 周槽位 0->3 (周常 L4 解锁) / 可接星级 ≤L★
    // 第四章日列 1/1/2/2/3/3/3/4/4/5 · 周列 0/0/0/1/0/0/2/2/2/3
    // ============================================================

    private static final int[] DAILY_BOUNTY_SLOTS = {
            1, // L1
            1, // L2
            2, // L3
            2, // L4
            3, // L5
            3, // L6
            3, // L7
            4, // L8
            4, // L9
            5  // L10
    };

    private static final int[] WEEKLY_BOUNTY_SLOTS = {
            0, // L1
            0, // L2
            0, // L3
            1, // L4 (周常 + 青辉石解锁)
            1, // L5 (第四章 L5/L6 未抬周槽, 沿用 L4 的 1)
            1, // L6
            2, // L7
            2, // L8
            2, // L9
            3  // L10
    };

    // ============================================================
    // 伤害加成支线 (10 阶): vs 精英 +5% -> +15% (线性每级 +1%, 末级 L10 = +15% 即 +5%+10 级跳)
    // 第四章列 5/6/7/8/9/10/11/12/13/15 (% — L10 多跳 1% 到 15%)
    // ============================================================

    private static final int[] DAMAGE_BONUS_PERCENT = {
            5,  // L1
            6,  // L2
            7,  // L3
            8,  // L4
            9,  // L5
            10, // L6
            11, // L7
            12, // L8
            13, // L9
            15  // L10 (末级多跳 1%: +13% -> +15%)
    };

    /** 把任意等级值夹到合法区间 (防越界查表; 第一章 1-10)。 */
    public static int clampLevel(int level) {
        if (level < MIN_LEVEL) {
            return MIN_LEVEL;
        }
        return Math.min(level, MAX_LEVEL);
    }

    private static int idx(int level) {
        return clampLevel(level) - 1;
    }

    // ---- 探测 ----

    /**
     * 某等级扫描范围 (格); L10 返回 {@link #SCAN_RANGE_CROSS_CHUNK} (-1) 表示跨区块不按格数限制。
     * 第四章范围列 64/96/128/160/200/256/320/384/448/跨区块。
     */
    public static int scanRangeBlocks(int level) {
        return SCAN_RANGE_BLOCKS[idx(level)];
    }

    /** 某等级扫描脉冲 CD (秒); 60s->30s 线性随级缩 (第五章主动脉冲长 CD 防全图刷新)。 */
    public static int scanPulseCdSeconds(int level) {
        int lv = clampLevel(level);
        // L1=60s, L10=30s, 线性插值: 60 - (lv-1)*(60-30)/(10-1)。
        int span = SCAN_PULSE_CD_MAX_SECONDS - SCAN_PULSE_CD_MIN_SECONDS; // 30
        return SCAN_PULSE_CD_MAX_SECONDS - (lv - MIN_LEVEL) * span / (MAX_LEVEL - MIN_LEVEL);
    }

    // ---- 封印 ----

    /** 某等级被动封印是否解锁 (L3 起)。 */
    public static boolean isPassiveSealUnlocked(int level) {
        return clampLevel(level) >= SEAL_UNLOCK_LEVEL;
    }

    /** 某等级机制/核心类封印是否解锁 (L8 起)。 */
    public static boolean isMechanicSealUnlocked(int level) {
        return clampLevel(level) >= MECHANIC_SEAL_UNLOCK_LEVEL;
    }

    /**
     * 某等级 + 词条类别的封印窗口时长 (秒); 未解锁返回 0。被动 8s->12s / 机制 3s->5s (第四章)。
     */
    public static int sealWindowSeconds(int level, SealCategory category) {
        int i = idx(level);
        return switch (category) {
            case PASSIVE -> PASSIVE_SEAL_WINDOW_SECONDS[i];
            case MECHANIC -> MECHANIC_SEAL_WINDOW_SECONDS[i];
        };
    }

    /**
     * 某等级 + 词条类别的封印 CD (秒); 未解锁返回 0。被动 30s->18s / 机制仅 L10=45s (第四章)。
     */
    public static int sealCooldownSeconds(int level, SealCategory category) {
        int i = idx(level);
        return switch (category) {
            case PASSIVE -> PASSIVE_SEAL_CD_SECONDS[i];
            case MECHANIC -> MECHANIC_SEAL_CD_SECONDS[i];
        };
    }

    /**
     * 某等级可封的最高精英星级 (第四章: 可封星级随级抬到 10★, 与等级同尺度即 maxSealableStar(L)=L)。
     * 未解锁封印 (L<3) 返回 0。
     */
    public static int maxSealableStar(int level) {
        int lv = clampLevel(level);
        if (lv < SEAL_UNLOCK_LEVEL) {
            return 0;
        }
        return lv; // 第四章: 可封星级 = 等级 (L3 可封≤3★ ... L10 可封≤10★)。
    }

    /**
     * 某等级对某精英的封印槽数 (第四章: 每精英 1 槽; 8★+ 为 2 槽, 但第二槽需干员 L9+ 才解锁)。
     * 槽数是"该精英可被压几份封印"的容量, 由 min(精英星级档, 干员解锁的槽位) 决定。
     *
     * @param level 干员等级
     * @param star  精英初始星级 (1-10)
     * @return 该精英的封印槽容量 (0 = 干员未解锁封印; 1 = 单槽; 2 = 双槽)
     */
    public static int sealSlots(int level, int star) {
        int lv = clampLevel(level);
        if (lv < SEAL_UNLOCK_LEVEL) {
            return 0;
        }
        // 8★+ 精英本身允许 2 槽, 但需干员 L9+ 才解锁第二槽 (第四章 L9: 2 槽)。
        if (star >= 8 && lv >= SECOND_SEAL_SLOT_UNLOCK_LEVEL) {
            return 2;
        }
        return 1;
    }

    // ---- 加强奖励 ----

    /** 某等级加强奖励倍率 (×1.0 -> ×3.0; 第四章)。 */
    public static double enhancedRewardMultiplier(int level) {
        return ENHANCED_REWARD_MULTIPLIER[idx(level)];
    }

    // ---- 日常周常权限 ----

    /** 某等级日常悬赏槽位 (1->5; 第四章)。 */
    public static int dailyBountySlots(int level) {
        return DAILY_BOUNTY_SLOTS[idx(level)];
    }

    /** 某等级周常悬赏槽位 (0->3, 周常 L4 才 >0; 第四章)。 */
    public static int weeklyBountySlots(int level) {
        return WEEKLY_BOUNTY_SLOTS[idx(level)];
    }

    /** 某等级是否解锁周常悬赏 (L4 起, 等价 weeklyBountySlots>0)。 */
    public static boolean isWeeklyBountyUnlocked(int level) {
        return clampLevel(level) >= WEEKLY_BOUNTY_UNLOCK_LEVEL;
    }

    /** 某等级可接悬赏的最高目标星级 (第四章: 可接 ≤L★, 即可接星级 = 等级)。 */
    public static int maxBountyStar(int level) {
        return clampLevel(level);
    }

    /** 某等级是否开放世界 BOSS 悬赏 (L8 起; 第四章 L8 世界 BOSS 悬赏开)。 */
    public static boolean isWorldBossBountyUnlocked(int level) {
        return clampLevel(level) >= WORLD_BOSS_BOUNTY_UNLOCK_LEVEL;
    }

    // ---- 伤害加成 ----

    /** 某等级对精英/冠军的伤害加成百分比 (整数 %; +5 -> +15, 第四章)。 */
    public static int damageBonusPercent(int level) {
        return DAMAGE_BONUS_PERCENT[idx(level)];
    }

    /** 某等级对精英/冠军的伤害加成系数 (小数; 0.05 -> 0.15, 供 LivingHurtEvent 单点放大乘子)。 */
    public static double damageBonusFraction(int level) {
        return damageBonusPercent(level) / 100.0D;
    }
}
