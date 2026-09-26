package com.miningdim.achievement.reward;

import com.miningdim.achievement.command.AchievementCommands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 奖励与命令的挂载点 (Achievement_System_DesignSpec 第七章、第十一章), 由 {@code AchievementSystem} 在 mod 构造期调用一次:
 * <ul>
 *   <li>{@link AdvancementEvent.AdvancementEarnEvent}: 获得会产生奖励的进度时写一条待领取奖励并提示玩家
 *       ({@link AchievementRewardService#recordEarned})。原版每次授予 (含撤销后再授予) 都会发这个事件,
 *       去重靠奖励表的主键;</li>
 *   <li>{@link RegisterCommandsEvent}: 注册 {@code /machievement}。</li>
 * </ul>
 */
public final class AchievementRewardSystem {

    private AchievementRewardSystem() {
    }

    /** 把奖励与命令的监听挂上 Forge 总线。 */
    public static void register(IEventBus forgeBus) {
        forgeBus.addListener(EventPriority.NORMAL, false, AdvancementEvent.AdvancementEarnEvent.class,
                AchievementRewardSystem::onAdvancementEarned);
        forgeBus.addListener(EventPriority.NORMAL, false, RegisterCommandsEvent.class,
                event -> AchievementCommands.register(event.getDispatcher()));
    }

    private static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AchievementRewardService.recordEarned(player, event.getAdvancement());
        }
    }
}
