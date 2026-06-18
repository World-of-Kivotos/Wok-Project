package com.miningdim.job.tarot.pack;

/**
 * 三种卡包 (TarotReader spec 第七章)。决定货币、出率与开包行为分支。
 */
public enum PackKind {
    /** 普通: 信用点购买, 1 张 R + 随机正逆。 */
    COMMON("common"),
    /** 高级: 信用点购买, 3 张独立 SR/SSR + 派生包。 */
    ADVANCED("advanced"),
    /** 闪耀: 青辉石/稀有掉落, 开出自选 1 张 SSR (不含 UR/闪耀)。 */
    SHINY("shiny");

    private final String id;

    PackKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
