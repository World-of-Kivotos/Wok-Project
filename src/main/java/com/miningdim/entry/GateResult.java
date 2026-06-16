package com.miningdim.entry;

/**
 * 难度解锁门控结果 (设计文档 14.4)。门控失败返回明确原因码 + i18n key, 由命令/网络入口本地化提示玩家。
 * 当前实现等级门槛 (Easy 无 / Medium L10 / Hard L25); 成就与入场券为后续可配开关, 接口预留原因码。
 */
public enum GateResult {

    PASS(null),
    LEVEL_TOO_LOW("message.miningdim.gate.level_too_low"),
    MISSING_ADVANCEMENT("message.miningdim.gate.missing_advancement"),
    NO_TICKET("message.miningdim.gate.no_ticket");

    private final String reasonKey;

    GateResult(String reasonKey) {
        this.reasonKey = reasonKey;
    }

    public boolean passed() {
        return this == PASS;
    }

    /** i18n key; PASS 时为 null。 */
    public String reasonKey() {
        return reasonKey;
    }
}
