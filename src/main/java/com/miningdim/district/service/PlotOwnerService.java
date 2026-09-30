package com.miningdim.district.service;

import com.miningdim.district.DistrictLimits;
import com.miningdim.district.access.Actor;
import com.miningdim.district.access.DistrictAccess;
import com.miningdim.district.access.PlotRelation;
import com.miningdim.district.core.DistrictActionNames;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.FriendSyncStatus;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionCells;
import com.miningdim.district.core.PermissionChange;
import com.miningdim.district.core.PermissionItemDef;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotAudience;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.notice.DistrictNoticeKind;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 户主管自己的地块 (plot.setPermission / plot.resetPermissions / plot.addFriend / plot.removeFriend /
 * plot.restoreFriend): 三列开关与朋友。户主本人或管理员可改 (管理员改的一律记"管理员代改"),
 * 区务长改别人的地块一律拒绝 (户主的家由户主做主)。只有有户主的地块能改: 冻结中与空置的地块依次报 PLOT_FROZEN / PLOT_VACANT。
 */
public final class PlotOwnerService extends ServiceSupport {

    /** plot.setPermission 的结果: 值没变时 logEntry 为 null。 */
    public record SetResult(PlotRecord plot, PermissionItemDef item, PermissionCells cells, PlotRelation relation,
                            @Nullable PlotLogEntry logEntry) {
    }

    /** plot.resetPermissions 的结果: 一格都没改时 logEntries 为空。 */
    public record ResetResult(PlotRecord plot, PlotRelation relation, List<PlotLogEntry> logEntries) {
    }

    /** plot.addFriend / plot.restoreFriend 的结果。 */
    public record FriendResult(FriendRecord friend, boolean isResident, PlotLogEntry logEntry) {
    }

    private record Gate(DistrictRecord district, PlotRecord plot, PlotRelation relation) {
    }

    private record PlotChange(PermissionItemDef item, PlotAudience audience, boolean from, boolean to) {
    }

    PlotOwnerService(DistrictContext ctx) {
        super(ctx);
    }

    /**
     * 地块写动作的共同闸门 (plot.setPermission 前 5 步): 自管区、地块、关系 (区务长用专门的文案)、冻结、空置。
     */
    private Gate gate(Actor actor, String districtId, String plotId, String action) {
        DistrictRecord district = requireLive(districtId);
        PlotRecord plot = requirePlot(district, plotId);
        PlotRelation relation = access.relation(actor, plot);
        if (relation == PlotRelation.NONE) {
            if (access.access(actor, district) == DistrictAccess.WARDEN) {
                throw denied(action, "owner", "别人的地块由户主做主，区务长只能看地块列表，不能看或改朋友和权限");
            }
            throw denied(action, "owner", "只有户主本人和管理员能改这块地的朋友和权限");
        }
        if (plot.frozen()) {
            throw new DistrictRuleException(DistrictError.PLOT_FROZEN, plot.code() + " 冻结中，朋友和权限要等解除冻结后才能改",
                    DistrictRuleException.params("plotId", plot.plotId(), "code", plot.code()));
        }
        if (plot.vacant()) {
            throw new DistrictRuleException(DistrictError.PLOT_VACANT, plot.code() + " 现在空置，没有户主，不能加朋友或改权限",
                    DistrictRuleException.params("plotId", plot.plotId(), "code", plot.code()));
        }
        return new Gate(district, plot, relation);
    }

    // ================================================================
    // plot.setPermission / plot.resetPermissions 三列开关
    // ================================================================

