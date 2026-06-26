package com.miningdim.marriage;

import com.miningdim.config.MiningServerConfig;

import java.util.List;

/**
 * 结婚系统阶段 2 数值派生 (结婚系统 spec 第四/五/六章; 实时读 {@link MiningServerConfig}, 严禁缓存)。把"婚龄"
 * 折算成共享背包/传送等级, 再把等级折算成容量/蓄力 T/CD; 所有阶梯阈值与各级数值都来自 config 列表, 业务代码
 * (menu/teleport/divorce) 只问本类要派生结果, 不各自硬编码同义裸常量 (C6 单一数据源)。
 *
 * 婚龄口径 (与离婚再婚冷却同源): 以 overworld {@code getGameTime()} 的"服务器运行 tick"为基准, 每 {@value #TICKS_PER_DAY}
 * tick = 1 天 (20 tick/s * 86400 s = 一真实日的服务器在线 tick), 故"30 天解锁满级背包"≈30 真实日服务器在线时长,
 * 与"再婚冷却 N 真实日"口径一致 (二者都用 lastWeddingTick/marriedSinceTick 这一 gameTime 轴)。
 *
 * 等级钳制: 共享背包/传送等级恒在 [1, {@value #MAX_LEVEL}]; 1 级永远可用 (即使婚龄为 0)。各级数值列表若 config 配短于
 * MAX_LEVEL, 取该列表最后一项兜底 (不抛, 不静默给 0); 列表为空属配置缺陷, 自然抛 (异常必痛, 不掩盖)。
 */
public final class MarriageTuning {

    private MarriageTuning() {
    }

    /** 共享背包/传送的最高等级 (spec 第四/五章 1..5)。 */
    public static final int MAX_LEVEL = 5;

    /** 一"天"对应的服务器运行 tick 数 (20 tick/s * 86400 s; 与再婚冷却同源, 见类注释)。 */
    public static final long TICKS_PER_DAY = 20L * 86400L;

    /**
     * 由婚龄 (当前 gameTime - marriedSinceTick) 派生共享背包解锁等级 (spec 第四章按婚龄阶梯)。
     * 从高到低逐级比对解锁阈值 (config backpackUnlockDays 第 i 项 = 第 i+1 级阈值天数), 命中即取该级; 兜底 1 级。
     *
     * @param marriedSinceTick 典礼完成时的 gameTime (婚龄起算点)
     * @param nowTick          当前 overworld gameTime
     * @return 共享背包当前解锁等级 [1, MAX_LEVEL]
     */
    public static int backpackLevel(long marriedSinceTick, long nowTick) {
        long days = marriedDays(marriedSinceTick, nowTick);
        List<? extends Integer> unlockDays = MiningServerConfig.MARRIAGE_BACKPACK_UNLOCK_DAYS.get();
        return levelForDays(days, unlockDays);
    }

    /**
     * 由婚龄派生传送解锁等级 (spec 第五章按婚龄阶梯)。本期传送与共享背包共用同一组婚龄阈值
     * (backpackUnlockDays) 派生等级 —— 婚龄即解锁尺度, 两功能同步成长; 若日后需独立阶梯, 在此分叉读独立 config。
     */
    public static int teleportLevel(long marriedSinceTick, long nowTick) {
        return backpackLevel(marriedSinceTick, nowTick);
    }

    /** 某共享背包等级暴露的格数 (spec 第四章; config backpackSlots 第 level-1 项)。 */
    public static int backpackVisibleSlots(int level) {
        return clampPick(MiningServerConfig.MARRIAGE_BACKPACK_SLOTS.get(), level, "backpackSlots");
    }

    /** 某传送等级的蓄力 tick 数 (spec 第五章; config teleportChargeSeconds 第 level-1 项 * 20)。 */
    public static int teleportChargeTicks(int level) {
        int seconds = clampPick(MiningServerConfig.MARRIAGE_TELEPORT_CHARGE_SECONDS.get(), level, "teleportChargeSeconds");
        return seconds * 20;
    }

    /** 某传送等级的冷却 tick 数 (spec 第五章; config teleportCooldownSeconds 第 level-1 项 * 20)。 */
    public static int teleportCooldownTicks(int level) {
        int seconds = clampPick(MiningServerConfig.MARRIAGE_TELEPORT_COOLDOWN_SECONDS.get(), level, "teleportCooldownSeconds");
        return seconds * 20;
    }

    /** 婚龄 (整数天; 负值钳为 0, 防 nowTick < marriedSinceTick 的极端时序)。 */
    public static long marriedDays(long marriedSinceTick, long nowTick) {
        long elapsed = nowTick - marriedSinceTick;
        if (elapsed < 0L) {
            return 0L;
        }
        return elapsed / TICKS_PER_DAY;
    }

    /** 当前再婚冷却剩余天数所需 tick (spec 第六章闸 1: base * (1 + divorceCount), 随离婚次数递增)。 */
    public static long remarryCooldownTicks(int divorceCount) {
        long baseDays = MiningServerConfig.MARRIAGE_REMARRY_COOLDOWN_DAYS.get();
        long effectiveDays = baseDays * (1L + Math.max(0, divorceCount));
        return effectiveDays * TICKS_PER_DAY;
    }

    /** 由婚龄天数与解锁阈值列表派生等级: 从高级向低级找首个"婚龄 >= 该级阈值"的级。 */
    private static int levelForDays(long days, List<? extends Integer> unlockDays) {
        if (unlockDays.isEmpty()) {
            throw new IllegalStateException("marriage backpackUnlockDays config must not be empty");
        }
        int maxIndex = Math.min(unlockDays.size(), MAX_LEVEL) - 1;
        for (int idx = maxIndex; idx >= 0; idx--) {
            if (days >= unlockDays.get(idx)) {
                return idx + 1;
            }
        }
        // 婚龄不足第 1 级阈值 (理论上首项应为 0): 仍给 1 级 (1 级永远可用, spec 第四章)。
        return 1;
    }

    /**
     * 从某分级数值列表取第 level 级的值 (level 1..MAX_LEVEL, 取 list[level-1])。level 越界钳到 [1,size];
     * list 比 MAX_LEVEL 短时取最后一项兜底 (不静默给 0)。空列表属配置缺陷, 自然抛。
     */
    private static int clampPick(List<? extends Integer> list, int level, String which) {
        if (list.isEmpty()) {
            throw new IllegalStateException("marriage config list '" + which + "' must not be empty");
        }
        int clampedLevel = Math.max(1, Math.min(level, MAX_LEVEL));
        int idx = Math.min(clampedLevel - 1, list.size() - 1);
        return list.get(idx);
    }
}
