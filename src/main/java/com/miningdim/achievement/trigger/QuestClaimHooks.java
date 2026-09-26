package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementConfig;
import com.miningdim.achievement.AchievementServices;
import com.miningdim.achievement.AchievementStoreException;
import com.miningdim.quest.QuestClaim;
import com.miningdim.quest.QuestPool;
import com.miningdim.quest.QuestServices;
import com.miningdim.quest.QuestSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务领奖的成就信号 (Achievement_System_DesignSpec 6.1 quests_completed / quest_daily_clears、6.2 quest_complete、
 * 9.5), 由 {@link SocialEconomyHooks} 注册进任务模块的领奖监听 (9.10), 在 {@code QuestService#claim} 返回 CLAIMED
 * 之前的最后一步收到。
 *
 * <ul>
 *   <li>quests_completed: 每人每天 (UTC) 最多计 dailyQuestCap 份, 当天已计次数存本模块的每日计数表;</li>
 *   <li>quest_daily_clears: 领的是每日任务、且领完之后当天的每日任务已全部领取时加一, 以任务板的日常周期戳为"天",
 *       每个周期戳最多一次 (同一天重摇出新日常再领完也不重复计);</li>
 *   <li>quest_complete: 带上来源、任务 id、所属任务线与是否走完整条线触发。统计项先于触发器递增, 所以"初次委托"
 *       (quests_completed ≥ 1) 总在它下面的子成就之前到手。</li>
 * </ul>
 * 每日计数写库失败只记错误日志, 这一次不加统计 (宁可少发), 触发器照常触发。FakePlayer 不计。
 */
final class QuestClaimHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement/hooks");

    private QuestClaimHooks() {
    }

    /** 一次领奖已完成 ({@link com.miningdim.quest.QuestClaimListener})。 */
    static void onQuestClaimed(QuestClaim claim) {
        ServerPlayer player = claim.player();
        if (player instanceof FakePlayer) {
            return;
        }
        QuestSource source = claim.definition().source();
        try {
            countClaim(player, source == QuestSource.DAILY && claim.dailiesAllClaimed(), claim.dailyStamp());
            AchievementTriggers.QUEST_COMPLETE.trigger(player, source, claim.definition().id(), claim.chainId(),
                    claim.chainFinished());
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] quest claim {} of {} not credited to achievements", claim.definition().id(),
                    player.getGameProfile().getName(), failure);
        }
    }

    /**
     * 上线追溯 (9.9): 经任务模块的只读接口查"神射手"任务线是否已经走完, 走完则按"走完整条线"触发 quest_complete
     * (不知道具体阶段, 所以不带任务 id)。任务服务未就绪时跳过。
     */
    static void backfillMarksmanChain(ServerPlayer player) {
        if (QuestServices.isRegistered()
                && QuestServices.service().chainFinished(player.server, player.getUUID(), QuestPool.CHAIN_MARKSMAN)) {
            AchievementTriggers.QUEST_COMPLETE.trigger(player, QuestSource.HIDDEN, null, QuestPool.CHAIN_MARKSMAN,
                    true);
        }
    }

    private static void countClaim(ServerPlayer player, boolean clearedDailies, long dailyStamp) {
        try {
            DailyCounterRepository counters = AchievementServices.dailyCounters();
            if (counters.incrementIfBelow(player.getUUID(), DailyCounterRepository.QUESTS_COMPLETED_KEY,
                    DailyCounterRepository.today(), AchievementConfig.DAILY_QUEST_CAP.get())) {
                AchievementStats.award(player, AchievementStats.QUESTS_COMPLETED, 1);
            }
            if (clearedDailies && counters.incrementIfBelow(player.getUUID(),
                    DailyCounterRepository.QUEST_DAILY_CLEAR_KEY, dailyStamp, 1)) {
                AchievementStats.award(player, AchievementStats.QUEST_DAILY_CLEARS, 1);
            }
        } catch (AchievementStoreException failure) {
            LOGGER.error("[miningdim] quest claim of {} not counted: daily counter unavailable",
                    player.getGameProfile().getName(), failure);
        }
    }
}
