package com.miningdim.freshness;

/**
 * 腐烂底座 (wok-freshness) 的稳定常量。
 *
 * 文档真源: {@code docs/Spoilage_Granary_DesignSpec.md}(14 项已按推荐拍板, 2026-10-15)。
 * 本类只放**跨文件共享且不得随实现漂移**的量: 物品数据根键与子键、存档名、停表口径。
 * 保质期本身是可热改的配置 ({@link FreshnessConfig}), 不在这里写死。
 */
public final class FreshnessConstants {

    /** 模块键 (module-registry.json)。 */
    public static final String MODULE_ID = "wok-freshness";

    /** 主世界 SavedData 名: 时钟 (lastSeenHour / offsetHours / 心跳)。 */
    public static final String CLOCK_DATA_NAME = "miningdim_freshness_clock";

    /**
     * 物品数据根键, 与 MiningChef / MiningBrewer / MiningFish 平级。
     * 所有写入走 {@link FreshnessData} 的同一个 writer: f = 0 不写, 数值一律 IntTag ——
     * 同一批货若因标签类型不同 (IntTag / LongTag) 就会叠不上, 是规格里点名的坑。
     */
    public static final String ROOT = "MiningFresh";

    /** 批次时刻 (UTC 分钟序号, 盖章时取整点 × 60)。 */
    public static final String KEY_BATCH = "h";

    /** 已停表小时 (int, > 0 才写)。 */
    public static final String KEY_STOPPED = "f";

    /** 开服迁移货标记 (永不和新货合并)。 */
    public static final String KEY_MIGRATED = "m";

    /** 村民货标记 (到手剩 6 小时)。 */
    public static final String KEY_VILLAGER = "v";

    /** 每件东西累计最多停表 21 天。 */
    public static final int STOP_CAP_HOURS = 504;

    /** 粮仓要连续存满 24 小时才开始停表。 */
    public static final int MIN_STORE_HOURS = 24;

    /** 村民卖出的食物到手时剩的小时数。 */
    public static final int VILLAGER_GOODS_HOURS = 6;

    private FreshnessConstants() {
    }
}
