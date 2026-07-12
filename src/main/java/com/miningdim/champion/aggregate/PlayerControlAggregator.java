package com.miningdim.champion.aggregate;

import com.miningdim.champion.ChampionRedlines;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 每玩家控制聚合层纯逻辑 (ChampionStarAffix spec 红线 5 / 9.5 / 第十四章实现拆分 4)。
 *
 * 同一玩家身上来自所有来源的失明/击飞/位移进入统一递减: 任意 7s 滚动窗内受控总时长 ≤50%, 且必须存在连续
 * ≥2s 完全自由窗 (可瞄准/开火/移动)。超出部分作废不入队 (spec 红线 5)。减速总量另由
 * {@link #clampSlow(double)} 硬封顶 ≤50% (绝不定身)。
 *
 * 本类是单玩家有状态聚合器 (服务端 tick 串行, 非线程安全): 控制类词条拟施加一段控制时, 调
 * {@link #admit(long, long)} 申请 —— 返回"实际可施加的控制时长 (被 7s 窗 50% 上限夹断后的值)"; 超额作废返 0。
 * 以 tick 记录受控区间, 滚动窗内累计受控 tick 受 50% 占比上限约束。不碰世界/实体, GameTest 直接断言。
 *
 * 自由窗校验 {@link #hasMinFreeWindow(long)}: 给定窗内是否存在连续 ≥2s 无控制的空隙 (红线 5 硬要求)。
 */
public final class PlayerControlAggregator {

    private static final long TICKS_PER_SECOND = 20L;

    /** 7s 滚动窗 tick 数。 */
    public static final long WINDOW_TICKS =
            (long) (ChampionRedlines.CONTROL_ROLLING_WINDOW_SECONDS * TICKS_PER_SECOND);

    /** 窗内受控 tick 上限 (= 50% × 窗 = 70 tick)。 */
    public static final long BUSY_TICK_CAP =
            (long) (WINDOW_TICKS * ChampionRedlines.CONTROL_WINDOW_BUSY_RATIO_CAP);

    /** 连续自由窗最小 tick 数 (= 2s = 40 tick)。 */
    public static final long MIN_FREE_WINDOW_TICKS =
            (long) (ChampionRedlines.CONTROL_MIN_FREE_WINDOW_SECONDS * TICKS_PER_SECOND);

    /** 已确认入队的受控区间 [startTick, endTick) (按 startTick 升序; 旧区间在滚动窗外被裁剪)。 */
    private final Deque<long[]> controlledIntervals = new ArrayDeque<>();

    /**
     * 申请施加一段控制, 返回经 7s 窗 50% 上限夹断后实际可施加的控制 tick (≥0; 0 = 额度耗尽超额作废)。
     * 多控制源对同一玩家共享同一聚合器即实现"所有来源统一递减"(spec 红线 5)。
     *
     * @param startTick    控制起始 gameTime tick
     * @param requestTicks 拟施加的控制时长 tick (必须 &gt;=0)
     * @return 实际可施加的控制 tick (被窗内 50% 上限夹断; 超额作废)
     */
    public long admit(long startTick, long requestTicks) {
        if (requestTicks < 0L) {
            throw new IllegalArgumentException("requestTicks must be >= 0, got " + requestTicks);
        }
        pruneOlderThan(startTick - WINDOW_TICKS);

        // 以申请起点回看一个完整 7s 窗内已确认受控 tick, 本次可施加额度 = 50% 窗上限 - 已受控; 超额作废。
        long alreadyBusy = busyTicksInWindow(startTick - WINDOW_TICKS + 1, startTick);
        long avail = Math.max(0L, BUSY_TICK_CAP - alreadyBusy);
        long granted = Math.min(requestTicks, avail);

        if (granted > 0L) {
            long[] candidate = new long[]{startTick, startTick + granted};
            controlledIntervals.addLast(candidate);
            // 红线 5 "连续 ≥2s 自由窗"硬要求落地 (对抗审查发现 hasMinFreeWindow 曾是无人调用的死代码): 以新区间
            // 末端锚定的 7s 窗复核, 授予后窗内不再存在 ≥2s 自由空隙则整笔作废回退 —— 两只控制精英交替施控可在
            // 不超 50% 占比的前提下抹掉全部自由窗, 占比帽单独挡不住碎片化永控。红线宁可丢一次控制不可永控。
            if (!hasMinFreeWindow(candidate[1] - WINDOW_TICKS)) {
                controlledIntervals.removeLast();
                return 0L;
            }
        }
        return granted;
    }

    /**
     * 给定 7s 窗 [windowStartTick, windowStartTick+WINDOW_TICKS) 内是否存在连续 ≥2s 自由窗 (红线 5 硬要求)。
     * 扫描该窗内已确认受控区间之间的空隙, 任一空隙 ≥ MIN_FREE_WINDOW_TICKS 即满足。
     *
     * @param windowStartTick 窗起始 tick
     * @return 是否存在连续 ≥2s 自由空隙
     */
    public boolean hasMinFreeWindow(long windowStartTick) {
        long windowEnd = windowStartTick + WINDOW_TICKS;
        long cursor = windowStartTick;
        // 区间按 startTick 升序遍历, 累计空隙。
        for (long[] iv : controlledIntervals) {
            long s = Math.max(iv[0], windowStartTick);
            long e = Math.min(iv[1], windowEnd);
            if (e <= s) {
                continue; // 区间在窗外。
            }
            if (s - cursor >= MIN_FREE_WINDOW_TICKS) {
                return true;
            }
            if (e > cursor) {
                cursor = e;
            }
        }
        return windowEnd - cursor >= MIN_FREE_WINDOW_TICKS;
    }

    /**
     * 减速总量硬封顶 (spec 红线 5: ≤50%, 绝不定身)。多源减速合计后夹到 50%。纯函数。
     *
     * @param totalSlowPct 多源累加的减速总量 (0-1+; 负数抛 IllegalArgumentException)
     * @return 夹到 ≤50% 的减速量
     */
    public static double clampSlow(double totalSlowPct) {
        if (totalSlowPct < 0.0D || Double.isNaN(totalSlowPct)) {
            throw new IllegalArgumentException("totalSlowPct must be >= 0, got " + totalSlowPct);
        }
        return Math.min(totalSlowPct, ChampionRedlines.SLOW_TOTAL_CAP_PCT);
    }

    /** 窗 [from, to) 内已确认受控 tick 合计 (区间求交)。 */
    private long busyTicksInWindow(long from, long to) {
        long busy = 0L;
        for (long[] iv : controlledIntervals) {
            long s = Math.max(iv[0], from);
            long e = Math.min(iv[1], to);
            if (e > s) {
                busy += (e - s);
            }
        }
        return busy;
    }

    /** 裁剪起点早于 cutoff 的区间 (滚动窗外, 不再参与累计)。 */
    private void pruneOlderThan(long cutoff) {
        while (!controlledIntervals.isEmpty() && controlledIntervals.peekFirst()[1] <= cutoff) {
            controlledIntervals.pollFirst();
        }
    }

    /** 当前已入队受控区间数 (诊断/测试用)。 */
    public int intervalCount() {
        return controlledIntervals.size();
    }
}
