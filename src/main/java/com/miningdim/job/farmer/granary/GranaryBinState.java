package com.miningdim.job.farmer.granary;

/**
 * 一个储粮囤在粮仓里的启用状态 (Spoilage_Granary_DesignSpec「容量怎么算」)。
 *
 * 设计文档把「撑不住的囤」分成两种, 必须分开记 —— 待启用的新囤**从来没停过表**, 一进来就只是不能存;
 * 停用的老囤是**原来在停表、部件被拆之后排不上的**, 里面的东西照常腐烂但要走宽限期。
 * 两者对玩家的提示、对结算的算法都不一样 (宽限期那支的分片按这个状态分流), 所以状态在账本里落盘。
 */
public enum GranaryBinState {

    /** 启用: 能存能取, 存满 24 小时起停表。 */
    ACTIVE,
    /** 待启用: 新放进来的囤, 撑不住 (部件不够/超额/到 128 个上限) —— 只能取不能存。 */
    PENDING,
    /** 停用: 原来启用、现在撑不住的老囤 —— 只能取不能存, 宽限期内照常停表。 */
    STOPPED;

    /** 能不能往里存 (只有启用能存)。 */
    public boolean acceptsDeposits() {
        return this == ACTIVE;
    }

    /** 语言键。 */
    public String translationKey() {
        return "granary.bin_state." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
