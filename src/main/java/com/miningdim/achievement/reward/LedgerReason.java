package com.miningdim.achievement.reward;

/** 成就点流水 ({@code achievement_point_ledger.reason}) 的来源, 落库为小写 id (Achievement_System_DesignSpec 7.2)。 */
public enum LedgerReason {
    /** 领取成就奖励, ref 记进度 id。 */
    CLAIM("claim"),
    /** 成就点商店兑换, ref 记商品 id。 */
    SHOP_BUY("shop_buy"),
    /** 管理员调整 (/machievement points)。 */
    ADMIN("admin");

    private final String id;

    LedgerReason(String id) {
        this.id = id;
    }

    /** 落库用的小写 id。 */
    public String id() {
        return id;
    }
}