    /**
     * @param audience wire 值 (friend / resident / outsider)
     * @param enabled  不是 JSON 布尔时为 null
     */
    public SetResult setPermission(Actor actor, String districtId, String plotId, @Nullable String permissionId,
                                   @Nullable String audience, @Nullable Boolean enabled) {
        requireNoOpenTransaction("plot.setPermission");
        sweep(districtId);
        long at = ctx.now();
        SetResult result = repo.inTransaction(() -> {
            Gate gate = gate(actor, districtId, plotId, DistrictActionNames.PLOT_SET_PERMISSION);
            PlotAudience column = PlotAudience.fromWire(audience);
            if (column == null) {
                throw DistrictRuleException.invalidRequest("audience", audience);
            }
            if (enabled == null) {
                throw DistrictRuleException.invalidRequest("enabled", null);
            }
            PermissionItemDef item = PermissionCatalog.find(permissionId)
                    .orElseThrow(() -> DistrictPermissionService.unknownItem(permissionId));
            if (item.region()) {
                throw new DistrictRuleException(DistrictError.REGION_RULE_NOT_IN_PLOT,
                        "区域规则全区统一，只有管理员能在自管区里改，地块里不能单独改",
                        DistrictRuleException.params("permissionId", item.permissionId()));
            }
            PermissionCells before = repo.plotCells(gate.plot().plotId());
            boolean from = before.plot(item, column);
            if (from == enabled) {
                return new SetResult(gate.plot(), item, before, gate.relation(), null);
            }
            repo.setPlotCell(gate.plot().plotId(), item.permissionId(), column.wire(), enabled);
            PlotLogEntry log = writePlotLog(ownerLog(gate, actor, at, PlotLogAction.PERMISSION, null, null,
                    new PermissionChange(item.permissionId(), item.label(), column.wire(), from, enabled)));
            schedulePlotPush(gate.plot());
            notifyOwnerIfAdmin(gate, actor, DistrictNoticeKind.PLOT_ADMIN_PERMISSION, List.of(actor.name(),
                    gate.plot().code(), DistrictTexts.permissionChangeText(column, item.label(), enabled)));
            return new SetResult(gate.plot(), item, repo.plotCells(gate.plot().plotId()), gate.relation(), log);
        });
        auditIfAdmin(actor, result.relation(), result.logEntry() != null, "set " + permissionId + "/" + audience
                + " of plot " + plotId + " to " + enabled);
        return result;
    }

    /** 每一格与默认不同的写回默认, 顺序为先按目录项、每项内按朋友 / 住户 / 外人, 每格一条记录 (缘由"恢复默认")。 */
    public ResetResult resetPermissions(Actor actor, String districtId, String plotId) {
        requireNoOpenTransaction("plot.resetPermissions");
        sweep(districtId);
        long at = ctx.now();
        ResetResult result = repo.inTransaction(() -> {
            Gate gate = gate(actor, districtId, plotId, DistrictActionNames.PLOT_RESET_PERMISSIONS);
            PermissionCells cells = repo.plotCells(gate.plot().plotId());
            List<PlotChange> changes = new ArrayList<>();
            for (PermissionItemDef item : PermissionCatalog.memberItems()) {
                for (PlotAudience column : PlotAudience.values()) {
                    boolean from = cells.plot(item, column);
                    boolean to = item.plotDefault(column);
                    if (from != to) {
                        changes.add(new PlotChange(item, column, from, to));
                    }
                }
            }
            List<PlotLogEntry> logs = new ArrayList<>();
            for (PlotChange change : changes) {
                repo.setPlotCell(gate.plot().plotId(), change.item().permissionId(), change.audience().wire(),
                        change.to());
                logs.add(writePlotLog(ownerLog(gate, actor, at, PlotLogAction.PERMISSION, null,
                        DistrictTexts.RESTORE_DEFAULT, new PermissionChange(change.item().permissionId(),
                                change.item().label(), change.audience().wire(), change.from(), change.to()))));
            }
            if (!changes.isEmpty()) {
                schedulePlotPush(gate.plot());
                // N 条记录合成一条通知。
                notifyOwnerIfAdmin(gate, actor, DistrictNoticeKind.PLOT_ADMIN_RESET, List.of(actor.name(),
                        gate.plot().code(), DistrictTexts.NOTICE_RESET_SCOPE, String.valueOf(changes.size())));
            }
            return new ResetResult(gate.plot(), gate.relation(), List.copyOf(logs));
        });
        auditIfAdmin(actor, result.relation(), !result.logEntries().isEmpty(),
                "reset " + result.logEntries().size() + " cell(s) of plot " + plotId);
        return result;
    }

