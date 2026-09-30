package com.miningdim.district.flan;

import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.store.DistrictRepository;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 领域级 Flan 推送 (设计文档 8.2, 阶段 2 按 20.3 / 20.4 改): 按数据库算出整块的期望状态, 读 Flan 的现状, 只写不同的格子
 * ("先收后放"), 再把成败回写成"领地权限生效状态"。对账 ({@link DistrictReconciler}) 与推送共用这里的比较与写入。
 *
 * 只在事务提交之后调用 (服务层经 afterCommit 登记): Flan 没有事务, 先写 Flan 再提交数据库的话, 一旦提交失败 Flan 里就
 * 多出权限; 反过来最坏只是"数据库说该有、Flan 里还没有", 这种状态会以 failed 显示出来, 对账也会补齐。回写状态时连接
 * 不在事务中, 每一次写入各自提交。
 *
 * 结果怎么回写:
 * <ul>
 *   <li>住户的生效状态取本区居民组那一次写入的成败 (A9); 某块地的居民组写失败时那块地标 failed, 住户本身不受影响。</li>
 *   <li>本区开关写失败、删子领地失败没有契约字段可以展示: 置 needs_reconcile 并记 WARN, 对账来修。</li>
 *   <li>已解绑的自管区一律跳过, 不写任何 Flan。</li>
 * </ul>
 */
public final class DistrictFlanSync {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    /** 对一块领地执行一串写入的结果。 */
    public record ApplyOutcome(boolean ok, @Nullable String error, List<FlanOp> planned, List<FlanOp> applied,
                               @Nullable FlanOp failedOp) {

        public ApplyOutcome {
            planned = List.copyOf(planned);
            applied = List.copyOf(applied);
        }

        static ApplyOutcome failure(String error) {
            return new ApplyOutcome(false, error, List.of(), List.of(), null);
        }

        static ApplyOutcome nothing() {
            return new ApplyOutcome(true, null, List.of(), List.of(), null);
        }

        public FlanResult<Void> asResult() {
            return ok ? FlanResult.success() : FlanResult.failure(error == null ? "领地写入失败" : error);
        }
    }

    private final DistrictRepository repo;
    private final FlanGateway gateway;
    private final LogThrottle throttle;
    @Nullable
    private FlanPermissionPolicy policy;
    private long policyVersion = Long.MIN_VALUE;
    private long reportedProblemsVersion = Long.MIN_VALUE;

    /**
     * @param throttle 同一条"地块写失败""置对账标记"每小时至多 WARN 一次 (对账每 5 分钟一轮, 一块一直失败的地不能每轮都
     *                 WARN 一遍)
     */
    public DistrictFlanSync(DistrictRepository repo, FlanGateway gateway, LogThrottle throttle) {
        this.repo = repo;
        this.gateway = gateway;
        this.throttle = throttle;
    }

    public FlanGateway gateway() {
        return gateway;
    }

    DistrictRepository repo() {
        return repo;
    }

    /** 按网关当前的权限表生成的全表 (权限表重读后自动重建)。 */
    public FlanPermissionPolicy policy() {
        long version = gateway.permissionTableVersion();
        if (policy == null || version != policyVersion) {
            policy = FlanPermissionPolicy.of(gateway.permissionTable());
            policyVersion = version;
        }
        return policy;
    }

    /**
     * 写前预检的权限表部分 (20.2 第 8 条): 本模块要显式写的 id 缺了 (/reload 后数据包删掉了某个权限) 时, 整批不写,
     * 报"Flan 权限表里没有 {id}"; 同一张表只记一次 ERROR。返回 null 表示可以写。
     */
    @Nullable
    public String permissionProblem() {
        FlanPermissionPolicy current = policy();
        List<String> problems = current.problems();
        if (problems.isEmpty()) {
            return null;
        }
        if (reportedProblemsVersion != policyVersion) {
            reportedProblemsVersion = policyVersion;
            LOGGER.error("[miningdim] district: the Flan permission table is missing permissions this module writes; "
                    + "every Flan write and the reconciliation are paused until a /reload restores them:\n - {}",
                    String.join("\n - ", problems));
        }
        return problems.get(0);
    }

