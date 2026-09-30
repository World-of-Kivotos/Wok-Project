package com.miningdim.district.service;

import com.miningdim.district.access.Actor;
import com.miningdim.district.access.DistrictAccess;
import com.miningdim.district.core.DistrictActionNames;
import com.miningdim.district.core.DistrictAudience;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionCells;
import com.miningdim.district.core.PermissionChange;
import com.miningdim.district.core.PermissionItemDef;
import com.miningdim.district.core.PermissionScope;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 公共区域开关表: 改一格 (district.setPermission)、恢复默认 (district.resetPermissions)。区务长只改本区住户 / 外人两列, 区域规则只有管理员能改。
 * 开关值只收布尔: 缺省、null、字符串一律拒绝, 绝不写成"不设置" (写进 Flan 的"不设置"会回退到上一层)。
 */
public final class DistrictPermissionService extends ServiceSupport {

    /** district.setPermission 的结果: item 是改完 (或没改) 之后这一项的整行值; 值没变时 logEntry 为 null。 */
    public record SetResult(PermissionItemDef item, PermissionCells cells, DistrictAccess access,
                            @Nullable DistrictLogEntry logEntry) {
    }

    /** district.resetPermissions 的结果: 改完之后的整张表与每改一格一条的记录 (一格都没改时为空)。 */
    public record ResetResult(DistrictRecord district, PermissionCells cells, DistrictAccess access,
                              List<DistrictLogEntry> logEntries) {
    }

    private record CellChange(PermissionItemDef item, DistrictAudience audience, boolean from, boolean to) {
    }

    DistrictPermissionService(DistrictContext ctx) {
        super(ctx);
    }

    /**
     * @param audience wire 值 (resident / outsider / district)
     * @param enabled  不是 JSON 布尔时为 null
     */
    public SetResult setPermission(Actor actor, String districtId, String permissionId, @Nullable String audience,
                                   @Nullable Boolean enabled) {
        requireNoOpenTransaction("setPermission");
        sweep(districtId);
        long at = ctx.now();
        SetResult result = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            DistrictAccess actorAccess = access.access(actor, district);
            if (!actorAccess.manager()) {
                throw denied(DistrictActionNames.SET_PERMISSION, "manager",
                        "只有本区区务长或管理员可以改本区公共区域的权限");
            }
            PermissionItemDef item = PermissionCatalog.find(permissionId).orElseThrow(() -> unknownItem(permissionId));
            DistrictAudience column = DistrictAudience.fromWire(audience);
            if (column == null || !column.appliesTo(item.scope())) {
                throw DistrictRuleException.invalidRequest("audience", audience);
            }
            if (item.scope() == PermissionScope.REGION && actorAccess != DistrictAccess.ADMIN) {
                throw denied(DistrictActionNames.SET_PERMISSION, "admin", "区域规则只有管理员可以改");
            }
            if (enabled == null) {
                throw DistrictRuleException.invalidRequest("enabled", null);
            }
            PermissionCells before = repo.districtCells(district.districtId());
            boolean from = before.district(item, column);
            if (from == enabled) {
                // 本来就是这个值 (比如两个人同时点了同一格): 不写库、不写记录, 照常回当前值。
                return new SetResult(item, before, actorAccess, null);
            }
            repo.setDistrictCell(district.districtId(), item.permissionId(), column.wire(), enabled);
            DistrictLogEntry log = writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at, actor.uuid(),
                    actor.name(), managerRole(actorAccess), DistrictLogAction.PERMISSION, null, null,
                    new PermissionChange(item.permissionId(), item.label(), column.wire(), from, enabled), null));
            repo.afterCommit(() -> ctx.flanSync().writeDistrictState(district.districtId()));
            return new SetResult(item, repo.districtCells(district.districtId()), actorAccess, log);
        });
        if (result.logEntry() != null) {
            AUDIT.info("[miningdim] {} ({}) set {}/{} of district {} to {}", actor.name(), actor.uuid(),
                    permissionId, audience, districtId, enabled);
        }
        return result;
    }

    /** @param scope wire 值: member (住户列与外人列) 或 all (再加全区列, 只有管理员) */
    public ResetResult resetPermissions(Actor actor, String districtId, @Nullable String scope) {
        requireNoOpenTransaction("resetPermissions");
        sweep(districtId);
        long at = ctx.now();
        ResetResult result = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            DistrictAccess actorAccess = access.access(actor, district);
            if (!actorAccess.manager()) {
                throw denied(DistrictActionNames.RESET_PERMISSIONS, "manager",
                        "只有本区区务长或管理员可以恢复本区的默认权限");
            }
            if (!"member".equals(scope) && !"all".equals(scope)) {
                throw DistrictRuleException.invalidRequest("scope", scope);
            }
            boolean all = "all".equals(scope);
            if (all && actorAccess != DistrictAccess.ADMIN) {
                throw denied(DistrictActionNames.RESET_PERMISSIONS, "admin",
                        "区域规则只有管理员可以改，区务长只能恢复住户和外人的开关");
            }
            PermissionCells cells = repo.districtCells(district.districtId());
            List<CellChange> changes = new ArrayList<>();
            for (PermissionItemDef item : PermissionCatalog.items()) {
                if (item.region() && !all) {
                    continue;
                }
                for (DistrictAudience column : DistrictAudience.values()) {
                    if (!column.appliesTo(item.scope())) {
                        continue;
                    }
                    boolean from = cells.district(item, column);
                    boolean to = item.districtDefault(column);
                    if (from != to) {
                        changes.add(new CellChange(item, column, from, to));
                    }
                }
            }
            List<DistrictLogEntry> logs = new ArrayList<>();
            for (CellChange change : changes) {
                repo.setDistrictCell(district.districtId(), change.item().permissionId(), change.audience().wire(),
                        change.to());
                logs.add(writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at, actor.uuid(),
                        actor.name(), managerRole(actorAccess), DistrictLogAction.PERMISSION, null,
                        DistrictTexts.RESTORE_DEFAULT, new PermissionChange(change.item().permissionId(),
                        change.item().label(), change.audience().wire(), change.from(), change.to()), null)));
            }
            if (!changes.isEmpty()) {
                // 按库把父领地整块比一遍: 改了几格都只推一次, 只写不同的格子 (跟随项一起)。
                repo.afterCommit(() -> ctx.flanSync().writeDistrictState(district.districtId()));
            }
            return new ResetResult(district, repo.districtCells(district.districtId()), actorAccess, List.copyOf(logs));
        });
        if (!result.logEntries().isEmpty()) {
            AUDIT.info("[miningdim] {} ({}) reset {} permission cell(s) of district {} (scope {})", actor.name(),
                    actor.uuid(), result.logEntries().size(), districtId, scope);
        }
        return result;
    }

    static DistrictRuleException unknownItem(@Nullable String permissionId) {
        return new DistrictRuleException(DistrictError.PERMISSION_ITEM_UNKNOWN, "没有这一项权限，页面可能过期了，请刷新",
                DistrictRuleException.params("permissionId", DistrictTexts.echo(permissionId)));
    }
}