    // ================================================================
    // plot.addFriend / plot.removeFriend / plot.restoreFriend 朋友
    // ================================================================

    /** 朋友可以是任何玩家: 外人、本区住户、别区住户、OP 都行。 */
    public FriendResult addFriend(Actor actor, String districtId, String plotId, @Nullable String typedName,
                                  boolean allowNeverJoined) {
        requireNoOpenTransaction("plot.addFriend");
        sweep(districtId);
        long at = ctx.now();
        record Added(PlotRelation relation, FriendResult result) {
        }
        Added added = repo.inTransaction(() -> {
            Gate gate = gate(actor, districtId, plotId, DistrictActionNames.PLOT_ADD_FRIEND);
            String name = typedName == null ? "" : typedName.trim();
            if (!DistrictLimits.PLAYER_NAME_PATTERN.matcher(name).matches()) {
                throw new DistrictRuleException(DistrictError.INVALID_PLAYER_NAME,
                        "玩家 ID 只能由 3-16 位英文字母、数字或下划线组成",
                        DistrictRuleException.params("playerName", DistrictTexts.echo(name)));
            }
            String nameLower = DistrictTexts.lower(name);
            PlayerDirectory.Resolved resolved = ctx.players().resolve(name);
            PlotRecord plot = gate.plot();
            if (plot.isOwner(resolved.uuid()) || DistrictTexts.lower(String.valueOf(plot.ownerName())).equals(nameLower)) {
                throw new DistrictRuleException(DistrictError.FRIEND_IS_OWNER,
                        "户主本人不用加成朋友：自己的地块本来就什么都能做",
                        DistrictRuleException.params("playerName", String.valueOf(plot.ownerName())));
            }
            List<FriendRecord> friends = repo.friendsOf(plot.plotId());
            Optional<FriendRecord> existing = friends.stream()
                    .filter(friend -> friend.collidesWith(resolved.uuid(), nameLower))
                    .findFirst();
            if (existing.isPresent()) {
                FriendRecord friend = existing.get();
                throw new DistrictRuleException(DistrictError.ALREADY_FRIEND, friend.suspended()
                        ? friend.name() + " 已经在朋友名单上（已暂停），点 TA 旁边的“恢复”就行"
                        : friend.name() + " 已经是这块地的朋友了",
                        DistrictRuleException.params("playerName", friend.name(),
                                "suspended", String.valueOf(friend.suspended())));
            }
            if (friends.size() >= DistrictLimits.FRIEND_LIMIT) {
                throw new DistrictRuleException(DistrictError.FRIEND_LIMIT_REACHED,
                        "朋友已满 " + DistrictLimits.FRIEND_LIMIT + " 人，先移除一位再加",
                        DistrictRuleException.params("limit", String.valueOf(DistrictLimits.FRIEND_LIMIT)));
            }
            if (!resolved.known() && !allowNeverJoined) {
                throw new DistrictRuleException(DistrictError.PLAYER_NEVER_JOINED, "没有找到 " + name + " 的登录记录",
                        DistrictRuleException.params("playerName", DistrictTexts.echo(name)));
            }
            FriendRecord draft = new FriendRecord(0, plot.plotId(), resolved.uuid(), resolved.name(), at, actor.name(),
                    resolved.known() ? FriendSyncStatus.SYNCED : FriendSyncStatus.PENDING, null);
            long id = repo.insertFriend(draft);
            PlotLogEntry log = writePlotLog(ownerLog(gate, actor, at, PlotLogAction.ADD_FRIEND, resolved.name(), null,
                    null));
            schedulePlotPush(plot);
            notifyFriendChange(gate, actor, resolved.uuid(), resolved.name(),
                    DistrictNoticeKind.PLOT_ADMIN_FRIEND_ADDED, DistrictNoticeKind.FRIEND_ADDED);
            FriendRecord friend = new FriendRecord(id, draft.plotId(), draft.uuid(), draft.name(), draft.addedAt(),
                    draft.addedByName(), draft.syncStatus(), null);
            return new Added(gate.relation(), new FriendResult(friend, isResident(gate.district(), friend), log));
        });
        auditIfAdmin(actor, added.relation(), true, "added friend " + added.result().friend().name()
                + " to plot " + plotId);
        return added.result();
    }

