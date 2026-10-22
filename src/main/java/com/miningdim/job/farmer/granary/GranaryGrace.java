package com.miningdim.job.farmer.granary;

/**
 * 损坏宽限期的算术 (Spoilage_Granary_DesignSpec「损坏与宽限」)。
 *
 * 宽限期 = 「出事那一刻起 7 天」与「该囤主出事后第一次上线起 3 天」**取较晚的那个**, 最长 28 天。
 * 三组数字都有理由, 别顺手改:
 * <ul>
 *   <li><b>7 天</b>: 天天上线的人也有一整个周末可以修;</li>
 *   <li><b>首次上线后 3 天</b>: 考试周一整周不来的人, 回来那天才是他知道的时候, 从那天起算 3 天;</li>
 *   <li><b>28 天上限</b>: 一直不上线也不能把宽限拖到无限 —— 每件东西本来就最多停 21 天, 所以拖过 28 天
 *       没有意义 (该烂的已经烂了)。</li>
 * </ul>
 *
 * 纯算术: 时间一律是小时序号 (与停表同一把挂钟), 不读世界。{@code firstLoginAfterHour <= 0} 表示
 * 「出事后还没上线过」。
 */
public final class GranaryGrace {

    /** 出事起算的宽限 (7 天)。 */
    public static final long DAMAGE_HOURS = 7L * 24L;

    /** 出事之后第一次上线起算的宽限 (3 天)。 */
    public static final long LOGIN_HOURS = 3L * 24L;

    /** 宽限期上限 (28 天)。 */
    public static final long MAX_HOURS = 28L * 24L;

    private GranaryGrace() {
    }

    /**
     * 宽限到哪一刻为止。
     *
     * @param damagedAtHour        出事时刻 (小时序号, 必须 > 0)
     * @param firstLoginAfterHour  该囤主出事后第一次上线的时刻 (0 = 还没上线过)
     */
    public static long deadline(long damagedAtHour, long firstLoginAfterHour) {
        if (damagedAtHour <= 0L) {
            throw new IllegalArgumentException("damagedAtHour must be > 0, got " + damagedAtHour);
        }
        long base = damagedAtHour + DAMAGE_HOURS;
        long afterLogin = firstLoginAfterHour > 0L ? firstLoginAfterHour + LOGIN_HOURS : 0L;
        return Math.min(Math.max(base, afterLogin), damagedAtHour + MAX_HOURS);
    }

    /** 到这一刻为止还在宽限期内 (截止那一刻算过期: 停表从这里停)。 */
    public static boolean inGrace(long nowHour, long damagedAtHour, long firstLoginAfterHour) {
        return nowHour < deadline(damagedAtHour, firstLoginAfterHour);
    }

    /** 还剩多少小时 (已过期返回 0)。 */
    public static long remainingHours(long nowHour, long damagedAtHour, long firstLoginAfterHour) {
        return Math.max(0L, deadline(damagedAtHour, firstLoginAfterHour) - nowHour);
    }
}
