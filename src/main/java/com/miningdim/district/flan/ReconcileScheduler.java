package com.miningdim.district.flan;

import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.store.DistrictRepository;
import com.miningdim.district.store.DistrictStoreException;
import com.miningdim.store.MiningStoreException;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 对账的节拍与时间切片 (设计文档 20.4):
 * <ul>
 *   <li>开服跑一轮 ({@link #request}, 由 DistrictSystem 在 ServerStarted 的开服备份、老玩家回填、到期收回之后调);</li>
 *   <li>之后每 {@link #INTERVAL_TICKS} tick (5 分钟) 起一轮;</li>
 *   <li>权限表重读后 id 集合变了, 从下一 tick 起一轮 (并记一条 INFO 列出增减);</li>
 *   <li>同一时间至多一轮; 每 tick 限时 {@link #SLICE_NANOS} (至少处理一个条目), 游标记住做到了哪里;</li>
 *   <li>一轮之中写入连续失败 {@link #MAX_CONSECUTIVE_FAILURES} 次就中止本轮, 记一次 ERROR (Flan 整体坏掉时不刷屏)。</li>
 * </ul>
 * 只在功能 LIVE 时由 DistrictSystem 驱动; 本类不看全局状态, 调用方传入这一 tick 用的对账器。
 */
public final class ReconcileScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    /** 定时对账的间隔: 6000 tick = 5 分钟。 */
    public static final int INTERVAL_TICKS = 6000;
    /** 每 tick 的时间预算。 */
    public static final long SLICE_NANOS = 2_000_000L;
    /** 连续这么多次写失败就中止本轮。 */
    public static final int MAX_CONSECUTIVE_FAILURES = 20;
    /** 每隔几 tick 看一次权限表有没有被重读 (一次引用比较, 很便宜, 但没必要每 tick 都做)。 */
    static final int TABLE_CHECK_TICKS = 20;
    /** 中止原因: 连续写失败。 */
    public static final String ABORT_FAILURES = "consecutive failures";
    /** 中止原因: 数据库出错 (库锁着或坏了)。 */
    public static final String ABORT_DATABASE = "database error";

    /**
     * 上一轮的统计 (/district status 显示)。
     *
     * @param failures    写失败的条目数, 加上抛了异常的条目数
     * @param abortReason 中止的原因 ("consecutive failures" / "database error"); 没中止为 null
     */
    public record RoundStats(long startedAt, long durationMs, int slices, int districts, int reverted, int unfixable,
                             int failures, boolean aborted, @Nullable String abortReason) {
    }

    /** 一个条目: 父领地 (plotId 为 null) 或一块地。 */
    private record Entry(String districtId, @Nullable String plotId) {
    }

    private final LongSupplier wallClock;
    private final LongSupplier nanoClock;
    @Nullable
    private Deque<Entry> queue;
    @Nullable
    private DistrictReconciler.DistrictProgress current;
    private final List<DistrictReconciler.DistrictProgress> finished = new ArrayList<>();
    private long roundStartedAt;
    private int roundSlices;
    private int consecutiveFailures;
    /** 本轮抛了异常的条目数 (不在任何 DistrictProgress 的计数里)。 */
    private int roundFailures;
    /** 本轮是否已经带堆栈记过一次条目异常。 */
    private boolean loggedThisRound;
    private boolean requested;
    private int nextRoundTick = -1;
    @Nullable
    private RoundStats lastRound;
    private long seenTableVersion = Long.MIN_VALUE;
    @Nullable
    private Set<String> seenTableIds;

    public ReconcileScheduler(LongSupplier wallClock, LongSupplier nanoClock) {
        this.wallClock = wallClock;
        this.nanoClock = nanoClock;
    }

    /** 尽快起一轮 (下一 tick; 已有一轮在跑时等它结束)。 */
    public synchronized void request() {
        requested = true;
    }

    @Nullable
    public synchronized RoundStats lastRound() {
        return lastRound;
    }

    public synchronized boolean running() {
        return queue != null;
    }

    /**
     * 一 tick: 看权限表有没有被重读; 到点或被请求时起一轮; 有一轮在跑就按时间预算推进。
     *
     * @param tickCount  服务器 tick 计数
     * @param reconciler 这一 tick 用的对账器 (取自当前绑定的 context)
     */
    public synchronized void tick(int tickCount, DistrictReconciler reconciler, DistrictRepository repo,
                                  FlanGateway gateway) {
        if (tickCount % TABLE_CHECK_TICKS == 0) {
            checkPermissionTable(gateway);
        }
        if (queue == null) {
            if (nextRoundTick < 0) {
                nextRoundTick = tickCount + INTERVAL_TICKS;
            }
            if (!requested && tickCount < nextRoundTick) {
                return;
            }
            requested = false;
            nextRoundTick = tickCount + INTERVAL_TICKS;
            startRound(repo);
        }
        runSlice(reconciler, SLICE_NANOS);
    }

    /** 同步跑完整一轮 (测试与开服之外的显式入口), 不经时间切片。 */
    public synchronized RoundStats runFullRoundNow(DistrictReconciler reconciler, DistrictRepository repo,
                                                    FlanGateway gateway) {
        checkPermissionTable(gateway);
        if (queue == null) {
            startRound(repo);
        }
        while (queue != null) {
            runSlice(reconciler, Long.MAX_VALUE);
        }
        requested = false;
        return lastRound;
    }

    /**
     * 权限表被重读 (版本变了) 时: id 集合有增减就记一条 INFO 并安排一轮完整对账; 同时列出表外的未知 id。开服后
     * 第一次调用只记未知 id, 不算"变了"。
     */
    void checkPermissionTable(FlanGateway gateway) {
        long version = gateway.permissionTableVersion();
        if (version == seenTableVersion) {
            return;
        }
        seenTableVersion = version;
        List<KnownPermission> table = gateway.permissionTable();
        Set<String> ids = new LinkedHashSet<>();
        table.forEach(permission -> ids.add(permission.id()));
        List<String> unknown = FlanPermissionPolicy.of(table).unknownIds();
        if (!unknown.isEmpty()) {
            LOGGER.info("[miningdim] district: Flan permissions outside the district table (owner group true, every "
                    + "other position false): {}", unknown);
        }
        if (seenTableIds != null && !seenTableIds.equals(ids)) {
            Set<String> added = new LinkedHashSet<>(ids);
            added.removeAll(seenTableIds);
            Set<String> removed = new LinkedHashSet<>(seenTableIds);
            removed.removeAll(ids);
            LOGGER.info("[miningdim] district: the Flan permission table was reloaded (added {}, removed {}); "
                    + "a full reconciliation starts next tick", added, removed);
            requested = true;
        }
        seenTableIds = ids;
    }

    private void startRound(DistrictRepository repo) {
        Deque<Entry> entries = new ArrayDeque<>();
        for (DistrictRecord district : repo.liveDistricts()) {
            entries.add(new Entry(district.districtId(), null));
        }
        queue = entries;
        current = null;
        finished.clear();
        roundStartedAt = wallClock.getAsLong();
        roundSlices = 0;
        consecutiveFailures = 0;
        roundFailures = 0;
        loggedThisRound = false;
    }

    private void runSlice(DistrictReconciler reconciler, long budgetNanos) {
        if (queue == null) {
            return;
        }
        roundSlices++;
        long start = nanoClock.getAsLong();
        boolean first = true;
        while (!queue.isEmpty()) {
            if (!first && nanoClock.getAsLong() - start >= budgetNanos) {
                return;
            }
            first = false;
            Entry entry = queue.poll();
            int failuresBefore = current == null ? 0 : current.failures();
            int thrown = 0;
            try {
                if (entry.plotId() == null) {
                    closeCurrent(reconciler);
                    failuresBefore = 0;
                    current = reconciler.startDistrict(entry.districtId(), DistrictReconciler.Mode.AUTO);
                    if (!current.skipped()) {
                        // 地块条目排在它的父领地之后、下一个区之前。
                        List<String> plots = current.plotIds();
                        for (int i = plots.size() - 1; i >= 0; i--) {
                            queue.addFirst(new Entry(entry.districtId(), plots.get(i)));
                        }
                    }
                } else if (current != null && current.districtId().equals(entry.districtId())) {
                    reconciler.reconcilePlot(current, entry.plotId());
                }
            } catch (DistrictStoreException | MiningStoreException database) {
                // 库锁着或坏了: 后面的条目多半一样 (每条还可能在 busy_timeout 上卡 5 秒), 整轮中止, 等下一次定时。
                logEntryFailure(entry, database);
                roundFailures++;
                LOGGER.error("[miningdim] district reconciliation aborted: database error; the next round starts on "
                        + "schedule");
                endRound(reconciler, true, ABORT_DATABASE);
                return;
            } catch (RuntimeException failure) {
                // 一个条目出错不能让整轮每 tick 抛一次: 记成失败, 接着做下一个条目。
                logEntryFailure(entry, failure);
                roundFailures++;
                thrown = 1;
            }
            int failuresNow = (current == null ? 0 : current.failures()) + thrown;
            if (failuresNow > failuresBefore) {
                consecutiveFailures += failuresNow - failuresBefore;
            } else {
                consecutiveFailures = 0;
            }
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                LOGGER.error("[miningdim] district reconciliation aborted after {} consecutive Flan write failures; "
                        + "the next round starts on schedule", consecutiveFailures);
                endRound(reconciler, true, ABORT_FAILURES);
                return;
            }
        }
        endRound(reconciler, false, null);
    }

    /** 一个条目抛出的异常: 一轮之中只有第一个带堆栈记 ERROR, 其余一行 DEBUG。 */
    private void logEntryFailure(Entry entry, RuntimeException failure) {
        String what = entry.plotId() == null ? "district " + entry.districtId()
                : "plot " + entry.plotId() + " of " + entry.districtId();
        if (!loggedThisRound) {
            loggedThisRound = true;
            LOGGER.error("[miningdim] district reconciliation of {} threw; counted as a failure (further errors in "
                    + "this round are logged at DEBUG)", what, failure);
        } else {
            LOGGER.debug("[miningdim] district reconciliation of {} threw {}", what, failure.toString());
        }
    }

    private void closeCurrent(DistrictReconciler reconciler) {
        if (current != null) {
            reconciler.finishDistrict(current);
            finished.add(current);
            current = null;
        }
    }

    private void endRound(DistrictReconciler reconciler, boolean aborted, @Nullable String abortReason) {
        if (aborted) {
            if (current != null) {
                finished.add(current);
                current = null;
            }
        } else {
            try {
                closeCurrent(reconciler);
            } catch (RuntimeException failure) {
                // 收尾 (清对账标记) 出错: 标记留着, 下一轮再清; 这一轮照常结束。
                LOGGER.error("[miningdim] district reconciliation could not finish {}", current == null ? "?"
                        : current.districtId(), failure);
                current = null;
                roundFailures++;
            }
        }
        int reverted = 0;
        int unfixable = 0;
        int failures = roundFailures;
        for (DistrictReconciler.DistrictProgress progress : finished) {
            reverted += progress.reverted();
            unfixable += progress.unfixable().size();
            failures += progress.failures();
        }
        lastRound = new RoundStats(roundStartedAt, Math.max(0, wallClock.getAsLong() - roundStartedAt), roundSlices,
                finished.size(), reverted, unfixable, failures, aborted, abortReason);
        queue = null;
        finished.clear();
        if (reverted > 0 || unfixable > 0) {
            LOGGER.info("[miningdim] district reconciliation round: {} district(s), {} reverted, {} unfixable, {} "
                    + "failure(s), {} slice(s)", lastRound.districts(), reverted, unfixable, failures, roundSlices);
        }
    }
}
