package com.miningdim.job.engineer;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import net.minecraft.world.entity.player.Player;

/**
 * 工程师等级 / 解锁判定的薄封装 (MillenniumEngineer_Mod_DesignSpec 七章)。等级曲线 (7.1) + 每日衰减 (7.3) +
 * 经验入账由共享职业框架 (JobXpCurve / JobProgress.grantXp / IJobService.grantXp) 统一裁决, 本类只负责:
 *  - unlockedTier(level): 等级 -> 已解锁最高档 (7.2 每两级一档);
 *  - 经 {@link JobServices#jobService()} 读工程师等级 + 入账原始经验 (职业侧只发原始经验, 衰减/翻日/升级框架管)。
 *
 * 严禁本类自行折算衰减或重定义曲线 (框架 spec: 消除 spec 漂移)。工程师特有数值 (单档原始经验/解锁档) 才在此。
 */
public final class EngineerLevels {

    private EngineerLevels() {
    }

    /**
     * 给定工程师等级, 返回已解锁的最高护甲板/生产台档 (7.2)。L1->LOW, L3->MEDIUM, L5->HIGH, L7->SUPERIOR,
     * L9->TRANSCENDENT, L10->RADIANT。未达 L1 (不可能, 最低 1) 兜底 LOW。
     */
    public static NanoTier unlockedTier(int level) {
        NanoTier best = null;
        for (NanoTier tier : NanoTier.values()) {
            if (level >= tier.unlockLevel()) {
                best = tier;
            }
        }
        return best == null ? NanoTier.LOW : best;
    }

    /** 某档是否已被该等级解锁 (7.2 等级门)。 */
    public static boolean isTierUnlocked(int level, NanoTier tier) {
        return level >= tier.unlockLevel();
    }

    /** 读玩家工程师等级 (经职业框架门面; 未挂载 capability 返回 1 级)。 */
    public static int engineerLevel(Player player) {
        return JobServices.jobService().level(player, JobId.ENGINEER);
    }

    /**
     * 给玩家入账一笔工程师原始经验 (经职业框架统一衰减/翻日/升级)。
     *
     * @param rawXp 原始经验 (>=0)
     * @return 经每日衰减折算后实际入账的有效经验 (>=0; 未挂载 capability 返回 0)
     */
    public static long grantRawXp(Player player, long rawXp) {
        return JobServices.jobService().grantXp(player, JobId.ENGINEER, rawXp);
    }
}
