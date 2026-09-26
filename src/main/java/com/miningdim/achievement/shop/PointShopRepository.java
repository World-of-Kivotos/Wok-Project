package com.miningdim.achievement.shop;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.UUID;

/**
 * 成就点商店的持久化边界 (Achievement_System_DesignSpec 7.2 / 8.4), 当前实现为 {@link SqlitePointShopRepository}。
 *
 * 不另建购买记录表: 每次兑换都在成就点流水里写一条 reason=shop_buy、ref=商品 id 的扣点记录 (与扣点同一事务),
 * 限购就数这些行。扣点、写流水与事务本身经 {@code AchievementRewardRepository}, 两个仓库在同一条共享连接上,
 * 在奖励仓库的事务里调用本接口即读到同一事务里的数据。
 *
 * 全部方法在服务端主线程同步调用。
 */
public interface PointShopRepository {

    /** 该玩家兑换过这件商品的次数。 */
    int purchaseCount(UUID player, ResourceLocation goodsId);

    /** 该玩家兑换过的每件商品及次数 (没兑换过的商品不在其中), 列表页一次查齐。 */
    Map<ResourceLocation, Integer> purchaseCounts(UUID player);
}