    // ================================================================
    // 自管区领地
    // ================================================================

    /**
     * 取本区的父领地; 库里还没有领地 id 时新建一块 (先强制备份, 建完显式存盘) 并把 id 写回, 然后写满父领地的状态。
     * 库里有 id 但 Flan 里找不到时不擅自重建 (可能是被 /flan 删了, 也可能是恢复了更早的备份), 置对账标记后报失败;
     * OP 确认后用 /district claim &lt;id&gt; recreate 或 relink。
     *
     * <p>领地对接降级 (没装 Flan、自检不过、熔断) 或这个维度没有加载时, 查询一律返回空: 这时报出真正的原因, 不当成
     * "领地被删了", 也不置对账标记。
     */
    public FlanResult<ClaimHandle> ensureDistrictClaim(DistrictRecord district) {
        String dimension = district.bounds().dimension();
        String unreachable = unreachable(dimension);
        if (unreachable != null) {
            return FlanResult.failure(unreachable);
        }
        if (district.flanClaimId() != null) {
            Optional<ClaimHandle> found = gateway.findDistrictClaim(dimension, district.flanClaimId());
            if (found.isPresent()) {
                return FlanResult.success(found.get());
            }
            flagReconcile(district.districtId(), "district claim " + district.flanClaimId() + " is missing in Flan");
            return FlanResult.failure(DistrictTexts.FLAN_CLAIM_MISSING);
        }
        return createDistrictClaim(district, "create");
    }

    /** 领地对接用不了、或这个维度没有加载时的原因 (给人看的); 能用为 null。 */
    @Nullable
    public String unreachable(String dimension) {
        if (!gateway.available()) {
            return gateway.unavailableReason();
        }
        return gateway.dimensionLoaded(dimension) ? null : DistrictTexts.dimensionNotLoaded(dimension);
    }

    /** 在库里的范围上新建父领地 (建区、DEGRADED 时建的区补建、recreate): 备份、建、写回 id、存盘、写满状态。 */
    FlanResult<ClaimHandle> createDistrictClaim(DistrictRecord district, String backupReason) {
        String problem = permissionProblem();
        if (problem != null) {
            return FlanResult.failure(problem);
        }
        String dimension = district.bounds().dimension();
        Optional<ClaimHandle> stray = strayClaimOf(district);
        if (stray.isPresent()) {
            // 上一次建成了、却没能把 id 写进库 (写库失败、崩溃): 认回这块, 不再去建一块注定"重叠"的。
            LOGGER.warn("[miningdim] district {}: relinking the unrecorded admin claim {} that exactly matches its "
                    + "bounds and name instead of creating a new one", district.districtId(), stray.get().claimId());
            repo.setDistrictClaimId(district.districtId(), stray.get().claimId());
            DistrictRecord relinked = repo.liveDistrict(district.districtId()).orElse(district);
            ApplyOutcome written = syncDistrictClaim(relinked, stray.get(), true);
            if (!written.ok()) {
                flagReconcile(district.districtId(), "writing the state of a relinked district claim failed: "
                        + written.error());
            }
            return FlanResult.success(stray.get());
        }
        FlanResult<String> backup = gateway.backupNow(dimension, backupReason);
        if (!backup.ok()) {
            return FlanResult.failure(errorOf(backup));
        }
        FlanResult<ClaimHandle> created = gateway.createDistrictClaim(dimension, district.bounds(),
                DistrictTexts.claimName(district.displayName()));
        if (!created.ok() || created.value() == null) {
            return FlanResult.failure(errorOf(created));
        }
        ClaimHandle handle = created.value();
        repo.setDistrictClaimId(district.districtId(), handle.claimId());
        // 父领地本身是唯一不能自愈的状态 (库里记着 id、Flan 里却没有时不擅自重建): 建完立刻存一次 (20.5)。
        gateway.flush(dimension);
        DistrictRecord withClaim = repo.liveDistrict(district.districtId()).orElse(district);
        ApplyOutcome written = syncDistrictClaim(withClaim, handle, true);
        if (!written.ok()) {
            flagReconcile(district.districtId(), "writing the state of a new district claim failed: "
                    + written.error());
        }
        return FlanResult.success(handle);
    }

