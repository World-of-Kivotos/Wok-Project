package com.miningdim.job.farmer.granary;

/**
 * 粮仓的稳定常量 (Spoilage_Granary_DesignSpec「粮仓：建筑与分档」「物品数据与存档」)。
 *
 * 与 {@link com.miningdim.job.farmer.FarmerConstants} 分开: 粮仓是农夫模块下的独立子包, 这些键一旦发布
 * 就不能再改 (改了会让已经放下的储粮囤失去归属, 等于废掉存档里的建造成果)。
 */
public final class GranaryConstants {

    /** 粮仓归属与状态的 SavedData 名。 */
    public static final String DATA_NAME = "miningdim_farmer_granary";

    /** 储粮囤容量 (与木桶一致)。 */
    public static final int BIN_SLOTS = 27;

    /** 每人最多几座账台 (够「自家一座 + 学院或社团一座」)。 */
    public static final int MAX_CENTERS_PER_PLAYER = 2;

    private GranaryConstants() {
    }
}