    public PlotLogEntry removeFriend(Actor actor, String districtId, String plotId, @Nullable String typedName) {
        requireNoOpenTransaction("plot.removeFriend");
        sweep(districtId);
        long at = ctx.now();
        record Removed(PlotRelation relation, PlotLogEntry log) {
        }
        Removed removed = repo.inTransaction(() -> {
            Gate gate = gate(actor, districtId, plotId, DistrictActionNames.PLOT_REMOVE_FRIEND);
            FriendRecord friend = requireFriend(gate.plot(), typedName);
            repo.deleteFriend(friend.id());
            PlotLogEntry log = writePlotLog(ownerLog(gate, actor, at, PlotLogAction.REMOVE_FRIEND, friend.name(), null,
                    null));
            schedulePlotPush(gate.plot());
            notifyFriendChange(gate, actor, friend.uuid(), friend.name(),
                    DistrictNoticeKind.PLOT_ADMIN_FRIEND_REMOVED, DistrictNoticeKind.FRIEND_REMOVED);
            return new Removed(gate.relation(), log);
        });
        auditIfAdmin(actor, removed.relation(), true, "removed friend " + typedName + " from plot " + plotId);
        return removed.log();
    }

    public FriendResult restoreFriend(Actor actor, String districtId, String plotId, @Nullable String typedName) {
        requireNoOpenTransaction("plot.restoreFriend");
        sweep(districtId);
        long at = ctx.now();
        record Restored(PlotRelation relation, FriendResult result) {
        }
        Restored restored = repo.inTransaction(() -> {
            Gate gate = gate(actor, districtId, plotId, DistrictActionNames.PLOT_RESTORE_FRIEND);
            FriendRecord friend = requireFriend(gate.plot(), typedName);
            if (!friend.suspended()) {
                throw new DistrictRuleException(DistrictError.FRIEND_NOT_SUSPENDED,
                        friend.name() + " 的朋友身份没有暂停，不需要恢复",
                        DistrictRuleException.params("playerName", friend.name()));
            }
            repo.setFriendSuspended(friend.id(), null);
            PlotLogEntry log = writePlotLog(ownerLog(gate, actor, at, PlotLogAction.RESTORE_FRIEND, friend.name(), null,
                    null));
            schedulePlotPush(gate.plot());
            notifyFriendChange(gate, actor, friend.uuid(), friend.name(),
                    DistrictNoticeKind.PLOT_ADMIN_FRIEND_RESTORED, DistrictNoticeKind.FRIEND_RESTORED);
            FriendRecord restoredFriend = new FriendRecord(friend.id(), friend.plotId(), friend.uuid(), friend.name(),
                    friend.addedAt(), friend.addedByName(), friend.syncStatus(), null);
            return new Restored(gate.relation(),
                    new FriendResult(restoredFriend, isResident(gate.district(), restoredFriend), log));
        });
        auditIfAdmin(actor, restored.relation(), true, "restored friend " + typedName + " on plot " + plotId);
        return restored.result();
    }

