package com.miningdim.achievement.reward;

import com.miningdim.achievement.tier.AchievementTier;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 一条成就奖励 (表 {@code achievement_reward} 的一行, Achievement_System_DesignSpec 7.2)。
 *
 * 点数与称号是获得那一刻的元数据快照: 之后调整数值不影响已经产生的记录。
 *
 * @param claimedAt 领取时间 (毫秒); null 表示待领取
 */
public record AchievementReward(UUID player, ResourceLocation advancementId, AchievementTier tier, int points,
                                @Nullable ResourceLocation titleId, long earnedAt, @Nullable Long claimedAt) {

    public boolean isClaimed() {
        return claimedAt != null;
    }
}
