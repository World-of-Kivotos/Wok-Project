package com.miningdim.quest;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 一次成功的任务领奖 (交给 {@link QuestClaimListener} 的只读事实)。
 *
 * @param player            领奖玩家
 * @param definition        领取的任务 (来源、id 都从这里取)
 * @param chainId           所属隐藏任务线; 不属于任何任务线为 null
 * @param chainFinished     这次领奖是否走完了整条任务线 (只有任务线的最后一个阶段为 true)
 * @param dailyStamp        领奖时任务板的日常周期戳 (UTC epochDay)
 * @param dailiesAllClaimed 领奖之后当天的每日任务是否已全部领取 (当天一条日常都没有时为 false)
 */
public record QuestClaim(ServerPlayer player, QuestDefinition definition, @Nullable String chainId,
                         boolean chainFinished, long dailyStamp, boolean dailiesAllClaimed) {
}
