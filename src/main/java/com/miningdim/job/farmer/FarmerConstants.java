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
     * 卖菜信用点 faucet 的每日计数键 (经济文档 8.5: 所有 faucet 须并入每人每日信用点软上限)。
     * 当前未被引用: 地基 {@link com.miningdim.economy.IEconomyService} 只有扣费侧 tryChargeDaily (含 dailyKey),
     * 无发放侧 grantDaily, 故卖菜暂走农夫私有 per-player 上限 ({@link #WHEAT_SELL_DAILY_CREDIT_CAP}) 而非全服
     * 统一 dailyKey 软上限。待地基补 grantDaily(player, currency, amount, dailyKey, dailyCap) 后, 卖菜改走它并
     * 复用与矿物收购同一 dailyKey 命名空间 (见 foundationGaps)。本键先行声明该命名空间, 接线即用。
     */
    public static final String WHEAT_SELL_DAILY_KEY = "farmer_wheat_sell";

    /**
     * 卖菜信用点每日上限 (信用点): basePrice × softCap 量级, 防单职业灌爆信用点。PENDING 经济校准。
     * 当前为农夫私有 per-player 上限 (非全服统一 faucet 软上限): 矿工卖矿与农夫卖菜各算独立日上限, 未共享同一
     * 每日信用点天花板。这与经济文档 8.5 "全服 faucet 并入同一软上限" 相悖, 根因是地基缺发放侧 grantDaily 每日
     * 计数 API (见 foundationGaps); 待补齐后并入全服统一上限再据 "满级日纯经济约 3 万株" 锚回算校准。
     */
    public static final long WHEAT_SELL_DAILY_CREDIT_CAP = 2160L;
}
