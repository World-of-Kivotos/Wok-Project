package com.miningdim.title;

/** {@link ITitleService#grant} 系列的结果。 */
public enum GrantResult {
    /** 新发放, 已落库。 */
    GRANTED,
    /** 此前已拥有; 按主键幂等, 最早的来源记录保持不变。 */
    ALREADY_OWNED,
    /** 当前没有加载这个称号的定义 (id 拼错或数据包缺失); 不落库。 */
    UNKNOWN_TITLE
}
