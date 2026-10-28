package com.miningdim.job.farmer.granary;

/**
 * 启用裁决 (Spoilage_Granary_DesignSpec「容量怎么算」)。
 *
 * 可支撑囤数 = min(室内 ÷ 9, 气窗 × 4, 冷源 × 2) 三者取最小 (冷源只在冷库与官仓算), 并且不超过
 * 单座粮仓的技术上限 128 个。囤比可支撑的多时**按入册先后启用**, 最后入册的那几个是「待启用」——
 * 只能取不能存, 里面的东西照常腐烂; 部件被拆撑不住时, 原来启用、现在排不上的那几个变成「停用」。
 *
 * 纯函数: 只按「总数 + 可支撑数」决定启用几个, 谁先谁后由调用方按入册序号排好再传进来。
 */
public final class GranaryEnablement {

    /** 单座粮仓最多启用多少个储粮囤 (技术上限, 防卡服)。 */
    public static final int MAX_ACTIVE_BINS = 128;

    /**
     * 一次启用裁决: 前面 enabled 个启用, 后面 unsupported 个排不上。
     *
     * 排不上的分两种 (待启用 / 停用), 由 {@link GranarySavedData#applyEnablement} 按「以前启用过没有」分流 ——
     * 这个纯函数只管数, 不管历史。
     */
    public record Decision(int enabled, int unsupported) {

        public boolean isActive(int index) {
            return index < enabled;
        }
    }

    private GranaryEnablement() {
    }

    /**
     * @param totalBins     这座粮仓里入册的囤数 (按入册顺序, 从早到晚)
     * @param supportedBins 部件能支撑几个 ({@link GranaryStructureScanner.ScanResult#supportedBins})
     */
    public static Decision decide(int totalBins, int supportedBins) {
        if (totalBins < 0 || supportedBins < 0) {
            throw new IllegalArgumentException("bin counts must be >= 0, got "
                    + totalBins + " / " + supportedBins);
        }
        int enabled = Math.min(Math.min(totalBins, supportedBins), MAX_ACTIVE_BINS);
        return new Decision(enabled, totalBins - enabled);
    }
}
