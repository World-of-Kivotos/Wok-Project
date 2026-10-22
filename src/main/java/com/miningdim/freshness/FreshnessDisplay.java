package com.miningdim.freshness;

/**
 * 客户端显示用的纯算术与文案: 剩余时间标签、颜色阈值和快捷栏提醒门槛。
 *
 * tooltip 与快捷栏提醒共用时间和颜色判定，规则集中在此处并可直接断言。
 */
public final class FreshnessDisplay {

    private FreshnessDisplay() {
    }

    /** 颜色阈值 (小时): 剩余 ≤ 24 转黄, ≤ 6 转红; 与 tooltip 共用同一份常量。 */
    public static final int WARN_HOURS = 24;
    public static final int DANGER_HOURS = 6;

    /** 快捷栏提醒的门槛 (小时): 有东西在这么多小时内会烂就提醒一次。 */
    public static final int REMIND_WITHIN_HOURS = 1;

    /**
     * 角落那行数字 (半尺寸字体): {@code 5h} / {@code 2d} / {@code <1h} / {@code 已腐烂}。
     *
     * 一天以上按天 (省地方), 一小时以内给"&lt;1h"而不是"0h" (0h 会被读成"已经没了")。
     */
    public static String label(long remainingMinutes) {
        if (remainingMinutes <= 0L) {
            return "rot";
        }
        if (remainingMinutes < 60L) {
            return "<1h";
        }
        long hours = remainingMinutes / 60L;
        if (hours < 24L) {
            return hours + "h";
        }
        return (hours / 24L) + "d";
    }

    /** 颜色档位 (客户端据此选颜色; 这里只出档位, 免得把颜色常量塞进纯逻辑)。 */
    public enum Level {
        /** 剩余 > 24 小时。 */
        FRESH,
        /** 剩余 ≤ 24 小时。 */
        WARN,
        /** 剩余 ≤ 6 小时。 */
        DANGER,
        /** 已经腐烂。 */
        ROTTEN,
        /** 不在任何易腐类别里 (石头、工具)。 */
        UNTIMED
    }

    /** 一件东西现在处于哪一档 (剩余分钟数; {@code untimed} 为真时无关剩余)。 */
    public static Level level(long remainingMinutes, boolean untimed) {
        if (untimed) {
            return Level.UNTIMED;
        }
        if (remainingMinutes <= 0L) {
            return Level.ROTTEN;
        }
        long hours = remainingMinutes / 60L;
        if (hours <= DANGER_HOURS) {
            return Level.DANGER;
        }
        if (hours <= WARN_HOURS) {
            return Level.WARN;
        }
        return Level.FRESH;
    }

}