    /**
     * 没被任何自管区行 (在用的与已解绑的) 记着、X/Z 与库里的范围恰好相同、名字恰好是本区名字的顶层管理员领地: 只可能是
     * 本模块上一次建成了、却没能把 id 写进库的那一块 (建完之后写库失败、崩溃)。恰好一块时返回它。
     */
    private Optional<ClaimHandle> strayClaimOf(DistrictRecord district) {
        String dimension = district.bounds().dimension();
        String name = DistrictTexts.claimName(district.displayName());
        Set<UUID> referenced = new HashSet<>();
        repo.liveDistricts().forEach(row -> addIfPresent(referenced, row.flanClaimId()));
        repo.archivedDistricts(Integer.MAX_VALUE).forEach(row -> addIfPresent(referenced, row.flanClaimId()));
        List<ClaimHandle> matches = new ArrayList<>();
        for (ClaimHandle candidate : gateway.claimsIntersecting(dimension, district.bounds())) {
            if (candidate.parentId() != null || referenced.contains(candidate.claimId())) {
                continue;
            }
            Optional<ClaimInfo> info = gateway.inspectClaim(candidate);
            if (info.isPresent() && info.get().adminClaim() && info.get().area().equals(district.bounds().asArea())
                    && name.equals(info.get().name())) {
                matches.add(candidate);
            }
        }
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private static void addIfPresent(Set<UUID> ids, @Nullable UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    /** 建区之后: 建好父领地并写满状态 (失败只置对账标记, 建区本身不回滚)。 */
    public void initializeDistrict(String districtId) {
        Optional<DistrictRecord> district = repo.liveDistrict(districtId);
        if (district.isEmpty()) {
            return;
        }
        FlanResult<ClaimHandle> claim = ensureDistrictClaim(district.get());
        if (!claim.ok()) {
            flagReconcile(districtId, "creating the district claim failed: " + claim.error());
        }
    }

    /**
     * 收编一块已有的管理员领地 (/district bind 确认之后, 20.6): 强制备份, 删掉其上全部子领地、全部组与成员、假玩家与
     * 药水, 设名字, 显式存盘, 写满父领地的状态。任一步失败即停并置对账标记 (每一步都是幂等的, 下一轮对账接着做)。
     */
    public FlanResult<Void> adoptDistrictClaim(String districtId) {
        Optional<DistrictRecord> found = repo.liveDistrict(districtId);
        if (found.isEmpty() || found.get().flanClaimId() == null) {
            return FlanResult.failure("自管区不存在或没有绑定领地");
        }
        DistrictRecord district = found.get();
        String dimension = district.bounds().dimension();
        FlanResult<Void> result = adopt(district, dimension);
        if (!result.ok()) {
            flagReconcile(districtId, "adopting the district claim failed: " + result.error());
        }
        return result;
    }

    private FlanResult<Void> adopt(DistrictRecord district, String dimension) {
        String unreachable = unreachable(dimension);
        if (unreachable != null) {
            return FlanResult.failure(unreachable);
        }
        String problem = permissionProblem();
        if (problem != null) {
            return FlanResult.failure(problem);
        }
        Optional<ClaimHandle> claim = gateway.findDistrictClaim(dimension, district.flanClaimId());
        if (claim.isEmpty()) {
            return FlanResult.failure("要收编的领地在 Flan 里找不到了");
        }
        FlanResult<String> backup = gateway.backupNow(dimension, "bind");
        if (!backup.ok()) {
            return FlanResult.failure(errorOf(backup));
        }
        ClaimHandle handle = claim.get();
        for (ClaimHandle sub : gateway.listPlotClaims(handle)) {
            FlanResult<Void> deleted = gateway.deletePlotClaim(sub);
            if (!deleted.ok()) {
                return deleted;
            }
        }
        for (String group : gateway.readPermissions(handle).groups().keySet()) {
            FlanResult<Void> deleted = gateway.deleteGroup(handle, group);
            if (!deleted.ok()) {
                return deleted;
            }
        }
        for (UUID member : gateway.readMembers(handle).keySet()) {
            FlanResult<Void> removed = gateway.setMember(handle, member, null);
            if (!removed.ok()) {
                return removed;
            }
        }
        FlanResult<Void> cleared = gateway.clearExtras(handle);
        if (!cleared.ok()) {
            return cleared;
        }
        FlanResult<Void> named = gateway.setClaimName(handle, DistrictTexts.claimName(district.displayName()));
        if (!named.ok()) {
            return named;
        }
        // /flan 圈的管理员领地默认只往下探 defaultClaimDepth 格: 底补到世界底, 否则每块地的地下都是野外 (20.3)。
        FlanResult<Void> deepened = gateway.extendDistrictClaimToBottom(handle);
        if (!deepened.ok()) {
            return deepened;
        }
        gateway.flush(dimension);
        return syncDistrictClaim(district, handle, true).asResult();
    }

    /**
     * 父领地丢了 (/district claim &lt;id&gt; recreate, 20.6): 在库里的范围上新建一块, 新 id 写回库。之后由调用方跑一轮
     * resync 把全部地块按库重建。
     */
    public FlanResult<ClaimHandle> recreateDistrictClaim(String districtId) {
        Optional<DistrictRecord> found = repo.liveDistrict(districtId);
        if (found.isEmpty()) {
            return FlanResult.failure("自管区不存在");
        }
        return createDistrictClaim(found.get(), "recreate");
    }

    /**
     * 按库写一遍父领地 (组、默认、名字、假玩家与药水; includeMembers 时连成员一起)。
     *
     * <p>推送 (改公共区域开关) 不带成员: 成员资格另有定向的推送 (加住户、首次登录、移出), 那里负责回写住户的生效状态。
     * 对账、收编、建领地带成员, 写成功的住户回写 synced, 写失败的回写 failed 加原因。
     */
    public ApplyOutcome syncDistrictClaim(DistrictRecord district, ClaimHandle handle, boolean includeMembers) {
        String problem = permissionProblem();
        if (problem != null) {
            return ApplyOutcome.failure(problem);
        }
        List<MemberRecord> roster = repo.membersOf(district.academyId());
        ClaimDesiredState desired = DistrictDesiredState.compute(policy(), district,
                repo.districtCells(district.districtId()), roster);
        List<FlanOp> plan = planDistrict(desired, handle, includeMembers);
        ApplyOutcome outcome = execute(handle, plan);
        if (includeMembers) {
            writeBackMembers(roster, desired, outcome);
        }
        return outcome;
    }

    /** 父领地的差异 (对账的预演也用它)。 */
    List<FlanOp> planDistrict(ClaimDesiredState desired, ClaimHandle handle, boolean includeMembers) {
        Map<UUID, String> members = gateway.readMembers(handle);
        List<FlanOp> plan = ClaimDiff.plan(desired, gateway.readPermissions(handle),
                includeMembers ? members : desired.members(), gateway.inspectClaim(handle).orElse(null));
        if (includeMembers) {
            return plan;
        }
        // 不带成员: 现状里的成员照搬期望, 比较结果里就只剩删组 (连同组里的人) 与权限、名字、杂项。
        List<FlanOp> filtered = new ArrayList<>();
        for (FlanOp op : plan) {
            if (!(op instanceof FlanOp.SetMember) && !(op instanceof FlanOp.RemoveMember)) {
                filtered.add(op);
            }
        }
        return filtered;
    }

    /**
     * 住户的生效状态回写: 期望里的人, 本来就在对的组里、或这一轮写成了的回 synced; 写失败的那一位回 failed 加原因;
     * 还没轮到写 (前面的写入先失败了) 的不动。
     */
    private void writeBackMembers(List<MemberRecord> roster, ClaimDesiredState desired, ApplyOutcome outcome) {
        UUID failedMember = outcome.failedOp() instanceof FlanOp.SetMember set ? set.member() : null;
        Set<UUID> needed = new HashSet<>();
        for (FlanOp op : outcome.planned()) {
            if (op instanceof FlanOp.SetMember set) {
                needed.add(set.member());
            }
        }
        Set<UUID> written = new HashSet<>();
        for (FlanOp op : outcome.applied()) {
            if (op instanceof FlanOp.SetMember set) {
                written.add(set.member());
            }
        }
        for (MemberRecord member : roster) {
            if (!desired.members().containsKey(member.uuid())) {
                continue;
            }
            if (member.uuid().equals(failedMember)) {
                repo.setMemberSync(member.uuid(), ResidentSyncStatus.FAILED, outcome.error());
            } else if (member.syncStatus() == ResidentSyncStatus.FAILED
                    && (!needed.contains(member.uuid()) || written.contains(member.uuid()))) {
                repo.setMemberSync(member.uuid(), ResidentSyncStatus.SYNCED, null);
            }
        }
    }

    // ================================================================
    // 住户的成员资格 (定向推送)
    // ================================================================

    /**
     * 把住户写进本区居民组, 再在每块未冻结的地里按期望状态放进对应的组 (户主 &gt; 朋友 &gt; 居民)。不回写住户状态;
     * 某块地写失败时那块地标 failed。
     *
     * @return 本区居民组那一次写入的成败 (即住户的生效状态)
     */
    public FlanResult<Void> applyResidentMembership(String districtId, UUID member) {
        Optional<DistrictRecord> found = repo.liveDistrict(districtId);
        if (found.isEmpty()) {
            return FlanResult.failure("自管区已解除绑定");
        }
        DistrictRecord district = found.get();
        String problem = permissionProblem();
        if (problem != null) {
            return FlanResult.failure(problem);
        }
        FlanResult<ClaimHandle> claim = ensureDistrictClaim(district);
        if (!claim.ok() || claim.value() == null) {
            return FlanResult.failure(errorOf(claim));
        }
        String group = FlanGroupNames.districtResident(district.districtId());
        if (!group.equals(gateway.readMembers(claim.value()).get(member))) {
            FlanResult<Void> joined = gateway.setMember(claim.value(), member, group);
            if (!joined.ok()) {
                return joined;
            }
        }
        // 本区居民组已写成功: 名单行此刻还挂着"临时失败", 放进各地块时把 TA 当作已生效。
        placeMemberInPlots(district, claim.value(), member, member);
        return FlanResult.success();
    }

    /** {@link #applyResidentMembership} 之后回写住户的生效状态 (synced, 或 failed 加原因)。 */
    public ResidentSyncStatus syncResidentMembership(String districtId, UUID member) {
        FlanResult<Void> result = applyResidentMembership(districtId, member);
        ResidentSyncStatus status = result.ok() ? ResidentSyncStatus.SYNCED : ResidentSyncStatus.FAILED;
        repo.setMemberSync(member, status, result.ok() ? null : result.error());
        if (!result.ok()) {
            LOGGER.warn("[miningdim] district {}: membership write for {} failed: {}", districtId, member,
                    result.error());
        }
        return status;
    }

    /** 移出住户之后: 从本区居民组与每块地的居民组里移出 (TA 仍是有效朋友的地块留在朋友组)。 */
    public void removeResidentMembership(String districtId, UUID member) {
        Optional<DistrictRecord> found = repo.liveDistrict(districtId);
        if (found.isEmpty()) {
            return;
        }
        DistrictRecord district = found.get();
        FlanResult<ClaimHandle> claim = ensureDistrictClaim(district);
        if (!claim.ok() || claim.value() == null) {
            flagReconcile(districtId, "removing " + member + " failed: " + claim.error());
            return;
        }
        if (gateway.readMembers(claim.value()).containsKey(member)) {
            FlanResult<Void> left = gateway.setMember(claim.value(), member, null);
            if (!left.ok()) {
                flagReconcile(districtId, "removing " + member + " from the district group failed: " + left.error());
            }
        }
        placeMemberInPlots(district, claim.value(), member, null);
    }

    /**
     * 在每块未冻结的地里把某人放进期望的组 (或移出); 某块地失败时标 failed。这个人的名单行与全区的朋友行各读一次,
     * 每块地只算"这个人该在哪个组" ({@link PlotDesiredState#memberGroup}), 不逐块整张算期望状态 —— 那要逐块读三列与
     * 全区名单, 大区里一次加人就是地块数乘名单人数的读库量。
     */
    private void placeMemberInPlots(DistrictRecord district, ClaimHandle districtClaim, UUID member,
                                    @Nullable UUID assumeSynced) {
        MemberRecord record = repo.memberByUuid(member).orElse(null);
        Map<String, List<FriendRecord>> friendsByPlot = new HashMap<>();
        for (FriendRecord friend : repo.friendsInDistrict(district.districtId())) {
            friendsByPlot.computeIfAbsent(friend.plotId(), ignored -> new ArrayList<>()).add(friend);
        }
        for (PlotRecord plot : repo.plotsOf(district.districtId())) {
            if (plot.frozen()) {
                // 冻结期间居民组本来就是空的, 解冻时整块重写会把人放回去。
                continue;
            }
            String group = PlotDesiredState.memberGroup(district, plot, friendsByPlot.getOrDefault(plot.plotId(),
                    List.of()), member, record, assumeSynced);
            FlanResult<Void> placed = placeMemberInPlot(districtClaim, plot, member, group, assumeSynced);
            if (!placed.ok()) {
                markPlotFailed(plot, placed.error());
            }
        }
    }

    private FlanResult<Void> placeMemberInPlot(ClaimHandle districtClaim, PlotRecord plot, UUID member,
                                               @Nullable String group, @Nullable UUID assumeSynced) {
        Optional<ClaimHandle> plotClaim = plot.flanClaimId() == null
                ? Optional.empty()
                : gateway.findPlotClaim(districtClaim, plot.flanClaimId());
        if (plotClaim.isEmpty()) {
            // 子领地还没建起来 (或在 Flan 里找不到): 整块重写会建好它并把所有人一起放进去, 成败由它自己回写。
            writePlotState(plot.plotId(), assumeSynced);
            return FlanResult.success();
        }
        String current = gateway.readMembers(plotClaim.get()).get(member);
        if (group == null ? current == null : group.equals(current)) {
            return FlanResult.success();
        }
        return gateway.setMember(plotClaim.get(), member, group);
    }

    // ================================================================
    // 公共区域开关
    // ================================================================

    /**
     * 改了本区的开关之后 (一格或恢复默认): 按库把父领地的组与默认整块比一遍, 只写不同的格子 (跟随项、区域规则取反都在
     * 全表里算好)。失败置对账标记 (没有契约字段可以展示)。
     */
    public boolean writeDistrictState(String districtId) {
        Optional<DistrictRecord> district = repo.liveDistrict(districtId);
        if (district.isEmpty()) {
            return false;
        }
        FlanResult<ClaimHandle> claim = ensureDistrictClaim(district.get());
        if (!claim.ok() || claim.value() == null) {
            flagReconcile(districtId, "writing the public permissions failed: " + claim.error());
            return false;
        }
        ApplyOutcome written = syncDistrictClaim(district.get(), claim.value(), false);
        if (!written.ok()) {
            flagReconcile(districtId, "writing the public permissions failed: " + written.error());
            return false;
        }
        return true;
    }

    // ================================================================
    // 地块
    // ================================================================

    /** 整块写一块地并回写它的生效状态; 地块已删或所在自管区已解绑时什么都不做。 */
    public void writePlotState(String plotId) {
        writePlotState(plotId, null);
    }

    private void writePlotState(String plotId, @Nullable UUID assumeSynced) {
        Optional<PlotRecord> plot = repo.plot(plotId);
        if (plot.isEmpty()) {
            return;
        }
        Optional<DistrictRecord> district = repo.liveDistrict(plot.get().districtId());
        if (district.isEmpty()) {
            return;
        }
        ApplyOutcome result = applyPlotState(district.get(), plot.get(), assumeSynced);
        recordPlotOutcome(plot.get(), result);
    }

    /**
     * 地块的生效状态回写 (before 是写之前读到的那一行)。状态与原因都没变时不写库: 对账每一轮每块地都走到这里, 大区里
     * 一轮就是成百上千次 UPDATE。
     */
    void recordPlotOutcome(PlotRecord before, ApplyOutcome result) {
        if (result.ok()) {
            if (before.syncStatus() != PlotSyncStatus.SYNCED || before.syncError() != null) {
                repo.setPlotSync(before.plotId(), PlotSyncStatus.SYNCED, null);
            }
        } else {
            markPlotFailed(before, result.error());
        }
    }

    /**
     * 整块写一块地: 建好父领地与子领地 (库里没有子领地 id 或 Flan 里找不到时新建并写回 id), 范围改到库里的值, 再按
     * 期望状态与现状的差异"先收后放"地写组、默认与成员。任何一步失败即停, 返回该失败。
     */
    ApplyOutcome applyPlotState(DistrictRecord district, PlotRecord plot, @Nullable UUID assumeSynced) {
        String problem = permissionProblem();
        if (problem != null) {
            return ApplyOutcome.failure(problem);
        }
        FlanResult<ClaimHandle> districtClaim = ensureDistrictClaim(district);
        if (!districtClaim.ok() || districtClaim.value() == null) {
            return ApplyOutcome.failure(errorOf(districtClaim));
        }
        ClaimHandle handle = plot.flanClaimId() == null
                ? null
                : gateway.findPlotClaim(districtClaim.value(), plot.flanClaimId()).orElse(null);
        if (handle == null) {
            // 网关建好的子领地已经关着 (三个 p_ 组与默认全假): 下面任何一步出事, 这块地都停在谁也进不去的一侧。
            FlanResult<ClaimHandle> created = gateway.createPlotClaim(districtClaim.value(), plot.area(),
                    DistrictTexts.claimName(plot.code()), plot.plotId());
            if (!created.ok() || created.value() == null) {
                return ApplyOutcome.failure(errorOf(created));
            }
            handle = created.value();
            try {
                repo.setPlotClaimId(plot.plotId(), handle.claimId());
            } catch (RuntimeException failure) {
                // 库里没记下它的 id: 删掉这块子领地, 下一次写入重新建 (删不掉的话它关着、带着本块的组名, 对账会认领)。
                FlanResult<Void> undone = gateway.deletePlotClaim(handle);
                LOGGER.error("[miningdim] plot {}: recording the new plot claim {} failed ({}); {}", plot.plotId(),
                        handle.claimId(), failure.toString(), undone.ok() ? "deleted it again"
                                : "could not delete it (" + undone.error() + "); the reconciliation relinks it",
                        failure);
                return ApplyOutcome.failure(DistrictTexts.PLOT_CLAIM_NOT_RECORDED);
            }
        }
        return execute(handle, planPlot(district, plot, handle, assumeSynced));
    }

    /** 一块地的差异: 范围不同先改范围, 其余照 {@link ClaimDiff}。 */
    List<FlanOp> planPlot(DistrictRecord district, PlotRecord plot, ClaimHandle handle, @Nullable UUID assumeSynced) {
        PlotDesiredState desired = desiredState(district, plot, assumeSynced);
        Optional<ClaimInfo> info = gateway.inspectClaim(handle);
        List<FlanOp> plan = new ArrayList<>(ClaimDiff.plan(desired.asClaimState(), gateway.readPermissions(handle),
                gateway.readMembers(handle), info.orElse(null)));
        if (info.isPresent() && !info.get().area().equals(plot.area())) {
            plan.add(new FlanOp.Resize(info.get().area(), plot.area()));
        }
        plan.sort(Comparator.comparingInt(FlanOp::phase));
        return plan;
    }

    /** 删除一块地的子领地 (地块行已删, 所以由调用方传入它的领地 id); 失败置对账标记。 */
    public void deletePlotClaim(String districtId, @Nullable UUID plotClaimId) {
        if (plotClaimId == null) {
            return;
        }
        Optional<DistrictRecord> district = repo.liveDistrict(districtId);
        if (district.isEmpty() || district.get().flanClaimId() == null) {
            return;
        }
        Optional<ClaimHandle> districtClaim = gateway.findDistrictClaim(district.get().bounds().dimension(),
                district.get().flanClaimId());
        if (districtClaim.isEmpty()) {
            flagReconcile(districtId, "deleting plot claim " + plotClaimId + ": the district claim is missing");
            return;
        }
        Optional<ClaimHandle> plotClaim = gateway.findPlotClaim(districtClaim.get(), plotClaimId);
        if (plotClaim.isEmpty()) {
            return;
        }
        FlanResult<Void> deleted = gateway.deletePlotClaim(plotClaim.get());
        if (!deleted.ok()) {
            flagReconcile(districtId, "deleting plot claim " + plotClaimId + " failed: " + deleted.error());
        }
    }

    // ================================================================
    // 内部
    // ================================================================

    /** 按顺序执行一串写入, 第一次失败即停。 */
    ApplyOutcome execute(ClaimHandle claim, List<FlanOp> plan) {
        if (plan.isEmpty()) {
            return ApplyOutcome.nothing();
        }
        List<FlanOp> applied = new ArrayList<>();
        for (FlanOp op : plan) {
            FlanResult<Void> result = op.apply(gateway, claim);
            if (!result.ok()) {
                return new ApplyOutcome(false, errorOf(result), plan, applied, op);
            }
            applied.add(op);
        }
        return new ApplyOutcome(true, null, plan, applied, null);
    }

    PlotDesiredState desiredState(DistrictRecord district, PlotRecord plot, @Nullable UUID assumeSynced) {
        return PlotDesiredState.compute(policy(), district, plot, repo.plotCells(plot.plotId()),
                repo.friendsOf(plot.plotId()), repo.membersOf(district.academyId()), assumeSynced);
    }

    /** 地块标 failed (状态与原因都没变时不写库); 同一块地同一个原因每小时只 WARN 一次。 */
    private void markPlotFailed(PlotRecord before, @Nullable String error) {
        String reason = error == null ? "领地写入失败" : error;
        if (before.syncStatus() != PlotSyncStatus.FAILED || !reason.equals(before.syncError())) {
            repo.setPlotSync(before.plotId(), PlotSyncStatus.FAILED, reason);
        }
        if (throttle.loud("plot-failed|" + before.plotId() + "|" + reason)) {
            LOGGER.warn("[miningdim] plot {}: Flan write failed: {}", before.plotId(), reason);
        } else {
            LOGGER.debug("[miningdim] plot {}: Flan write failed again: {}", before.plotId(), reason);
        }
    }

    /** 置对账标记; 同一个区同一件事每小时只 WARN 一次。 */
    void flagReconcile(String districtId, String what) {
        repo.markNeedsReconcile(districtId);
        if (throttle.loud("flag|" + districtId + "|" + what)) {
            LOGGER.warn("[miningdim] district {}: {}; flagged for reconciliation", districtId, what);
        } else {
            LOGGER.debug("[miningdim] district {}: {}; flagged for reconciliation", districtId, what);
        }
    }

    static String errorOf(FlanResult<?> result) {
        return result.error() == null ? "领地写入失败" : result.error();
    }
}
