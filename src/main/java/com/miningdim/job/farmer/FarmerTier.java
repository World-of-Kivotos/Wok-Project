package com.miningdim.job.farmer;

/**
 * 五档耕地枚举 (FarmingXP_Mod_DesignSpec 表B 主方案, 行 61-67)。每档携带解锁等级、成长间隔、每次收获产量、
 * 每小时收获次数, 以及反查吞吐的派生量。
 *
 * 表B 主方案: 单作物经验固定 = {@link FarmerConstants#SINGLE_CROP_XP} (2), 靠成长速度 + 产量拉开档位。
 * 成长间隔 (分钟) 是 "一株从种下到成熟" 的目标期望时长; 折算成 7 个成长阶段的每阶段期望 tick 后, 由
 * {@link FarmerCropBlock} 在 randomTick 内按概率推进, 使期望成熟时间命中本档间隔 (不沿用原版光照成长公式)。
 *
 * yieldPerHarvest 是一次成熟破坏掉落的小麦株数 (= 结算的作物株数, 每株 SINGLE_CROP_XP 经验);
 * harvestsPerHour 仅作 spec 吞吐自洽校验用 (= 60 / 成长间隔分钟), 不参与运行期成长 (运行期由 growthIntervalTicks 决定)。
 */
public enum FarmerTier {

    /** 低级: 解锁 L1, 10min/株, 产 2, 吞吐 24 经验/块/时 (1.00x)。 */
    LOW("low", 1, 10, 2),

    /** 中级: 解锁 L3, 8min/株, 产 3, 吞吐 45 (1.9x)。 */
    MEDIUM("medium", 3, 8, 3),

    /** 高级: 解锁 L5, 6min/株, 产 4, 吞吐 80 (3.3x)。 */
    HIGH("high", 5, 6, 4),

    /** 极品: 解锁 L7, 5min/株, 产 5, 吞吐 120 (5.0x)。 */
    PREMIUM("premium", 7, 5, 5),

    /** 超凡: 解锁 L9, 4min/株, 产 6, 吞吐 180 (7.5x)。 */
    SUPREME("supreme", 9, 4, 6);

    private final String id;
    private final int unlockLevel;
    private final int growthIntervalMinutes;
    private final int yieldPerHarvest;

    FarmerTier(String id, int unlockLevel, int growthIntervalMinutes, int yieldPerHarvest) {
        this.id = id;
        this.unlockLevel = unlockLevel;
        this.growthIntervalMinutes = growthIntervalMinutes;
        this.yieldPerHarvest = yieldPerHarvest;
    }

    /** 稳定小写 id (注册名 / 资源路径 / lang key)。 */
    public String id() {
        return id;
    }

    /** 解锁本档所需玩家农夫等级 (表A 第4列: 每两级解锁一档)。 */
    public int unlockLevel() {
        return unlockLevel;
    }

    /** 一株从种下到成熟的目标期望时长 (分钟; 表B 成长间隔)。 */
    public int growthIntervalMinutes() {
        return growthIntervalMinutes;
    }

    /**
     * 一株从种下到成熟的目标期望 tick 数 (= 间隔分钟 × 60 × 20)。
     * {@link FarmerCropBlock#randomTick} 据此把单次随机刻推进概率定为命中此期望成熟时间。
     */
    public long growthIntervalTicks() {
        return (long) growthIntervalMinutes * 60L * FarmerConstants.TICKS_PER_SECOND;
    }

    /** 一次成熟破坏掉落的小麦株数 (表B 每次产量; = 经验结算的株数)。 */
    public int yieldPerHarvest() {
        return yieldPerHarvest;
    }

    /** 玩家当前等级是否已解锁本档耕地 (放置门控: 表A 等级 < unlockLevel 拒放更高档)。 */
    public boolean isUnlockedAt(int playerLevel) {
        return playerLevel >= unlockLevel;
    }

    /** 该玩家实际吃到的每次产量: 未解锁本档退化为基准值, 与 FarmerHarvestLootModifier 同一裁决。 */
    public int yieldFor(int playerLevel) {
        return isUnlockedAt(playerLevel) ? yieldPerHarvest : FarmerConstants.LOCKED_TIER_YIELD;
    }
}
