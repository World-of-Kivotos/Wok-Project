package com.miningdim.district.service;

import com.miningdim.district.DistrictLimits;
import com.miningdim.district.access.Actor;
import com.miningdim.district.access.DistrictAccess;
import com.miningdim.district.core.DistrictActionNames;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.core.RemoveReasonKind;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.flan.FlanResult;
import com.miningdim.district.notice.DistrictNoticeKind;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 住户名单 (= 学院名单): 加住户 (district.addResident)、移出住户 (district.removeResident)、重试同步 (admin.district.retrySync)。
 */
public final class ResidentService extends ServiceSupport {

    /** district.addResident 的结果: resident 是推送之后重新读到的名单行 (生效状态已是真值)。 */
    public record AddResult(MemberRecord resident, DistrictLogEntry logEntry, @Nullable PlotRecord frozenPlot) {
    }

    /**
     * district.removeResident 的结果。suspendedFriendOfPlots / stillFriendOfPlots 只给块数: 区务长看不到别人地块的朋友名单, 回执不能把
     * "是哪几块"漏给 TA。
     */
    public record RemoveResult(DistrictLogEntry logEntry, @Nullable PlotRecord frozenPlot, @Nullable Long reclaimAt,
                               int suspendedFriendOfPlots, int stillFriendOfPlots) {
    }

    ResidentService(DistrictContext ctx) {
        super(ctx);
    }

    // ================================================================
    // district.addResident 加住户
    // ================================================================

