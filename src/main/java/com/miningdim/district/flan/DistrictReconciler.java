package com.miningdim.district.flan;

import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.core.TombstoneRecord;
import com.miningdim.district.store.DistrictRepository;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 对账 (设计文档 20.4): 比较数据库 (期望) 与 Flan (现状), 按差异写回, 记下"有人用了 /flan"的痕迹。
 *
 * <p>每个区是一串条目: 先是父领地 ({@link #startDistrict}), 然后每块地一个 ({@link #reconcilePlot}), 最后
 * {@link #finishDistrict}。{@link ReconcileScheduler} 按时间切片逐条推进; 命令与测试用 {@link #reconcileDistrict}
 * 同步跑完一个区。每个条目在被处理的那一刻才从库里读期望状态, 两个 tick 之间有人改了地块也不会拿旧数据去写。
 *
 * <p>三种模式:
 * <ul>
 *   <li>AUTO (开服、定时、权限表变了): 写回; 本模块留下的孤儿子领地 (有墓碑) 删掉; 外来子领地与没有墓碑的本模块子领地只报告;</li>
 *   <li>EXPLICIT (/district resync): 先强制备份; 外来的与没有墓碑的本模块子领地也删;</li>
 *   <li>DRY_RUN (/district inspect): 一个字都不写, 只列出差异。</li>
 * </ul>
 * 从不碰没有绑定的领地: 只从库里的在用区出发, 经库里记着的领地 id 找到父领地, 只看这块父领地的子领地。
 */
public final class DistrictReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    /** 日志里一块领地最多列几项改动, 其余只计数。 */
    static final int LOG_CHANGES = 10;

    public enum Mode {
        AUTO,
        EXPLICIT,
        DRY_RUN
    }

    /** 一个区这一轮的进度与结果。 */
    public static final class DistrictProgress {
        private final String districtId;
        private final Mode mode;
        @Nullable
        private DistrictRecord district;
        @Nullable
        private ClaimHandle claim;
        @Nullable
        private ClaimInfo claimInfo;
        private final List<String> plotIds = new ArrayList<>();
        private final List<String> changes = new ArrayList<>();
        private final List<String> unfixable = new ArrayList<>();
        private final List<String> removedSubclaims = new ArrayList<>();
        private final List<String> reportedSubclaims = new ArrayList<>();
        private int reverted;
        private int failures;
        private int succeeded;
        private int failed;
        private boolean skipped;

        private DistrictProgress(String districtId, Mode mode) {
            this.districtId = districtId;
            this.mode = mode;
        }

        public String districtId() {
            return districtId;
        }

        public Mode mode() {
            return mode;
        }

        /** 按顺序等着对的地块。 */
        public List<String> plotIds() {
            return List.copyOf(plotIds);
        }

        /** 改回 (DRY_RUN 时为"将改") 的每一项。 */
        public List<String> changes() {
            return List.copyOf(changes);
        }

        public int reverted() {
            return reverted;
        }

        /** 修不了、只报告的差异。 */
        public List<String> unfixable() {
            return List.copyOf(unfixable);
        }

        /** 删掉 (DRY_RUN 时为"将删") 的子领地。 */
        public List<String> removedSubclaims() {
            return List.copyOf(removedSubclaims);
        }

        /** 只报告、没删的子领地。 */
        public List<String> reportedSubclaims() {
            return List.copyOf(reportedSubclaims);
        }

        /** 写失败的条目数。 */
        public int failures() {
            return failures;
        }

        /** resync 的计数: 父领地 1 项 + 名单里 synced / failed 的住户 + 地块 (按最终的生效状态)。 */
        public int succeeded() {
            return succeeded;
        }

        public int failed() {
            return failed;
        }

        /** 父领地找不到等原因, 本区其余条目跳过。 */
        public boolean skipped() {
            return skipped;
        }
    }

    private final DistrictFlanSync sync;
    private final DistrictRepository repo;
    private final FlanGateway gateway;
    private final LogThrottle throttle;

    public DistrictReconciler(DistrictFlanSync sync, LogThrottle throttle) {
        this.sync = sync;
        this.repo = sync.repo();
        this.gateway = sync.gateway();
        this.throttle = throttle;
    }

    /** 同步跑完一个区 (命令与测试)。EXPLICIT 模式先强制备份。 */
    public DistrictProgress reconcileDistrict(String districtId, Mode mode) {
        DistrictProgress progress = startDistrict(districtId, mode);
        if (!progress.skipped) {
            for (String plotId : progress.plotIds()) {
                reconcilePlot(progress, plotId);
            }
        }
        finishDistrict(progress);
        return progress;
    }

    // ================================================================
    // 父领地条目
    // ================================================================

    /** 父领地条目: 找父领地、报告修不了的差异、按差异写父领地、给子领地分类 (认领 / 删孤儿 / 报告外来的)。 */
    public DistrictProgress startDistrict(String districtId, Mode mode) {
        DistrictProgress progress = new DistrictProgress(districtId, mode);
        Optional<DistrictRecord> found = repo.liveDistrict(districtId);
        if (found.isEmpty()) {
            progress.skipped = true;
            return progress;
        }
        DistrictRecord district = found.get();
        progress.district = district;
        String dimension = district.bounds().dimension();
        String unreachable = sync.unreachable(dimension);
        if (unreachable != null) {
            // 降级、熔断或维度没加载: 查询一律为空, 不能据此说"父领地找不到了", 也不置对账标记。
            progress.skipped = true;
            if (mode != Mode.DRY_RUN) {
                progress.failures++;
                progress.failed++;
            }
            unfixable(progress, "unreachable", unreachable);
            return progress;
        }
        String problem = mode == Mode.DRY_RUN ? null : sync.permissionProblem();
        if (problem != null) {
            progress.skipped = true;
            progress.failures++;
            progress.unfixable.add(problem);
            return progress;
        }
        if (mode == Mode.EXPLICIT) {
            FlanResult<String> backup = gateway.backupNow(dimension, "resync");
            if (!backup.ok()) {
                // 没有备份就一格都不写: 父领地、名单里 synced / failed 的住户、每块地都如实计作失败。
                progress.skipped = true;
                progress.failures++;
                progress.failed += 1 + (int) repo.membersOf(district.academyId()).stream()
                        .filter(member -> member.syncStatus() != ResidentSyncStatus.PENDING).count()
                        + repo.plotsOf(districtId).size();
                progress.unfixable.add(DistrictFlanSync.errorOf(backup));
                return progress;
            }
        }

        if (district.flanClaimId() == null) {
            if (mode == Mode.DRY_RUN) {
                progress.changes.add("district claim not created yet; the next write creates it at "
                        + district.bounds());
                progress.skipped = true;
                return progress;
            }
            FlanResult<ClaimHandle> created = sync.ensureDistrictClaim(district);
            if (!created.ok()) {
                progress.skipped = true;
                progress.failures++;
                progress.failed++;
                unfixable(progress, "claim-create", "creating the district claim failed: " + created.error());
                return progress;
            }
            district = repo.liveDistrict(districtId).orElse(district);
            progress.district = district;
        }

        Optional<ClaimHandle> claim = gateway.findDistrictClaim(dimension, district.flanClaimId());
        if (claim.isEmpty()) {
            progress.skipped = true;
            progress.failed++;
            if (mode != Mode.DRY_RUN) {
                repo.markNeedsReconcile(districtId);
            }
            unfixable(progress, "claim-missing", "district claim " + district.flanClaimId()
                    + " is missing in Flan; not recreating it on my own (after checking: /district claim " + districtId
                    + " recreate, or /district claim " + districtId + " relink <pos> if the land still has its admin "
                    + "claim under another id)");
            return progress;
        }
        ClaimHandle handle = claim.get();
        progress.claim = handle;
        Optional<ClaimInfo> info = gateway.inspectClaim(handle);
        if (info.isPresent()) {
            progress.claimInfo = info.get();
            reportDistrictGeometry(progress, district, info.get());
        }

        // 父领地的组、默认、名字、杂项与成员。
        if (mode == Mode.DRY_RUN) {
            ClaimDesiredState desired = DistrictDesiredState.compute(sync.policy(), district,
                    repo.districtCells(districtId), repo.membersOf(district.academyId()));
            sync.planDistrict(desired, handle, true).forEach(op -> progress.changes.add("district: " + op.describe()));
        } else {
            DistrictFlanSync.ApplyOutcome outcome = sync.syncDistrictClaim(district, handle, true);
            recordOutcome(progress, "district", outcome);
            if (outcome.ok()) {
                progress.succeeded++;
            } else {
                progress.failed++;
            }
            for (MemberRecord member : repo.membersOf(district.academyId())) {
                if (member.syncStatus() == ResidentSyncStatus.SYNCED) {
                    progress.succeeded++;
                } else if (member.syncStatus() == ResidentSyncStatus.FAILED) {
                    progress.failed++;
                }
            }
        }

        classifySubclaims(progress, district, handle);
        for (PlotRecord plot : repo.plotsOf(districtId)) {
            progress.plotIds.add(plot.plotId());
        }
        return progress;
    }

    private void reportDistrictGeometry(DistrictProgress progress, DistrictRecord district, ClaimInfo info) {
        String id = district.districtId();
        if (!info.adminClaim()) {
            String message = "district claim " + info.handle().claimId() + " is no longer an admin claim (someone used "
                    + "/flan setAdminClaim; its owner now bypasses everything in the whole district)";
            progress.unfixable.add(message);
            if (progress.mode != Mode.DRY_RUN && throttle.loud(id + "|not-admin")) {
                LOGGER.error("[miningdim] district {}: {}", id, message);
            }
        }
        if (!info.area().equals(district.bounds().asArea())) {
            unfixable(progress, "bounds", "district claim covers " + info.area() + " but the database says "
                    + district.bounds().asArea() + "; run /district bounds " + id + " sync after checking");
        }
        if (info.shallow()) {
            // Flan 只按顶层领地判定: 父领地不含的高度在每块地里都是野外。2D 的底能用 Flan 自己的 extendDownwards 补到底。
            FlanOp deepen = new FlanOp.ExtendToBottom(info.minY(), info.worldMinY());
            if (progress.mode == Mode.DRY_RUN) {
                progress.changes.add("district: " + deepen.describe());
                return;
            }
            FlanResult<Void> deepened = deepen.apply(gateway, info.handle());
            if (deepened.ok()) {
                progress.changes.add("district: " + deepen.describe());
                progress.reverted++;
                LOGGER.warn("[miningdim] district {}: the district claim only reached down to y {}; extended it to the "
                        + "world bottom {} (everything below was unprotected wilderness)", id, info.minY(),
                        info.worldMinY());
            } else {
                progress.failures++;
                unfixable(progress, "height", "extending the district claim down to the world bottom failed: "
                        + deepened.error());
            }
        } else if (!info.fullHeight()) {
            unfixable(progress, "height", "district claim is 3D (minY=" + info.minY() + ", world bottom "
                    + info.worldMinY() + "); everything outside its height range is unprotected wilderness in every "
                    + "plot; delete it with /flan and redraw it as a 2D claim, then /district claim " + id + " relink");
        }
    }

    /**
     * 父领地下每个子领地归哪一类: 库里某块地记着它的 id 的是本区地块; 带着 p_&lt;P&gt;_… 组键的是本模块建的 (P 是本区一块
     * 现存地块、而那块地记着的 id 在 Flan 里找不到 → 认领; P 有墓碑 → 孤儿, 删; 其余只报告, resync 才删); 其余是
     * 外来的 (只报告, resync 才删)。
     */
    private void classifySubclaims(DistrictProgress progress, DistrictRecord district, ClaimHandle districtClaim) {
        List<ClaimHandle> subclaims = gateway.listPlotClaims(districtClaim);
        if (subclaims.isEmpty()) {
            return;
        }
        List<PlotRecord> plots = repo.plotsOf(district.districtId());
        Map<UUID, PlotRecord> byClaim = new HashMap<>();
        Map<String, PlotRecord> byId = new HashMap<>();
        for (PlotRecord plot : plots) {
            byId.put(plot.plotId(), plot);
            if (plot.flanClaimId() != null) {
                byClaim.put(plot.flanClaimId(), plot);
            }
        }
        Set<UUID> present = new HashSet<>();
        subclaims.forEach(sub -> present.add(sub.claimId()));
        // 墓碑只在遇到"带本模块组名、库里又没有"的子领地时才读 (大区的墓碑可能很多, 平常每一轮都用不上)。
        Set<String> tombstoned = null;
        boolean orphanBackupDone = progress.mode == Mode.EXPLICIT;
        for (ClaimHandle sub : subclaims) {
            if (byClaim.containsKey(sub.claimId())) {
                continue;
            }
            Set<String> groups = gateway.readPermissions(sub).groups().keySet();
            String owner = modulePlotId(groups);
            String where = describeSubclaim(sub, groups);
            if (owner != null) {
                PlotRecord plot = byId.get(owner);
                if (plot != null && (plot.flanClaimId() == null || !present.contains(plot.flanClaimId()))) {
                    // 写库失败或存盘前崩溃留下的错位: 认领这块子领地, 不再新建一块。
                    progress.changes.add("plot " + owner + ": relinked to subclaim " + sub.claimId());
                    if (progress.mode != Mode.DRY_RUN) {
                        repo.setPlotClaimId(owner, sub.claimId());
                        byClaim.put(sub.claimId(), plot);
                        progress.reverted++;
                    }
                    continue;
                }
                if (tombstoned == null) {
                    tombstoned = new HashSet<>();
                    for (TombstoneRecord tombstone : repo.tombstones(district.districtId(), Integer.MAX_VALUE)) {
                        tombstoned.add(tombstone.plotId());
                    }
                }
                if (tombstoned.contains(owner) || progress.mode == Mode.EXPLICIT) {
                    if (!orphanBackupDone && progress.mode == Mode.AUTO) {
                        FlanResult<String> backup = gateway.backupNow(district.bounds().dimension(), "orphan");
                        if (!backup.ok()) {
                            progress.failures++;
                            unfixable(progress, "orphan-backup", "backup before deleting orphan subclaims failed: "
                                    + backup.error());
                            return;
                        }
                        orphanBackupDone = true;
                    }
                    removeSubclaim(progress, sub, "module subclaim of " + (tombstoned.contains(owner)
                            ? "deleted plot " : "unknown plot ") + owner + " " + where);
                    continue;
                }
                reportSubclaim(progress, "module subclaim of unknown plot " + owner + " " + where
                        + " (the database has neither the plot nor its tombstone; /district resync deletes it)");
                continue;
            }
            if (progress.mode == Mode.EXPLICIT) {
                removeSubclaim(progress, sub, "foreign subclaim " + where);
            } else {
                reportSubclaim(progress, "foreign subclaim " + where + " (made by hand; /district resync deletes it)");
            }
        }
    }

    private void removeSubclaim(DistrictProgress progress, ClaimHandle sub, String what) {
        progress.removedSubclaims.add(what);
        if (progress.mode == Mode.DRY_RUN) {
            return;
        }
        FlanResult<Void> deleted = gateway.deletePlotClaim(sub);
        if (deleted.ok()) {
            progress.reverted++;
            LOGGER.warn("[miningdim] district {}: deleted {}", progress.districtId, what);
        } else {
            progress.failures++;
            unfixable(progress, "delete-" + sub.claimId(), "deleting " + what + " failed: " + deleted.error());
        }
    }

    private void reportSubclaim(DistrictProgress progress, String what) {
        progress.reportedSubclaims.add(what);
        if (progress.mode != Mode.DRY_RUN && throttle.loud(progress.districtId + "|sub|" + what)) {
            LOGGER.warn("[miningdim] district {}: {}", progress.districtId, what);
        }
    }

    private String describeSubclaim(ClaimHandle sub, Set<String> groups) {
        Optional<ClaimInfo> info = gateway.inspectClaim(sub);
        int members = gateway.readMembers(sub).size();
        return sub.claimId() + " " + info.map(i -> i.area().toString()).orElse("?") + " groups " + groups
                + ", " + members + " member(s)";
    }

    /** 从组键里认出本模块的地块 id: p_&lt;plotId&gt;_owner|friend|resident。 */
    @Nullable
    static String modulePlotId(Set<String> groups) {
        for (String group : groups) {
            if (!group.startsWith(FlanGroupNames.PLOT_PREFIX)) {
                continue;
            }
            for (String suffix : List.of("_owner", "_friend", "_resident")) {
                if (group.endsWith(suffix) && group.length() > FlanGroupNames.PLOT_PREFIX.length() + suffix.length()) {
                    return group.substring(FlanGroupNames.PLOT_PREFIX.length(), group.length() - suffix.length());
                }
            }
        }
        return null;
    }

    // ================================================================
    // 地块条目
    // ================================================================

    /** 一块地: 按库整块对 (按差异写、先收后放), 回写生效状态; 冻结的地块按冻结状态对。 */
    public void reconcilePlot(DistrictProgress progress, String plotId) {
        if (progress.skipped || progress.district == null || progress.claim == null) {
            return;
        }
        Optional<PlotRecord> found = repo.plot(plotId);
        Optional<DistrictRecord> district = repo.liveDistrict(progress.districtId);
        if (found.isEmpty() || district.isEmpty()) {
            return;
        }
        PlotRecord plot = found.get();
        if (progress.claimInfo != null && !progress.claimInfo.area().contains(plot.area())) {
            // 父领地被金锄头缩小之后: 自动缩小地块会改动户主买下的地, 不做。
            unfixable(progress, "outside-" + plotId, "plot " + plot.code() + " " + plot.area()
                    + " lies outside the district claim " + progress.claimInfo.area()
                    + "; the part outside is not protected by Flan");
            progress.failed++;
            return;
        }
        if (progress.mode == Mode.DRY_RUN) {
            Optional<ClaimHandle> handle = plot.flanClaimId() == null
                    ? Optional.empty()
                    : gateway.findPlotClaim(progress.claim, plot.flanClaimId());
            if (handle.isEmpty()) {
                progress.changes.add("plot " + plot.code() + ": subclaim missing, would be created");
            } else {
                sync.planPlot(district.get(), plot, handle.get(), null)
                        .forEach(op -> progress.changes.add("plot " + plot.code() + ": " + op.describe()));
            }
            return;
        }
        DistrictFlanSync.ApplyOutcome outcome = sync.applyPlotState(district.get(), plot, null);
        sync.recordPlotOutcome(plot, outcome);
        recordOutcome(progress, "plot " + plot.code(), outcome);
        if (outcome.ok()) {
            progress.succeeded++;
        } else {
            progress.failed++;
        }
    }

    // ================================================================
    // 收尾
    // ================================================================

    /** 一个区整轮对完、且没有修不了的差异与写失败时, 清掉对账标记。 */
    public void finishDistrict(DistrictProgress progress) {
        if (progress.mode == Mode.DRY_RUN || progress.skipped) {
            return;
        }
        if (progress.unfixable.isEmpty() && progress.failures == 0 && progress.district != null
                && progress.district.needsReconcile()) {
            repo.clearNeedsReconcile(progress.districtId);
        }
    }

    // ================================================================
    // 内部
    // ================================================================

    private void recordOutcome(DistrictProgress progress, String target, DistrictFlanSync.ApplyOutcome outcome) {
        List<String> applied = new ArrayList<>();
        outcome.applied().forEach(op -> applied.add(op.describe()));
        progress.changes.addAll(applied.stream().map(line -> target + ": " + line).toList());
        progress.reverted += applied.size();
        if (!applied.isEmpty()) {
            List<String> shown = applied.subList(0, Math.min(LOG_CHANGES, applied.size()));
            String more = applied.size() > LOG_CHANGES ? "; and " + (applied.size() - LOG_CHANGES) + " more" : "";
            LOGGER.warn("[miningdim] district {}: reverted Flan drift on {} ({} change(s)): {}{}", progress.districtId,
                    target, applied.size(), String.join("; ", shown), more);
        }
        if (!outcome.ok()) {
            progress.failures++;
            unfixable(progress, "write-" + target, "writing " + target + " failed: " + outcome.error());
        }
    }

    private void unfixable(DistrictProgress progress, String key, String message) {
        progress.unfixable.add(message);
        if (progress.mode == Mode.DRY_RUN) {
            return;
        }
        if (throttle.loud(progress.districtId + "|" + key)) {
            LOGGER.warn("[miningdim] district {}: {}", progress.districtId, message);
        } else {
            LOGGER.debug("[miningdim] district {}: {}", progress.districtId, message);
        }
    }
}
