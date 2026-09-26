package com.miningdim.achievement.reward;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 一次领取 ({@link AchievementRewardService#claim} / {@link AchievementRewardService#claimSelected} /
 * {@link AchievementRewardService#claimAll}) 的结果。
 *
 * 领取要么整体成功, 要么什么都没有发生: 失败的各种情况下库里没有任何改动 (事务已回滚), {@link #claimed()} 为空。
 *
 * @param status        结果
 * @param claimed       本次领取的奖励 (仅 {@link Status#CLAIMED}; 其余为空表)
 * @param balance       领取提交后的成就点 (仅 {@link Status#CLAIMED}; 其余为 null)
 * @param advancementId 失败时涉及的那条奖励的进度 id (未找到、已领取、称号无法发放); 其余为 null
 * @param titleId       {@link Status#TITLE_UNAVAILABLE} 时发不出去的称号 id; 其余为 null
 */
public record ClaimResult(Status status, List<AchievementReward> claimed, @Nullable PointBalance balance,
                          @Nullable ResourceLocation advancementId, @Nullable ResourceLocation titleId) {

    public ClaimResult {
        claimed = List.copyOf(claimed);
    }

    /** 领取结果。 */
    public enum Status {
        /** 已领取: 标记已领取、成就点入账、流水、称号都已提交。 */
        CLAIMED,
        /** 该玩家没有这个进度的奖励记录 (没获得过、不产生奖励, 或 id 写错)。 */
        NOT_FOUND,
        /** 这条奖励已经领取过了 (WebUI 错误码 REWARD_ALREADY_CLAIMED)。 */
        ALREADY_CLAIMED,
        /** "全部领取"时没有待领取的奖励。 */
        NOTHING_PENDING,
        /** 附带的称号发不出去 (称号门面未注入、定义已不存在或 id 不可发放); 整次领取已回滚。 */
        TITLE_UNAVAILABLE,
        /** 读写数据库失败; 整次领取已回滚。 */
        STORE_FAILED
    }

    static ClaimResult claimed(List<AchievementReward> claimed, PointBalance balance) {
        return new ClaimResult(Status.CLAIMED, claimed, balance, null, null);
    }

    static ClaimResult rejected(Status status, @Nullable ResourceLocation advancementId,
                                @Nullable ResourceLocation titleId) {
        return new ClaimResult(status, List.of(), null, advancementId, titleId);
    }

    public boolean success() {
        return status == Status.CLAIMED;
    }

    /** 本次入账的成就点合计。 */
    public long points() {
        long total = 0L;
        for (AchievementReward reward : claimed) {
            total += reward.points();
        }
        return total;
    }
}
