package com.miningdim.title;

/** 专属称号管理员处置 (清空、锁定 / 解锁、清除冷却) 的结果 (Title_System_DesignSpec 13.7)。 */
public enum CustomAdminResult {
    /** 已执行。 */
    DONE,
    /** 该玩家没有专属称号记录, 无从处置。 */
    NO_CUSTOM_TITLE,
    /** 状态本来就是目标值 (已锁定再锁定、未锁定再解锁、不在冷却中), 未改动。 */
    UNCHANGED,
    /** 记录处于锁定状态, 拒绝清空: 清空会连同锁定一起删掉, 须先解锁, 以免锁定被悄悄解除。 */
    LOCKED
}
