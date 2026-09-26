package com.miningdim.title;

/** {@link ITitleService#grant} 系列的结果。 */
public enum GrantResult {
    /** 新发放, 已落库。 */
    GRANTED,
    /** 此前已拥有; 按主键幂等, 最早的来源记录保持不变。 */
    ALREADY_OWNED,
    /** 当前没有加载这个称号的定义 (id 拼错或数据包缺失); 不落库。 */
    UNKNOWN_TITLE,
    /**
     * 专属称号 ({@code miningdim:custom/*}) 不能发放: 它只随赞助资格与玩家自己的设置产生
     * (Title_System_DesignSpec 13.2), 防止冒用; 不落库。
     */
    NOT_GRANTABLE
}
