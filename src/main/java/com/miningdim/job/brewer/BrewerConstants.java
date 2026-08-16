package com.miningdim.job.brewer;

/**
 * 酿酒师编译期不变量常量 (单一来源, 杜绝跨类各写一份漂移)。平衡数值 (F086) 已搬进 {@link BrewerConfig}
 * 的 ForgeConfigSpec, 本类只留三类【不能/不该】进 config 的常量:
 *
 *  - {@link #BREW_DURATION_TICKS}: 客户端 {@code BrewingStationScreen} 直接读它算进度条; 若搬进 SERVER
 *    端 spec, 联机客户端调用 get() 会抛 ISE (SERVER 配置只在逻辑服务端加载, 客户端进程读不到)。
 *  - {@link #MAX_LAYERS_PER_TYPE}: 与 {@link BrewPermanentBuffs#brandyHasteAmplifier} 的 5/3 硬分档、
 *    以及 WebUI 契约字段 {@code maxLayersPerType} 绑死, 独立可调会让三处漂移不一致。
 *  - {@link #CELLAR_WINE_SLOTS}: 决定 {@link com.miningdim.job.brewer.cellar.WineCellarBlockEntity} 的
 *    容器结构与存档布局, 运行期改动会与已存档的物品栏大小失配。
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

    // ---- 酿造 (酿酒台, 阶段 3) ----

    /** 一次酿造耗时 (tick); 7 天周期的前半段 (后半段为酒窖箱陈酿)。客户端进度条直读, 不可搬进 SERVER config。 */
    public static final int BREW_DURATION_TICKS = 2_400;
    /** 一次酿造产出的基酒瓶数。 */
    public static final int BREW_OUTPUT_COUNT = 1;

    // ---- 干小麦燃料门控 + 陈酿/变质 (酒窖箱, 阶段 4) ----

    /** 酒窖箱的陈酿酒槽数 (干小麦燃料槽另算)。12 瓶高年份满产 ≈ 满级 L10 农夫供给 (对标农夫产量, 见设计文档)。 */
    public static final int CELLAR_WINE_SLOTS = 12;

    /**
     * 燃料槽单槽容量上限 (F027 二段修复: 复核确认"64 槽 vs 设计吞吐的量级差"须一并修, 建议原文二选一
     * "提高 slot limit 或开放受控的燃料输入通道"; 本项目选前者, 后者 (漏斗/机器自动注入燃料) 与
     * {@link com.miningdim.job.brewer.cellar.WineCellarBlockEntity} 类 javadoc 明载的反挂机决策冲突,
     * 不擅自推翻)。
     *
     * 数值系派生量, 非新拍的平衡数值: 取 {@link com.miningdim.job.brewer.cellar.CellarSettle} 结算步进的
     * 最大粒度 (1 现实天, 即
     * {@link #MILLIS_PER_VINTAGE_YEAR}) 下, 满窖 ({@link #CELLAR_WINE_SLOTS}=12 瓶) 全部停在闪耀永久层
     * 最低门槛 ({@link #VINTAGE_LAYER_T1}=10, 阶段五主线可达的最低目标年份) 时, 一天的满额应耗:
     * {@code 12 × (BrewerConfig.DRIED_WHEAT_PER_BOTTLE_YEAR + BrewerConfig.FUEL_QUAD_COEF × 10²)
     * = 12 × (16 + 5×100) = 12 × 516 = 6192} (系数取自 BrewerConfig 当前默认值)。
     *
     * 意义: fullDemand 随 elapsed 线性缩放 (CellarSettle.settle 每步 stepYears ∝ stepMillis), 故只要单次
     * 离线/区块卸载间隔不超过一个结算步 (1 天), 满槽一次即可让 budget≥fullDemand 恒成立、agedFraction=1、
     * 零衰退。继续陈酿到 v18/v25 后应耗随年份平方递增会重新吃紧, 这是设计文档"老酒烧钱凶, 自然经济封顶"
     * 故意保留的经济压力 (软上限的经济一面), 不在本次修复范围内。
     *
     * 生效前提 (缺一不可, 因 Forge {@code ItemStackHandler.getStackLimit} 取两者较小值):
     * {@link com.miningdim.job.brewer.BrewerItems#DRIED_WHEAT} 的 {@code stacksTo} 必须同步设为本值,
     * 且酒窖箱燃料槽的 {@code getSlotLimit} 必须覆写为本值 (均已配套修改)。
     *
     * 注: 此值在 Item 注册期 (static 初始化) 求值, 早于 ForgeConfigSpec 加载, 不能改读 BrewerConfig 的
     * 实时配置 —— 若运营方调整 DRIED_WHEAT_PER_BOTTLE_YEAR / FUEL_QUAD_COEF / VINTAGE_LAYER_T1 的默认值,
     * 需手动同步重算本常量 (WineCellarGameTests#fuelSlotCapacityCoversFullDayAtMainlineThreshold 会在
     * 二者失配时挂测, 充当漂移警报)。
     */
    public static final int FUEL_SLOT_CAPACITY = 6192;

    // ---- 闪耀永久增益 (一条命 = 永久层数系统, 阶段 5(iii)(iv)) ----

    /** 每类型永久层数封顶 (喝闪耀酒按年份加层, 死亡清零; 满层=该酒类永久特殊的满值)。与白兰地急迫 5/3 硬分档
     *  及 WebUI 契约 maxLayersPerType 绑死, 不可单独进 config。 */
    public static final int MAX_LAYERS_PER_TYPE = 5;

    /** 闪耀酒"年份 -> 本次加层"阈值 (含端点): [T1,T2) +1 / [T2,T3) +2 / >=T3 +3; <T1 +0 (嫩闪耀酒不固化层)。 */
    public static final double VINTAGE_LAYER_T1 = 10.0D;
    public static final double VINTAGE_LAYER_T2 = 18.0D;
    public static final double VINTAGE_LAYER_T3 = 25.0D;
}
