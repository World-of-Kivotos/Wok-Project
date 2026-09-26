package com.miningdim.achievement;

import com.miningdim.achievement.meta.AchievementCatalog;
import com.miningdim.achievement.reward.AchievementRewardRepository;
import com.miningdim.achievement.shop.PointShopCatalog;
import com.miningdim.achievement.shop.PointShopRepository;
import com.miningdim.achievement.trigger.DailyCounterRepository;
import com.miningdim.achievement.trigger.MarketPartnerRepository;

/**
 * 成就模块的运行期定位器 (仿 TitleServices 的平行定位器范式, 不改 core.MiningServices)。
 *
 * <ul>
 *   <li>{@link #catalog()}: 查询快照 (档位、成就点、称号、成就数量候选)。开服完成与每次 /reload 后由成就子系统重建;
 *       在那之前与停服后是 {@link AchievementCatalog#EMPTY}, 所有查询都回答"没有", 不抛异常 ——
 *       宁可少发, 不可错发。</li>
 *   <li>{@link #rewards()} / {@link #dailyCounters()} / {@link #marketPartners()} / {@link #pointShopRepository()}:
 *       存储边界, ServerStarting 时绑定到统一库的共享连接, ServerStopping 时清空; 未绑定时取用抛
 *       IllegalStateException (装配顺序错误, 不是可恢复的运行期状态)。</li>
 *   <li>{@link #pointShop()}: 成就点商店当前上架的商品, 每次数据包 (重新) 加载后由商品加载器整表替换; 停服后与没有
 *       商品时是 {@link PointShopCatalog#EMPTY}。</li>
 * </ul>
 */
public final class AchievementServices {

    private static volatile AchievementCatalog catalog = AchievementCatalog.EMPTY;
    private static volatile AchievementRewardRepository rewards;
    private static volatile DailyCounterRepository dailyCounters;
    private static volatile MarketPartnerRepository marketPartners;
    private static volatile PointShopCatalog pointShop = PointShopCatalog.EMPTY;
    private static volatile PointShopRepository pointShopRepository;

    private AchievementServices() {
    }

    /** 当前查询快照 (从不为 null)。 */
    public static AchievementCatalog catalog() {
        return catalog;
    }

    /** 装入新建的快照 (成就子系统在开服完成与每次数据包重载后调用)。 */
    public static void installCatalog(AchievementCatalog rebuilt) {
        if (rebuilt == null) {
            throw new IllegalArgumentException("Cannot install a null AchievementCatalog");
        }
        catalog = rebuilt;
    }

    /** 绑定存储边界 (成就子系统在 ServerStarting 调用; GameTest 可换成临时库上的实现)。 */
    public static void bindRepositories(AchievementRewardRepository rewardRepository,
                                        DailyCounterRepository dailyCounterRepository) {
        if (rewardRepository == null || dailyCounterRepository == null) {
            throw new IllegalArgumentException("Cannot bind null achievement repositories");
        }
        rewards = rewardRepository;
        dailyCounters = dailyCounterRepository;
    }

    /** 绑定市场成就的按买家记账 (社交与经济钩子在 ServerStarting 调用)。 */
    public static void bindMarketPartners(MarketPartnerRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Cannot bind a null market partner repository");
        }
        marketPartners = repository;
    }

    /** 待领取奖励与成就点账本。 */
    public static AchievementRewardRepository rewards() {
        AchievementRewardRepository current = rewards;
        if (current == null) {
            throw notBound();
        }
        return current;
    }

    /** 统计项每日上限计数。 */
    public static DailyCounterRepository dailyCounters() {
        DailyCounterRepository current = dailyCounters;
        if (current == null) {
            throw notBound();
        }
        return current;
    }

    /** 市场成就的按买家记账 (6.6)。 */
    public static MarketPartnerRepository marketPartners() {
        MarketPartnerRepository current = marketPartners;
        if (current == null) {
            throw notBound();
        }
        return current;
    }

    /** 成就点商店当前上架的商品 (从不为 null)。 */
    public static PointShopCatalog pointShop() {
        return pointShop;
    }

    /** 装入新加载的商品 (商品加载器在每次数据包加载后调用; GameTest 可换成夹具商品)。 */
    public static void installPointShop(PointShopCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("Cannot install a null PointShopCatalog");
        }
        pointShop = catalog;
    }

    /** 绑定成就点商店的存储边界 (成就子系统在 ServerStarting 调用; GameTest 可换成临时库上的实现)。 */
    public static void bindPointShopRepository(PointShopRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Cannot bind a null point shop repository");
        }
        pointShopRepository = repository;
    }

    /** 成就点商店的兑换次数查询。 */
    public static PointShopRepository pointShopRepository() {
        PointShopRepository current = pointShopRepository;
        if (current == null) {
            throw notBound();
        }
        return current;
    }

    private static IllegalStateException notBound() {
        return new IllegalStateException("AchievementServices: repositories not bound yet "
                + "(the achievement subsystem binds them at server start)");
    }

    /** 服务端停止时清空, 防跨存档沿用上一个世界的快照与连接。 */
    public static void reset() {
        catalog = AchievementCatalog.EMPTY;
        rewards = null;
        dailyCounters = null;
        marketPartners = null;
        pointShop = PointShopCatalog.EMPTY;
        pointShopRepository = null;
    }
}
