package com.miningdim.job.farmer;

/**
 * 农夫职业全部数值常量唯一来源 (FarmingXP_Mod_DesignSpec.md 表A/B/C; 仿 economy.EconomyConstants)。
 *
 * 数值对齐裁决 (FarmingXP spec 顶部 SUPERSEDED + JobFramework_Shared_Foundation 第四章):
 *  - 等级经验曲线 (表A) 与每日有效经验软上限衰减 (表C) 一律以 {@link com.miningdim.job.JobXpCurve} 为唯一真源
 *    (2000 系衰减, 总 61,900)。本类不再复制等级/衰减常量, 农夫经验入账一律走
 *    {@link com.miningdim.job.IJobService#grantXp} —— 职业侧只发原始经验, 衰减/翻日/升级由框架裁决。
 *    这消解了 FarmingXP 表C (T=1500 四档) 与共享地基 2000 系表的 Critical 冲突 (取共享地基为准, 见 notes)。
 *  - 本类只承载农夫专有的、框架不覆盖的数值: 表A 各级方块上限、表B 五档耕地参数 (成长间隔/产量/单作物经验)、
 *    经济收购曲线参数 (表C 经验衰减是经验侧, 与经济收购侧两条独立曲线, 见第八节)。
 *
 * 表B 主方案 (DECIDED): 单作物经验固定 = 2, 靠成长速度 + 产量拉开档位; 吞吐 = 收获/时 × 产量/次 × 单作物经验。
 * 不得凭记忆改写, 改动须回 spec 表对照。
 */
public final class FarmerConstants {

    private FarmerConstants() {
    }

    /** 单株成熟作物破坏掉落结算的原始经验 (表B 主方案: 固定 2, 全档一致)。 */
    public static final int SINGLE_CROP_XP = 2;

    /**
     * 玩家等级未解锁该档耕地时退化到的基准产量 (= 不放大)。不是新拍的平衡数值, 而是
     * {@link FarmerHarvestLootModifier} 已有裁决 ("未解锁 -> 原样返回 loot", 即 1 倍) 的显式化 (F026)。
     * mod 小麦的 loot table 只补种种子、小麦全由事件层单发, 故其"不放大"的基准就是每株 1。
     */
    public static final int LOCKED_TIER_YIELD = 1;

    /**
     * 各等级 (L1-L10) mod 耕地放置上限 (表A 第5列, 索引 = level - 1)。
     * 校验: 9/12/16/20/25/30/36/42/48/64 (FarmingXP spec 表A 行 40-49)。
     * 超限拒放是反扩建硬封顶 (设计目标 2)。
     */
    public static final int[] FARMLAND_CAP_PER_LEVEL = {
            9,   // L1
            12,  // L2
            16,  // L3
            20,  // L4
            25,  // L5
            30,  // L6
            36,  // L7
            42,  // L8
            48,  // L9
            64   // L10 (毕业, 此时手里仍是 48 块超凡地练满, 64 为封顶上界)
    };

    /**
     * 一秒的游戏 tick 数 (原版固定 20)。表B 成长间隔以分钟给, 折算成长所需的预期 tick 数时用。
     * 与 economy.EconomyConstants.TICKS_PER_SECOND 同值, 但农夫包不依赖经济实现常量 (模块化铁律), 独立声明。
     */
    public static final int TICKS_PER_SECOND = 20;

    // ============================================================
    // 经济侧: NPC 小麦动态收购价 (第八节方案4) — 与经验衰减独立的第二条曲线
    // ============================================================
    // spec 第八节明示两条曲线独立持久化; 收购曲线复用 economy.AbuseGuard.buyPrice 同构形态:
    //   price(n) = basePrice * max(floorRatio, decayBase^max(0, n - softCap))
    // 数值 (basePrice / softCap) 在 spec 中标 PENDING (经济文档 8.6 待校准), 此处给经实现期推演的可用默认值:
    //   - basePrice = 1 信用点/株 (经济文档定位农夫为基础 faucet, 单价低、靠量; 待经济文档校准, 见 foundationGaps)
    //   - softCap   = 2160 株/日 (= 单块超凡地 24h 满产, 表1; 作为 "正常单块全天产出" 的不衰减额度基准)
    // decayBase / floorRatio 与矿物收购同构 (0.97 / 0.25), 保证全服 faucet 衰减语言一致 (spec 第八节)。

    /** 小麦收购基础单价 (信用点/株, 未触软上限时)。PENDING 经济文档 8.6 校准 (见 foundationGaps)。 */
    public static final long WHEAT_BASE_PRICE = 1L;

    /** 小麦每日收购软上限 (株); 超过后单价按 decayBase 指数衰减至 floorRatio 地板。 */
    public static final int WHEAT_DAILY_SOFTCAP = 2160;

    /** 收购价递减底数 (与矿物收购 economy 0.97 同构)。 */
    public static final double WHEAT_DECAY_BASE = 0.97D;

    /** 收购价地板比例 (与矿物收购 economy 0.25 同构): 衰减不低于 basePrice 的 25%。 */
    public static final double WHEAT_PRICE_FLOOR_RATIO = 0.25D;

    /**
     * 卖菜信用点 faucet 的每日计数键 (第十一章决策 4: 引用全服唯一真源)。直接转引
     * {@link com.miningdim.economy.EconomyConstants#GLOBAL_DAILY_CREDIT_FAUCET_KEY}, 消灭农夫包内的字符串字面量副本:
     * 卖菜经 {@link com.miningdim.economy.IEconomyService#grantDaily} 入账时传此键, 与矿工卖矿 (settleOreSale 内部同键)
     * 共用同一 (playerId, faucetKey) 每日累计计数器, 各 faucet 并入同一衰减主闸天花板而非各算独立日上限。
     *
     * 跨包依赖说明 (第十一章决策 4 拍板"引用全局常量"): 本类历来避免 import 经济实现常量 (上方 TICKS_PER_SECOND 注释),
     * 但 {@link com.miningdim.economy.EconomyConstants} 是常量持有类 (非实现/状态), 且 {@link FarmerWheatSellService}
     * 已 import 经济门面类型, 该 job->economy 编译依赖在服务层本已存在; 转引常量令"全服统一 faucet 档值/键"只有一处真源,
     * 优于复制字面量再加"必须匹配"注释 (后者是漂移温床)。
     */
    public static final String WHEAT_SELL_FAUCET_KEY =
            com.miningdim.economy.EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY;

    /**
     * 卖菜入账传给 {@link com.miningdim.economy.IEconomyService#grantDaily} 的衰减主闸单档大小 (信用点; 第十一章决策 4)。
     * 直接转引 {@link com.miningdim.economy.EconomyConstants#GLOBAL_DAILY_CREDIT_FAUCET_TIER} (= 60000): 农夫卖菜与矿工卖矿
     * 撞同一档值即并入同一每人每日衰减主闸 (0.6/60000/1% 地板, 渐近 15 万), 共享同一天花板。此前农夫私有占位 2160L (株量纲
     * 误塞 CP 档) 已废, 改为全服唯一真源。
     *
     * 与 {@link #WHEAT_DAILY_SOFTCAP} 的量纲区分 (改动雷区): 本常量是 CP 档 (60000 信用点, 喂 grantDaily 第 4 参);
     * WHEAT_DAILY_SOFTCAP 是株档 (2160 株, 喂 FarmerWheatBuyback 收购曲线 softCap)。两条独立曲线, 量纲不同不可共用。
     */
    public static final long DAILY_CREDIT_FAUCET_CAP =
            com.miningdim.economy.EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER;
}
