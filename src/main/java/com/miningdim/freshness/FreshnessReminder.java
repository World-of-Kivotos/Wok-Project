package com.miningdim.freshness;

/**
 * 快捷栏提醒的判据 (规格「客户端显示」: 客户端每 20 tick 扫一遍自己背包, 有东西 1 小时内要烂就在快捷栏上方
 * 显示一次, **每小时最多一次**)。
 *
 * 拆成纯函数是为了能直接断言"频率": 每 tick 都提醒与每小时最多一次, 在玩家体感上完全是两个东西, 而扫背包
 * 那一段 (读 ItemStack) 放在客户端那个类里。
 *
 * 状态只有两个数: 上次提醒的分钟序号、上次检查的分钟序号 (由调用方持有, 本类不存状态)。
 */
public final class FreshnessReminder {

    private FreshnessReminder() {
    }

    /** 检查间隔 (tick): 规格给的 20。 */
    public static final int CHECK_INTERVAL_TICKS = 20;

    /** 冷却 (分钟): 规格给的"每小时最多一次"。 */
    public static final long COOLDOWN_MINUTES = 60L;

    /** 距离上次提醒满冷却了吗 (没提醒过时 {@code lastWarnMinute} 传 {@link Long#MIN_VALUE})。 */
    public static boolean cooldownElapsed(long nowMinute, long lastWarnMinute) {
        return lastWarnMinute == Long.MIN_VALUE || nowMinute - lastWarnMinute >= COOLDOWN_MINUTES;
    }

    /** 该不该现在提醒: 有东西快烂了 + 冷却已过。 */
    public static boolean shouldWarn(boolean hasExpiringSoon, long nowMinute, long lastWarnMinute) {
        return hasExpiringSoon && cooldownElapsed(nowMinute, lastWarnMinute);
    }

    /**
     * 一件东西是不是"快烂了": 剩余不足 {@link FreshnessDisplay#REMIND_WITHIN_HOURS} 小时**且还没烂**
     * (已经烂了的东西另有结算那条路处理, 提醒它没有意义)。
     */
    public static boolean isExpiringSoon(long remainingMinutes, boolean untimed) {
        return !untimed && remainingMinutes > 0L
                && remainingMinutes <= (long) FreshnessDisplay.REMIND_WITHIN_HOURS * 60L;
    }

    /** 提醒文案里的语言键 (客户端显示在快捷栏上方)。 */
    public static final String KEY_REMINDER = "message.miningdim.freshness.reminder";

    /** 到了该扫背包的那一 tick 吗。 */
    public static boolean isCheckTick(long gameTime) {
        return gameTime % CHECK_INTERVAL_TICKS == 0L;
    }
}
