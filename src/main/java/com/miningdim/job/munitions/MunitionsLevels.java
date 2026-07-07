package com.miningdim.job.munitions;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import net.minecraft.world.entity.player.Player;

/**
 * 军火商等级 / 解锁判定 + 产能曲线查表的薄封装 (Munitions_Job_DesignSpec 六/七章)。等级曲线 (七章 61,900 XP) +
 * 每日衰减 (七章软上限) + 经验入账由共享职业框架 (JobProgress.grantXp / IJobService.grantXp) 统一裁决; 本类只负责:
 *  - tableCount/ratePerTable/bufferPerTable(level): 等级 -> 产能参数 (6.1, 经 {@link MunitionsConfig} 实时 get);
 *  - isCaliberUnlocked / highestUnlockedCaliber: 等级 -> 已解锁口径 (6.1 等级门);
 *  - isRefineUnlocked: 等级 >= L6 解锁发射药提炼 (四章利润质变线);
 *  - 经 {@link JobServices#jobService()} 读军火商等级 + 入账原始经验 (职业侧只发原始经验, 衰减/翻日/升级框架管)。
 *
 * 严禁本类自行折算衰减或重定义曲线 (框架 spec: 消除 spec 漂移)。军火商特有数值才在此 (经 MunitionsConfig)。
 */
public final class MunitionsLevels {

    /** 军火商等级合法区间 (六章 1-10)。 */
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 10;

    private MunitionsLevels() {
    }

    /** 读玩家军火商等级 (经职业框架门面; 未挂载 capability 返回 1 级)。 */
    public static int munitionsLevel(Player player) {
        return JobServices.jobService().level(player, JobId.MUNITIONS);
    }

    /**
     * 给玩家入账一笔军火商原始经验 (经职业框架统一衰减/翻日/升级软上限)。
     *
     * @param rawXp 原始经验 (>=0)
     * @return 经每日衰减折算后实际入账的有效经验 (>=0; 未挂载 capability 返回 0)
     */
    public static long grantRawXp(Player player, long rawXp) {
        return JobServices.jobService().grantXp(player, JobId.MUNITIONS, rawXp);
    }

    /** 把任意等级值夹到合法区间 (防越界查表; 六章 1-10)。 */
    public static int clampLevel(int level) {
        if (level < MIN_LEVEL) {
            return MIN_LEVEL;
        }
        return Math.min(level, MAX_LEVEL);
    }

    /** 某等级允许拥有的制造台数上限 (6.1; 受等级上限约束, 放置门控用)。 */
    public static int tableCount(int level) {
        return switch (clampLevel(level)) {
            case 1 -> MunitionsConfig.TABLE_COUNT_L1.get();
            case 2 -> MunitionsConfig.TABLE_COUNT_L2.get();
            case 3 -> MunitionsConfig.TABLE_COUNT_L3.get();
            case 4 -> MunitionsConfig.TABLE_COUNT_L4.get();
            case 5 -> MunitionsConfig.TABLE_COUNT_L5.get();
            case 6 -> MunitionsConfig.TABLE_COUNT_L6.get();
            case 7 -> MunitionsConfig.TABLE_COUNT_L7.get();
            case 8 -> MunitionsConfig.TABLE_COUNT_L8.get();
            case 9 -> MunitionsConfig.TABLE_COUNT_L9.get();
            default -> MunitionsConfig.TABLE_COUNT_L10.get();
        };
    }

    /** 某等级每台速率 (发/时·步枪当量; 6.1)。 */
    public static int ratePerTable(int level) {
        return switch (clampLevel(level)) {
            case 1 -> MunitionsConfig.RATE_L1.get();
            case 2 -> MunitionsConfig.RATE_L2.get();
            case 3 -> MunitionsConfig.RATE_L3.get();
            case 4 -> MunitionsConfig.RATE_L4.get();
            case 5 -> MunitionsConfig.RATE_L5.get();
            case 6 -> MunitionsConfig.RATE_L6.get();
            case 7 -> MunitionsConfig.RATE_L7.get();
            case 8 -> MunitionsConfig.RATE_L8.get();
            case 9 -> MunitionsConfig.RATE_L9.get();
            default -> MunitionsConfig.RATE_L10.get();
        };
    }

    /** 某等级单台缓冲上限 (发; 6.1, 缓冲满停产)。 */
    public static int bufferPerTable(int level) {
        return switch (clampLevel(level)) {
            case 1 -> MunitionsConfig.BUFFER_L1.get();
            case 2 -> MunitionsConfig.BUFFER_L2.get();
            case 3 -> MunitionsConfig.BUFFER_L3.get();
            case 4 -> MunitionsConfig.BUFFER_L4.get();
            case 5 -> MunitionsConfig.BUFFER_L5.get();
            case 6 -> MunitionsConfig.BUFFER_L6.get();
            case 7 -> MunitionsConfig.BUFFER_L7.get();
            case 8 -> MunitionsConfig.BUFFER_L8.get();
            case 9 -> MunitionsConfig.BUFFER_L9.get();
            default -> MunitionsConfig.BUFFER_L10.get();
        };
    }

    /** 某口径档是否已被该等级解锁 (6.1 口径等级门)。 */
    public static boolean isCaliberUnlocked(int level, MunitionsCaliber caliber) {
        return level >= caliber.unlockLevel();
    }

    /**
     * 给定等级, 返回已解锁的最高口径档 (6.1)。未达任何档 (不可能, 最低 L1 解锁 PISTOL) 兜底 PISTOL。
     */
    public static MunitionsCaliber highestUnlockedCaliber(int level) {
        MunitionsCaliber best = MunitionsCaliber.PISTOL;
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            if (level >= caliber.unlockLevel() && caliber.unlockLevel() > best.unlockLevel()) {
                best = caliber;
            }
        }
        return best;
    }

    /** 该等级是否解锁发射药提炼 (四章 L6 利润质变线; 经 config REFINE_UNLOCK_LEVEL)。 */
    public static boolean isRefineUnlocked(int level) {
        return level >= MunitionsConfig.REFINE_UNLOCK_LEVEL.get();
    }
}