    /** 按名字 (不分大小写, 不校验格式) 找朋友; 没有就报 FRIEND_NOT_FOUND。 */
    private FriendRecord requireFriend(PlotRecord plot, @Nullable String typedName) {
        String nameLower = typedName == null ? "" : DistrictTexts.lower(typedName.trim());
        return repo.friendsOf(plot.plotId()).stream()
                .filter(friend -> DistrictTexts.lower(friend.name()).equals(nameLower))
                .findFirst()
                .orElseThrow(() -> new DistrictRuleException(DistrictError.FRIEND_NOT_FOUND,
                        DistrictTexts.echo(typedName) + " 不是这块地的朋友",
                        DistrictRuleException.params("playerName", DistrictTexts.echo(typedName))));
    }

    /**
     * 朋友是否本区名单上的人 (PlotFriend.isResident): 按 UUID; 按名字 (不分大小写) 只在朋友行还是待生效 (只有名字) 时才认。
     */
    boolean isResident(DistrictRecord district, FriendRecord friend) {
        return memberOfDistrict(district, friend.uuid()).isPresent()
                || (friend.syncStatus() == FriendSyncStatus.PENDING
                && memberOfDistrict(district, friend.name()).isPresent());
    }

    /** 户主或管理员改朋友、权限: 管理员改的一律标"代改" (户主一眼要看出不是自己改的)。 */
    private static PlotLogEntry ownerLog(Gate gate, Actor actor, long at, PlotLogAction action,
                                         @Nullable String target, @Nullable String reason,
                                         @Nullable PermissionChange permission) {
        boolean admin = gate.relation() == PlotRelation.ADMIN;
        return new PlotLogEntry(0, gate.plot().plotId(), gate.district().districtId(), gate.plot().tenure(), at,
                actor.uuid(), actor.name(), admin ? PlotActorRole.ADMIN : PlotActorRole.OWNER, action, target, reason,
                permission, null, admin);
    }

    /** 管理员代改时给户主一条 plot_admin.* (22.10); 户主自己改的不发。 */
    private void notifyOwnerIfAdmin(Gate gate, Actor actor, DistrictNoticeKind kind, List<String> args) {
        if (gate.relation() == PlotRelation.ADMIN) {
            ctx.notices().enqueueUnlessSelf(actor, gate.plot().ownerUuid(), kind, args, gate.district().districtId(),
                    gate.plot().plotId());
        }
    }

    /**
     * 朋友的加、移、恢复 (22.10): 管理员代改时户主收 plot_admin.*; 朋友那一侧照样收 friend_*, 它与谁操作无关 (待生效的
     * 朋友按离线 UUID 入队, 首次登录换键之后收到)。
     */
    private void notifyFriendChange(Gate gate, Actor actor, UUID friendUuid, String friendName,
                                    DistrictNoticeKind toOwner, DistrictNoticeKind toFriend) {
        PlotRecord plot = gate.plot();
        notifyOwnerIfAdmin(gate, actor, toOwner, List.of(actor.name(), friendName, plot.code()));
        ctx.notices().enqueueUnlessSelf(actor, friendUuid, toFriend,
                List.of(String.valueOf(plot.ownerName()), plot.code()), gate.district().districtId(), plot.plotId());
    }

    /** 地块先写临时失败, 提交后整块重写并回写真值。 */
    private void schedulePlotPush(PlotRecord plot) {
        repo.setPlotSync(plot.plotId(), PlotSyncStatus.FAILED, DistrictTexts.SYNC_INTERRUPTED);
        repo.afterCommit(() -> ctx.flanSync().writePlotState(plot.plotId()));
    }

    private static void auditIfAdmin(Actor actor, PlotRelation relation, boolean changed, String what) {
        if (changed && relation == PlotRelation.ADMIN) {
            AUDIT.info("[miningdim] {} ({}) on behalf of the owner: {}", actor.name(), actor.uuid(), what);
        }
    }
}
