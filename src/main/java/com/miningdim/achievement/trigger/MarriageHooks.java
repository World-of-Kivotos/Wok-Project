package com.miningdim.achievement.trigger;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 婚姻模块的两路信号 (Achievement_System_DesignSpec 9.5 social/married、social/long_distance), 由
 * {@link SocialEconomyHooks} 注册进 {@code MarriageEvents} (9.10)。
 *
 * <ul>
 *   <li>婚礼: 婚礼成功返回前对双方各通知一次, 当场触发 married。登录时读婚姻指针的补查仍在 (上线追溯, 静默),
 *       自动保存与打开共享背包时的补查也仍在, 它们对已经拿到成就的玩家是空操作。</li>
 *   <li>传送到伴侣身边: 传送完成时带上蓄力开始时双方的水平距离触发 spouse_teleport。先补查一次 married, 让父成就
 *       "执子之手"不会落在"千里赴约"之后。</li>
 * </ul>
 * 两个监听器都自己捕获并记录异常, 不影响婚礼与传送。
 */
final class MarriageHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement/hooks");

    private MarriageHooks() {
    }

    /** 婚礼已完成 ({@code MarriageEvents.WeddingListener}), 对双方各调用一次。 */
    static void onWedding(ServerPlayer player, ServerPlayer spouse) {
        try {
            AchievementTriggers.MARRIED.trigger(player);
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] wedding of {} and {} not credited to achievements",
                    player.getGameProfile().getName(), spouse.getGameProfile().getName(), failure);
        }
    }

    /** 传送到伴侣身边已完成 ({@code MarriageEvents.TeleportListener})。 */
    static void onSpouseTeleport(ServerPlayer traveller, ServerPlayer spouse, double horizontalDistance) {
        try {
            PlayerProgressHooks.checkMarried(traveller);
            AchievementTriggers.SPOUSE_TELEPORT.trigger(traveller, horizontalDistance);
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] teleport of {} to {} not credited to achievements",
                    traveller.getGameProfile().getName(), spouse.getGameProfile().getName(), failure);
        }
    }
}
