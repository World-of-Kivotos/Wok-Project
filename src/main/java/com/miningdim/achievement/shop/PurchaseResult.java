package com.miningdim.achievement.shop;

import com.miningdim.achievement.reward.PointBalance;
import org.jetbrains.annotations.Nullable;

/**
 * 一次成就点商店兑换 ({@link AchievementPointShop#buy}) 的结果。
 *
 * 兑换要么整体成功, 要么什么都没有发生: 失败的各种情况下库里没有任何改动 (没开事务就被拦下, 或事务已回滚),
 * 也没有发出任何物品或称号。
 *
 * @param status    结果
 * @param goods     涉及的商品; {@link Status#GOODS_UNKNOWN} 且数据包里查无此 id 时为 null
 * @param balance   {@link Status#PURCHASED} 时为扣点提交后的成就点, {@link Status#POINTS_INSUFFICIENT} 时为当前成就点;
 *                  其余为 null
 * @param purchased {@link Status#PURCHASED} 时为含本次在内的兑换次数, {@link Status#LIMIT_REACHED} 时为已兑换次数;
 *                  其余为 0
 */
public record PurchaseResult(Status status, @Nullable PointShopGoods goods, @Nullable PointBalance balance,
                             int purchased) {

    public enum Status {
        /** 已兑换: 扣点、shop_buy 流水与称号 (称号类) 已提交, 物品 (物品类) 已放进背包。 */
        PURCHASED,
        /** 数据包里没有这件商品, 或称号类商品引用的称号定义当前没有加载。 */
        GOODS_UNKNOWN,
        /** 商品要求先获得某个成就, 玩家尚未获得。 */
        REQUIREMENT_UNMET,
        /** 称号类商品: 玩家已经拥有这个称号 (例如管理员发过), 不再收点。 */
        TITLE_OWNED,
        /** 已达每人限购。 */
        LIMIT_REACHED,
        /** 成就点不足。 */
        POINTS_INSUFFICIENT,
        /** 物品类商品: 背包放不下 (扣点前检查)。 */
        INVENTORY_FULL,
        /** 读写数据库失败; 整次兑换已回滚。 */
        STORE_FAILED
    }

    static PurchaseResult purchased(PointShopGoods goods, PointBalance balance, int purchased) {
        return new PurchaseResult(Status.PURCHASED, goods, balance, purchased);
    }

    static PurchaseResult rejected(Status status, @Nullable PointShopGoods goods) {
        return new PurchaseResult(status, goods, null, 0);
    }

    public boolean success() {
        return status == Status.PURCHASED;
    }
}