    public AddResult addResident(Actor actor, String districtId, String typedName, boolean allowNeverJoined) {
        requireNoOpenTransaction("addResident");
        sweep(districtId);
        long at = ctx.now();
        record Draft(UUID uuid, DistrictLogEntry log, @Nullable PlotRecord frozen) {
        }
        Draft draft = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            DistrictAccess actorAccess = access.access(actor, district);
            if (!actorAccess.manager()) {
                throw denied(DistrictActionNames.ADD_RESIDENT, "manager", "只有本区区务长或管理员可以管理住户");
            }
            String name = typedName == null ? "" : typedName.trim();
            if (!DistrictLimits.PLAYER_NAME_PATTERN.matcher(name).matches()) {
                throw new DistrictRuleException(DistrictError.INVALID_PLAYER_NAME,
                        "玩家 ID 只能由 3-16 位英文字母、数字或下划线组成",
                        DistrictRuleException.params("playerName", DistrictTexts.echo(name)));
            }
            PlayerDirectory.Resolved resolved = ctx.players().resolve(name);
            Optional<MemberRecord> existing = repo.memberByUuid(resolved.uuid())
                    .or(() -> repo.memberByNameLower(DistrictTexts.lower(name)));
            if (existing.isPresent()) {
                throw alreadyMember(district, existing.get());
            }
            if (!resolved.known() && !allowNeverJoined) {
                throw new DistrictRuleException(DistrictError.PLAYER_NEVER_JOINED, "没有找到 " + name + " 的登录记录",
                        DistrictRuleException.params("playerName", DistrictTexts.echo(name)));
            }
            // 已知玩家先写临时失败, 推送后改真值; 从没进过服的人 pending, 不写 Flan, 首次登录时补写。
            ResidentSyncStatus status = resolved.known() ? ResidentSyncStatus.FAILED : ResidentSyncStatus.PENDING;
            repo.insertMember(new MemberRecord(0, resolved.uuid(), resolved.name(), district.academyId(), at,
                    actor.uuid(), actor.name(), status, resolved.known() ? DistrictTexts.SYNC_INTERRUPTED : null));
            DistrictLogEntry log = writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at, actor.uuid(),
                    actor.name(), managerRole(actorAccess), DistrictLogAction.ADD, resolved.name(), null, null, null));
            // 原来有一块冻结中的地块也不会自动还给 TA: 要由管理员解除冻结。
            PlotRecord frozen = frozenPlotIn(district, resolved.uuid(), DistrictTexts.lower(resolved.name()),
                    !resolved.known());
            // 通知 (22.10): 从没进过服的人按离线 UUID 入队, 首次登录换键之后收到。
            ctx.notices().enqueueUnlessSelf(actor, resolved.uuid(), DistrictNoticeKind.RESIDENT_ADDED,
                    List.of(academyOf(district).fullName(), district.displayName()), district.districtId(), null);
            if (frozen != null) {
                ctx.notices().enqueueUnlessSelf(actor, resolved.uuid(), DistrictNoticeKind.RESIDENT_ADDED_FROZEN,
                        List.of(frozen.code()), district.districtId(), frozen.plotId());
            }
            if (resolved.known()) {
                repo.afterCommit(() -> ctx.flanSync().syncResidentMembership(district.districtId(), resolved.uuid()));
            }
            return new Draft(resolved.uuid(), log, frozen);
        });
        MemberRecord resident = repo.memberByUuid(draft.uuid()).orElseThrow(() -> new IllegalStateException(
                "the member just added to " + districtId + " vanished"));
        AUDIT.info("[miningdim] {} ({}) added {} ({}) to district {} as {}", actor.name(), actor.uuid(),
                resident.name(), resident.uuid(), districtId, resident.syncStatus().wire());
        return new AddResult(resident, draft.log(), draft.frozen());
    }

    /** 已是某个学院的成员: 本区 -> ALREADY_RESIDENT; 别的学院 (未解绑 / 已解绑两句文案) -> RESIDENT_ELSEWHERE。 */
    private DistrictRuleException alreadyMember(DistrictRecord district, MemberRecord existing) {
        if (existing.academyId().equals(district.academyId())) {
            return new DistrictRuleException(DistrictError.ALREADY_RESIDENT, existing.name() + " 已经是本区住户了",
                    DistrictRuleException.params("playerName", existing.name()));
        }
        String fullName = repo.academy(existing.academyId()).map(a -> a.fullName()).orElse(existing.academyId());
        boolean live = repo.liveDistrictOfAcademy(existing.academyId()).isPresent();
        String message = live
                ? existing.name() + " 已经是" + fullName + "的成员。一个人只能属于一个学院，要转过来得先让原学院的区务长把 TA 移出"
                : existing.name() + " 已经是" + fullName + "的成员（这个学院的自管区已解除绑定，成员名单还在）。一个人只能属于一个学院";
        return new DistrictRuleException(DistrictError.RESIDENT_ELSEWHERE, message,
                DistrictRuleException.params("playerName", existing.name(), "academyId", existing.academyId(),
                        "unbound", String.valueOf(!live)));
    }

    /**
     * 这个人在本区原来的那块冻结地块: 按 UUID 找; 按名字 (不分大小写) 只在 TA 是以待生效记入 (只有名字, UUID 是按输入
     * 算的离线 UUID) 时才认。已知玩家带着真 UUID, UUID 对不上而名字相同的是另一个账号。
     */
    @Nullable
    private PlotRecord frozenPlotIn(DistrictRecord district, UUID uuid, String nameLower, boolean nameOnly) {
        for (PlotRecord plot : repo.plotsOf(district.districtId())) {
            if (plot.frozen() && (uuid.equals(plot.frozenOwnerUuid()) || (nameOnly
                    && DistrictTexts.lower(String.valueOf(plot.frozenOwnerName())).equals(nameLower)))) {
                return plot;
            }
        }
        return null;
    }

    // ================================================================
    // district.removeResident 移出住户
    // ================================================================

    public RemoveResult removeResident(Actor actor, String districtId, String typedName, @Nullable String reasonKind,
                                       @Nullable String reason) {
        requireNoOpenTransaction("removeResident");
        sweep(districtId);
        long at = ctx.now();
        RemoveResult result = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            DistrictAccess actorAccess = access.access(actor, district);
            if (!actorAccess.manager()) {
                throw denied(DistrictActionNames.REMOVE_RESIDENT, "manager", "只有本区区务长或管理员可以管理住户");
            }
            RemoveReasonKind kind = RemoveReasonKind.fromWire(reasonKind);
            if (kind == null) {
                throw DistrictRuleException.invalidRequest("reasonKind", reasonKind);
            }
            String reasonText = reason == null ? "" : reason.trim();
            if (reasonText.isEmpty()) {
                throw new DistrictRuleException(DistrictError.REASON_REQUIRED, "请填写移出原因，它会写进操作记录");
            }
            // 界面碰不到的形状错误 (补充说明限 60 字), 所以复用 INVALID_REQUEST {field}; 必须在任何写入之前拒掉。
            if (reasonText.length() > DistrictLimits.MAX_REMOVE_REASON_CHARS) {
                throw new DistrictRuleException(DistrictError.INVALID_REQUEST,
                        "移出原因最多 " + DistrictLimits.MAX_REMOVE_REASON_CHARS + " 个字",
                        DistrictRuleException.params("field", "reason"));
            }
            MemberRecord target = memberOfDistrict(district, typedName).orElseThrow(() -> new DistrictRuleException(
                    DistrictError.NOT_RESIDENT, DistrictTexts.echo(typedName) + " 不是本区住户",
                    DistrictRuleException.params("playerName", DistrictTexts.echo(typedName))));
            if (district.isWarden(target.uuid())) {
                throw new DistrictRuleException(DistrictError.RESIDENT_IS_WARDEN,
                        target.name() + " 是本区区务长，要先由管理员撤销区务长才能移出",
                        DistrictRuleException.params("playerName", target.name()));
            }

            List<PlotRecord> plots = repo.plotsOf(district.districtId());
            PlotRecord owned = null;
            for (PlotRecord plot : plots) {
                if (plot.isOwner(target.uuid())) {
                    owned = plot;
                }
            }
            // n 在改动之前统计: TA 在本区别人地块 (含冻结中的) 上未暂停的朋友身份。
            List<FriendRecord> activeFriendships = activeFriendshipsIn(plots, target);
            int n = activeFriendships.size();
            boolean violation = kind == RemoveReasonKind.VIOLATION;

            PlotFreezeService freezes = new PlotFreezeService(ctx);
            if (owned != null) {
                freezes.freezeInTransaction(district, owned, target, actor, actorAccess, at);
            }
            List<PlotRecord> suspendedPlots = new ArrayList<>();
            if (violation) {
                for (FriendRecord friendship : activeFriendships) {
                    repo.setFriendSuspended(friendship.id(), at);
                    plots.stream().filter(p -> p.plotId().equals(friendship.plotId())).findFirst()
                            .ifPresent(suspendedPlots::add);
                }
            }
            releaseArchivedOwnership(target, actor, at, district);
            repo.deleteMember(target.uuid());

            DistrictLogEntry removeLog = writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at,
                    actor.uuid(), actor.name(), managerRole(actorAccess), DistrictLogAction.REMOVE, target.name(),
                    reasonText, null, null));
            if (owned != null) {
                writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at, actor.uuid(), actor.name(),
                        managerRole(actorAccess), DistrictLogAction.FREEZE_PLOT, owned.code(),
                        DistrictTexts.freezeDistrictReason(target.name()), null, null));
            }
            if (violation && n > 0) {
                writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at, actor.uuid(), actor.name(),
                        managerRole(actorAccess), DistrictLogAction.SUSPEND_FRIENDS, target.name(),
                        DistrictTexts.suspendDistrictReason(n), null, null));
                // 这一行就是给户主的通知: 户主打开自己的地块就在记录里看到, 可以自己恢复。移出原因绝不写进来。
                for (PlotRecord plot : suspendedPlots) {
                    writePlotLog(new PlotLogEntry(0, plot.plotId(), district.districtId(), plot.tenure(), at,
                            actor.uuid(), actor.name(), managerPlotRole(actorAccess), PlotLogAction.SUSPEND_FRIEND,
                            target.name(), DistrictTexts.PLOT_NOTE_SUSPEND, null, null, false));
                }
            }

            // 通知 (22.10): 被移出的人只收原因种类, 原因原文绝不进任何通知; 给户主的朋友暂停通知只用固定句子。
            ctx.notices().enqueueUnlessSelf(actor, target.uuid(), DistrictNoticeKind.removed(kind),
                    List.of(academyOf(district).fullName()), district.districtId(), null);
            if (owned != null) {
                ctx.notices().enqueueUnlessSelf(actor, target.uuid(), DistrictNoticeKind.PLOT_FROZEN,
                        List.of(owned.code(), String.valueOf(DistrictLimits.FREEZE_DAYS)), district.districtId(),
                        owned.plotId());
            }
            for (PlotRecord plot : suspendedPlots) {
                // 冻结中的地块没有现任户主 (户主要等解冻才能恢复朋友), 不发。
                if (plot.owned()) {
                    ctx.notices().enqueueUnlessSelf(actor, plot.ownerUuid(), DistrictNoticeKind.FRIEND_SUSPENDED,
                            List.of(plot.code(), target.name()), district.districtId(), plot.plotId());
                }
            }

            PlotRecord frozenPlot = owned;
            repo.afterCommit(() -> {
                ctx.flanSync().removeResidentMembership(district.districtId(), target.uuid());
                if (frozenPlot != null) {
                    ctx.flanSync().writePlotState(frozenPlot.plotId());
                }
                for (PlotRecord plot : suspendedPlots) {
                    if (!plot.frozen()) {
                        ctx.flanSync().writePlotState(plot.plotId());
                    }
                }
            });
            // 受影响的未冻结地块先写临时失败, 推送后改真值。
            for (PlotRecord plot : suspendedPlots) {
                if (!plot.frozen()) {
                    repo.setPlotSync(plot.plotId(), PlotSyncStatus.FAILED, DistrictTexts.SYNC_INTERRUPTED);
                }
            }
            return new RemoveResult(removeLog, owned, owned == null ? null : at + DistrictLimits.FREEZE_MS,
                    violation ? n : 0, violation ? 0 : n);
        });
        AUDIT.info("[miningdim] {} ({}) removed {} from district {} (kind {}, froze {}, friendships {})",
                actor.name(), actor.uuid(), typedName, districtId, reasonKind,
                result.frozenPlot() == null ? "-" : result.frozenPlot().plotId(),
                result.suspendedFriendOfPlots() + result.stillFriendOfPlots());
        return result;
    }

    /**
     * 某人在本区别人地块 (有户主或冻结中) 上未暂停的朋友行; TA 自己的地块不算, 空置地块本来就没有朋友。
     * 一块地至多一行 (同一块地里 UUID 与小写名各自唯一)。
     */
    static List<FriendRecord> activeFriendshipsIn(List<PlotRecord> plots, List<FriendRecord> friendsInDistrict,
                                                  MemberRecord member) {
        List<FriendRecord> matched = new ArrayList<>();
        String nameLower = member.nameLower();
        for (PlotRecord plot : plots) {
            if (plot.vacant() || plot.isOwner(member.uuid())) {
                continue;
            }
            for (FriendRecord friend : friendsInDistrict) {
                if (friend.plotId().equals(plot.plotId()) && !friend.suspended()
                        && friend.matches(member.uuid(), nameLower)) {
                    matched.add(friend);
                    break;
                }
            }
        }
        return matched;
    }

    private List<FriendRecord> activeFriendshipsIn(List<PlotRecord> plots, MemberRecord member) {
        if (plots.isEmpty()) {
            return List.of();
        }
        return activeFriendshipsIn(plots, repo.friendsInDistrict(plots.get(0).districtId()), member);
    }

    /**
     * 边角情形 (重新绑定, P2): 此人还登记为某个已解绑自管区里一块地的户主。一人一块地的唯一索引与"还是户主的成员不能删"
     * 的触发器对全部地块生效, 所以移出前先清空那块地的户主 (不写 Flan: 解绑后面板本来就不管那片领地), 并在那块地的当前
     * 任期写一行 vacate, 只有管理员能在墓碑或归档里看到。
     */
    private void releaseArchivedOwnership(MemberRecord target, Actor actor, long at, DistrictRecord current) {
        Optional<PlotRecord> elsewhere = repo.plotOwnedBy(target.uuid());
        if (elsewhere.isEmpty() || elsewhere.get().districtId().equals(current.districtId())) {
            return;
        }
        PlotRecord plot = elsewhere.get();
        repo.releaseOwner(plot.plotId());
        writePlotLog(new PlotLogEntry(0, plot.plotId(), plot.districtId(), plot.tenure(), at, actor.uuid(),
                actor.name(), actor.op() ? PlotActorRole.ADMIN : PlotActorRole.WARDEN, PlotLogAction.VACATE,
                target.name(), DistrictTexts.PLOT_NOTE_RELEASE_ARCHIVED, null, null, false));
    }

    // ================================================================
    // admin.district.retrySync 重试同步
    // ================================================================

    /**
     * 流程特殊, 不用写动作模板: 先推送; 成功时在一个事务里改为 synced、清空原因、写 resync 记录; 失败时单独提交新的原因,
     * 不写 resync 记录, 然后抛 SYNC_RETRY_FAILED (文案带上 Flan 的失败原因)。
     */
    public MemberRecord retrySync(Actor actor, String districtId, String typedName) {
        requireOp(actor, DistrictActionNames.RETRY_SYNC);
        requireNoOpenTransaction("retrySync");
        sweep(districtId);
        DistrictRecord district = requireLive(districtId);
        MemberRecord target = memberOfDistrict(district, typedName).orElseThrow(() -> new DistrictRuleException(
                DistrictError.NOT_RESIDENT, DistrictTexts.echo(typedName) + " 不是本区住户",
                DistrictRuleException.params("playerName", DistrictTexts.echo(typedName))));
        if (target.syncStatus() != ResidentSyncStatus.FAILED) {
            throw new DistrictRuleException(DistrictError.SYNC_NOTHING_TO_RETRY,
                    target.name() + " 当前没有同步失败，不需要重试",
                    DistrictRuleException.params("playerName", target.name(), "syncStatus", target.syncStatus().wire()));
        }
        FlanResult<Void> pushed = ctx.flanSync().applyResidentMembership(district.districtId(), target.uuid());
        if (!pushed.ok()) {
            String error = pushed.error() == null ? "领地写入失败" : pushed.error();
            repo.setMemberSync(target.uuid(), ResidentSyncStatus.FAILED, error);
            AUDIT.info("[miningdim] {} ({}) retried Flan sync of {} in {}: failed again ({})", actor.name(),
                    actor.uuid(), target.name(), districtId, error);
            throw new DistrictRuleException(DistrictError.SYNC_RETRY_FAILED,
                    "重试没有成功：" + error + "。" + target.name() + " 仍是“同步失败”",
                    DistrictRuleException.params("playerName", target.name()));
        }
        long at = ctx.now();
        repo.inTransaction(() -> {
            repo.setMemberSync(target.uuid(), ResidentSyncStatus.SYNCED, null);
            writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at, actor.uuid(), actor.name(),
                    managerRole(DistrictAccess.ADMIN), DistrictLogAction.RESYNC, target.name(), null, null, null));
            return null;
        });
        AUDIT.info("[miningdim] {} ({}) retried Flan sync of {} in {}: synced", actor.name(), actor.uuid(),
                target.name(), districtId);
        return repo.memberByUuid(target.uuid()).orElse(target);
    }
}
