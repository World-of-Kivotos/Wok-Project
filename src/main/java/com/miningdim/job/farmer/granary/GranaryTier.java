package com.miningdim.job.farmer.granary;

import com.miningdim.job.farmer.granary.GranaryStructureScanner.ScanResult;

/**
 * 粮仓三个档位 (Spoilage_Granary_DesignSpec「档位管什么」)。
 *
 * 档位**不再给容量**, 只管「能存什么」和「有哪些功能」, 所以五档并成三档:
 * 粮棚 (L1, 除生鲜外的全部) / 冷库 (L5, 加收生鲜与海鲜) / 官仓 (L7, 再加公开捐粮与公开取用)。
 *
 * 档位在验收那一刻按**验收人的农夫等级**判定, 之后锁定: 验收人升级降级都不追溯 (规格「锁定后不追溯」)。
 */
public enum GranaryTier {

    /** 粮棚: 人人都是 L1, 自己点就成立。 */
    SHED("shed", 1, 18, false, false),

    /** 冷库: 能存生肉生鱼, 要冷源。 */
    COLD("cold", 5, 36, true, true),

    /** 官仓: 再加公开捐粮与公开取用。 */
    STATE("state", 7, 72, true, true);

    private final String id;
    private final int unlockLevel;
    private final int minInteriorCells;
    private final boolean needsColdSources;
    private final boolean acceptsFresh;

    GranaryTier(String id, int unlockLevel, int minInteriorCells,
                boolean needsColdSources, boolean acceptsFresh) {
        this.id = id;
        this.unlockLevel = unlockLevel;
        this.minInteriorCells = minInteriorCells;
        this.needsColdSources = needsColdSources;
        this.acceptsFresh = acceptsFresh;
    }

    public String id() {
        return id;
    }

    /** 验收这一档要的农夫等级 (服务器在点击那一刻读点击者的等级)。 */
    public int unlockLevel() {
        return unlockLevel;
    }

    /** 这一档至少要多少室内格 (还要叠加「每个入册的囤 9 格」)。 */
    public int minInteriorCells() {
        return minInteriorCells;
    }

    /** 冷库起要冷源。 */
    public boolean needsColdSources() {
        return needsColdSources;
    }

    /** 这一档收不收生鲜 (生肉、生鱼、肉馅、海鲜)。 */
    public boolean acceptsFresh() {
        return acceptsFresh;
    }

    /** 语言键。 */
    public String translationKey() {
        return "granary.tier." + id;
    }

    /** 下一档 (已到官仓返回自己)。 */
    public GranaryTier next() {
        return switch (this) {
            case SHED -> COLD;
            case COLD, STATE -> STATE;
        };
    }

    /** 至少要有几扇气窗 (每 4 个囤 1 扇, 至少 1 扇)。 */
    public static int requiredVents(ScanResult scan) {
        return Math.max(1, (int) Math.ceil(scan.bins() / (double) GranaryStructureScanner.BINS_PER_VENT));
    }
}
