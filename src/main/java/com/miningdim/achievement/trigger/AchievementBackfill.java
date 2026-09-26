package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementServices;
import com.miningdim.core.AdvancementSilence;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * 上线时的静默追溯 (Achievement_System_DesignSpec 9.9): 玩家登录时, 把查得到的历史进度按成就条件补一遍, 补出来的成就
 * 不做全服公告、不弹 Toast, 但照常生成待领取奖励 (奖励由 AdvancementEarnEvent 驱动, 静默不影响它)。
 *
 * <p>静默的实现在核心模块: {@link AdvancementSilence#run} 打开线程局部开关, 混入原版 {@code PlayerAdvancements}
 * 的 {@code PlayerAdvancementsSilenceMixin} 在开关打开时跳过公告、并保证完成的进度随重置包下发 (客户端对重置包不弹
 * Toast)。登录事件发生在玩家的第一个进度包之前, 这里补出的进度本来就落在第一个 (重置) 包里。
 *
 * <p>每一步都是幂等的: 原版对已完成的条件直接忽略触发, 待领取奖励按 (玩家, 进度) 主键去重, 所以每次登录都完整跑一遍,
 * 不记"已追溯过"。某一步抛出只记日志, 不影响后面的步骤, 也不影响登录。
 *
 * <p>新增一类追溯 (职业等级就是这样经 {@link JobHooks#checkJobLevels} 接入的) 时在 {@link #STEPS} 里加一行:
 * 步骤只管触发自己的条件, 静默由这里统一包住; 登录以外的时刻需要静默授予时, 直接用 {@link AdvancementSilence#run} 包住授予即可。
 * 挖方块、撤离、击杀、收获、credits_earned、任务领取次数这类计数没有可靠的历史数据, 一律从零开始, 不在追溯之列。
 */
public final class AchievementBackfill {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement/hooks");

    /** 一个追溯步骤; 名称只用于日志。 */
    private record Step(String name, Consumer<ServerPlayer> action) {
    }

    /** 按顺序执行。成就数量放在最后, 让前面补出的成就都已计入。 */
    private static final List<Step> STEPS = List.of(
            // 数据包新增阈值成就后, 已经达标的玩家上线即得。
            new Step("stat thresholds", AchievementTriggers.STAT_AT_LEAST::trigger),
            // 婚姻指针 (核心模块的 Capability)。
            new Step("marriage pointer", PlayerProgressHooks::checkMarried),
            // 职业等级 (wok-experience 里八条职业轨道的快照): /job set 与管理员改级不经经验路由, 在这里补判 job_level。
            new Step("job levels", JobHooks::checkJobLevels),
            // 开箱模块的只读接口 bestSettledRarity (已结算开箱的最高品质)。
            new Step("settled case openings", EconomyHooks::backfillSettledOpenings),
            // 任务模块的只读接口 chainFinished。
            new Step("marksman chain", QuestClaimHooks::backfillMarksmanChain),
            new Step("achievement count", player -> AchievementTriggers.ACHIEVEMENT_COUNT.trigger(player,
                    AchievementServices.catalog().countEarned(player.getAdvancements()))));

    private AchievementBackfill() {
    }

    /** 在静默状态下对该玩家执行全部追溯步骤 (登录时由 {@link PlayerProgressHooks} 调用)。 */
    public static void runSilently(ServerPlayer player) {
        AdvancementSilence.run(() -> {
            for (Step step : STEPS) {
                try {
                    step.action().accept(player);
                } catch (RuntimeException failure) {
                    LOGGER.error("[miningdim] achievement backfill step '{}' failed for {}", step.name(),
                            player.getGameProfile().getName(), failure);
                }
            }
        });
    }
}
