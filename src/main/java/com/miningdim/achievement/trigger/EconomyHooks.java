package com.miningdim.achievement.trigger;

import com.miningdim.caseopening.CaseRarity;
import com.miningdim.caseopening.CaseServices;
import com.miningdim.caseopening.SettledOpening;
import com.miningdim.economy.EconomyServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 经济页签的两路信号 (Achievement_System_DesignSpec 6.1 credits_earned、6.2 case_open、9.4), 由
 * {@link SocialEconomyHooks} 注册进经济模块与开箱模块的监听接口 (9.10)。
 *
 * <ul>
 *   <li>faucet 入账: {@code grantDaily} 的账本事务提交后, 把衰减后的实际入账额加进 credits_earned (所有 faucet 键)。
 *       FakePlayer 不计。</li>
 *   <li>开箱结算: 结算锚所在事务提交后触发 case_open。正常开箱 (fresh) 带上此刻读到的信用点余额, 即开箱后余额;
 *       恢复流程补结算的不带。开箱人不在线 (启动期对账) 时什么都不做, 等他登录时由上线追溯
 *       ({@link #backfillSettledOpenings}) 补上。</li>
 * </ul>
 * 两个监听器都自己捕获并记录异常, 不让成就侧的失败影响入账与开箱。
 */
final class EconomyHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement/hooks");

    private EconomyHooks() {
    }

    /** faucet 入账已提交 ({@link com.miningdim.economy.FaucetListener})。 */
    static void onFaucetCredited(ServerPlayer player, String faucetKey, long credited) {
        if (player instanceof FakePlayer) {
            return;
        }
        try {
            AchievementStats.award(player, AchievementStats.CREDITS_EARNED, (int) Math.min(credited, Integer.MAX_VALUE));
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] {} credit from {} not added to credits_earned of {}", credited, faucetKey,
                    player.getGameProfile().getName(), failure);
        }
    }

    /** 开箱已结算 ({@link com.miningdim.caseopening.CaseOpeningListener})。 */
    static void onOpeningSettled(SettledOpening opening) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            ServerPlayer owner = server == null ? null : server.getPlayerList().getPlayer(opening.ownerId());
            if (owner == null) {
                return;
            }
            long creditAfter = opening.fresh() ? EconomyServices.economyService().creditBalance(owner) : 0L;
            AchievementTriggers.CASE_OPEN.trigger(owner, opening.rarity(), opening.fresh(), creditAfter);
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] case opening {} of {} not credited to achievements", opening.openingId(),
                    opening.ownerId(), failure);
        }
    }

    /**
     * 上线追溯 (9.9): 按开箱模块的只读接口查该玩家已结算的开箱, 以其中最高的品质触发一次 case_open (都不算正常开箱,
     * 所以"倾家荡产"不会因此达成)。开箱服务未就绪时跳过。
     */
    static void backfillSettledOpenings(ServerPlayer player) {
        if (!CaseServices.isRegistered()) {
            return;
        }
        CaseRarity best = null;
        for (SettledOpening opening : CaseServices.service().settledOpenings(player.getUUID())) {
            if (best == null || opening.rarity().ordinal() > best.ordinal()) {
                best = opening.rarity();
            }
        }
        if (best != null) {
            AchievementTriggers.CASE_OPEN.trigger(player, best, false, 0L);
        }
    }
}
