package com.miningdim.title;

/** {@link ITitleService#equip} 的结果。服务端自行校验, 不相信客户端传入的值。 */
public enum EquipResult {
    /** 已佩戴 (含原本就佩戴着同一个称号)。 */
    EQUIPPED,
    /** 已卸下 (含原本就没有佩戴)。 */
    UNEQUIPPED,
    /** 玩家未拥有该称号。 */
    NOT_OWNED,
    /** 当前没有加载这个称号的定义。 */
    UNKNOWN_TITLE;

    public boolean success() {
        return this == EQUIPPED || this == UNEQUIPPED;
    }
}
