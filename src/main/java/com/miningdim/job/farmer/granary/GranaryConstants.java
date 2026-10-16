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

    // ------------------------------------------------------------
    // 存档键 (Spoilage_Granary_DesignSpec「物品数据与存档」)
    //
    // 放在这里而不是各写一份: 迁移脚本、存档体检与测试都要按同一套键读旧档, 键名是**对外契约**,
    // 发布之后只能加不能改。
    // ------------------------------------------------------------

    /** 存档头版本。 */
    public static final String KEY_VERSION = "Version";
    public static final String KEY_BINS = "Bins";
    public static final String KEY_CENTERS = "Centers";
    public static final String KEY_DIMENSION = "Dim";
    public static final String KEY_POS = "Pos";
    public static final String KEY_OWNER = "Owner";
    public static final String KEY_SEQ = "Seq";
    public static final String KEY_GRANARIES = "Granaries";
    public static final String KEY_BIN_GRANARY = "BinGranary";
    public static final String KEY_BIN_STATES = "BinStates";
    public static final String KEY_STATE = "State";
    public static final String KEY_GID = "Gid";
    public static final String KEY_NEXT_GID = "NextGid";
    public static final String KEY_NEXT_SEQ = "NextSeq";
    public static final String KEY_HOUR = "Hour";
    public static final String KEY_AT = "At";
    public static final String KEY_GRACE_LOGINS = "GraceLogins";

    /** 粮仓登记项 (GranaryRecord) 的键。 */
    public static final String KEY_ID = "Id";
    public static final String KEY_TIER = "Tier";
    public static final String KEY_ACCEPTED_BY = "AcceptedBy";
    public static final String KEY_ACCEPTED_AT = "AcceptedAt";
    public static final String KEY_STATUS = "Status";
    public static final String KEY_DAMAGED_AT = "DamagedAt";
    public static final String KEY_MEMBERS = "Members";
    public static final String KEY_MEMBER = "Member";

    /** 待领粮单 (GranaryPending) 的键。 */
    public static final String KEY_PENDING = "Pending";
    public static final String KEY_PLAYER = "Player";
    public static final String KEY_REASON = "Reason";
    public static final String KEY_ITEMS = "Items";
    public static final String KEY_CREDITED = "Credited";

    /** 存档版本号。 */
    public static final int SAVE_VERSION = 1;

    private GranaryConstants() {
    }
}
