package com.miningdim.district.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.district.DistrictLimits;
import com.miningdim.district.access.Abilities;
import com.miningdim.district.access.DistrictAccess;
import com.miningdim.district.access.GlobalRole;
import com.miningdim.district.access.PlotRelation;
import com.miningdim.district.core.Academy;
import com.miningdim.district.core.AreaChange;
import com.miningdim.district.core.DistrictAudience;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionCells;
import com.miningdim.district.core.PermissionChange;
import com.miningdim.district.core.PermissionItemDef;
import com.miningdim.district.core.PermissionScope;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotAudience;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotStatus;
import com.miningdim.district.service.DistrictQueryService;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * 自管区回执的构造与按身份裁剪 (设计文档 14.2)。裁剪规则只在这一处: 服务层的快照不裁剪, 只带上裁剪需要的身份。
 *
 * 字段名与类型逐字对应前端读取的形状 (webui/src/lib/types.ts 的自管区一节)。一律用 serializeNulls 的 Gson 写出:
 * 可空字段写 JSON null, 不许省略键 —— 前端对"没有这个键"和"值是 null"的处理不同 (前者是契约破裂)。
 *
 * 裁剪按"对这个区的身份" ({@link DistrictAccess}) 或"对这块地的关系" ({@link PlotRelation}) 判, 不按全局身份:
 * 别区的区务长在这里就是外人。列表按预算 ({@link ResponseBudget}) 装入, 装不下的置对应的截断标记 (★ 字段, 前端
 * 不认识时忽略)。
 */
final class DistrictJson {

    static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private DistrictJson() {
    }

    static String write(JsonElement element) {
        return GSON.toJson(element);
    }

    // ================================================================
    // 共享子结构
    // ================================================================

    static JsonElement plotRef(@Nullable PlotRecord plot) {
        if (plot == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject ref = new JsonObject();
        ref.addProperty("plotId", plot.plotId());
        ref.addProperty("code", plot.code());
        return ref;
    }

    static JsonObject bounds(DistrictBounds bounds) {
        JsonObject json = new JsonObject();
        json.addProperty("dimension", bounds.dimension());
        json.addProperty("minX", bounds.minX());
        json.addProperty("minZ", bounds.minZ());
        json.addProperty("maxX", bounds.maxX());
        json.addProperty("maxZ", bounds.maxZ());
        return json;
    }

    /** 地块的范围带上所在自管区的维度 (与自管区范围同形)。 */
    static JsonObject plotBounds(String dimension, PlotArea area) {
        return bounds(new DistrictBounds(dimension, area.minX(), area.minZ(), area.maxX(), area.maxZ()));
    }

    static JsonElement area(@Nullable PlotArea area) {
        if (area == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.addProperty("minX", area.minX());
        json.addProperty("minZ", area.minZ());
        json.addProperty("maxX", area.maxX());
        json.addProperty("maxZ", area.maxZ());
        return json;
    }

    private static JsonElement areaChange(@Nullable AreaChange change) {
        if (change == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.add("from", area(change.from()));
        json.add("to", area(change.to()));
        return json;
    }

    private static JsonElement permissionChange(@Nullable PermissionChange change) {
        if (change == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.addProperty("permissionId", change.permissionId());
        json.addProperty("label", change.label());
        json.addProperty("audience", change.audience());
        json.addProperty("from", change.from());
        json.addProperty("to", change.to());
        return json;
    }

    static JsonElement districtLog(@Nullable DistrictLogEntry entry) {
        if (entry == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.addProperty("entryId", entry.entryId());
        json.addProperty("at", entry.at());
        json.addProperty("actorName", entry.actorName());
        json.addProperty("actorRole", entry.actorRole().wire());
        json.addProperty("action", entry.action().wire());
        json.addProperty("targetName", entry.targetName());
        json.addProperty("reason", entry.reason());
        json.add("permission", permissionChange(entry.permission()));
        json.add("area", areaChange(entry.area()));
        return json;
    }

    static JsonElement plotLog(@Nullable PlotLogEntry entry) {
        if (entry == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.addProperty("entryId", entry.entryId());
        json.addProperty("at", entry.at());
        json.addProperty("actorName", entry.actorName());
        json.addProperty("actorRole", entry.actorRole().wire());
        json.addProperty("action", entry.action().wire());
        json.addProperty("targetName", entry.targetName());
        json.addProperty("reason", entry.reason());
        json.add("permission", permissionChange(entry.permission()));
        json.add("area", areaChange(entry.area()));
        json.addProperty("onBehalfOfOwner", entry.onBehalfOfOwner());
        return json;
    }

    /**
     * 按预算把一串条目装进列表; 全部装下返回 true。
     */
    private static <T> boolean fill(ResponseBudget budget, JsonArray target, List<T> items,
                                    Function<T, JsonElement> toJson) {
        for (T item : items) {
            if (!budget.tryAdd(target, toJson.apply(item))) {
                return false;
            }
        }
        return true;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    // ================================================================
    // district.state
    // ================================================================

    static JsonObject state(DistrictQueryService.StateView view) {
        boolean admin = view.role() == GlobalRole.ADMIN;
        JsonObject viewer = new JsonObject();
        viewer.addProperty("playerName", view.viewer().name());
        viewer.addProperty("role", view.role().wire());

        JsonArray districts = new JsonArray();
        for (DistrictQueryService.DistrictSummaryView summary : view.districts()) {
            districts.add(summary(summary, admin));
        }
        JsonArray friendOf = new JsonArray();
        JsonObject result = new JsonObject();
        result.add("viewer", viewer);
        result.add("residency", residency(view.residency()));
        result.add("districts", districts);
        result.add("friendOf", friendOf);
        result.addProperty("friendOfTruncated", view.friendOfTruncated());
        ResponseBudget budget = ResponseBudget.startingWith(result);
        if (!fill(budget, friendOf, view.friendOf(), DistrictJson::friendship)) {
            result.addProperty("friendOfTruncated", true);
        }
        return result;
    }

    /** 自管区的公开摘要; syncIssues 只给管理员。 */
    static JsonObject summary(DistrictQueryService.DistrictSummaryView summary, boolean admin) {
        DistrictRecord district = summary.district();
        JsonObject json = new JsonObject();
        json.addProperty("districtId", district.districtId());
        json.addProperty("displayName", district.displayName());
        json.addProperty("academyName", summary.academy().shortName());
        json.addProperty("academyFullName", summary.academy().fullName());
        json.addProperty("wardenName", district.wardenName());
        json.addProperty("residentCount", summary.residentCount());
        json.addProperty("area", district.bounds().area());
        json.addProperty("plotCount", summary.plotCount());
        json.addProperty("vacantPlotCount", summary.vacantPlotCount());
        if (admin) {
            JsonObject issues = new JsonObject();
            issues.addProperty("pending", summary.syncIssues().pending());
            issues.addProperty("failed", summary.syncIssues().failed());
            json.add("syncIssues", issues);
        } else {
            json.add("syncIssues", JsonNull.INSTANCE);
        }
        return json;
    }

    private static JsonElement residency(@Nullable DistrictQueryService.ResidencyView residency) {
        if (residency == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.addProperty("districtId", residency.district().districtId());
        json.addProperty("districtName", residency.district().displayName());
        json.addProperty("academyName", residency.academy().shortName());
        json.addProperty("academyFullName", residency.academy().fullName());
        json.addProperty("grantedAt", residency.member().joinedAt());
        json.addProperty("grantedBy", residency.member().addedByName());
        json.addProperty("isWarden", residency.isWarden());
        json.addProperty("syncStatus", residency.member().syncStatus().wire());
        json.add("plot", plotRef(residency.plot()));
        return json;
    }

    private static JsonElement friendship(DistrictQueryService.FriendshipView view) {
        JsonObject json = new JsonObject();
        json.addProperty("districtId", view.district().districtId());
        json.addProperty("plotId", view.plot().plotId());
        json.addProperty("code", view.plot().code());
        json.addProperty("ownerName", view.plot().ownerName());
        json.addProperty("syncStatus", view.friend().syncStatus().wire());
        json.addProperty("suspended", view.friend().suspended());
        return json;
    }

    // ================================================================
    // district.detail
    // ================================================================

    /** residents / log 只给管理员与本区区务长; 住户拿到的是 null (必须和空列表区分开)。 */
    static JsonObject detail(DistrictQueryService.DetailView view) {
        boolean manager = view.access().manager();
        JsonArray residents = new JsonArray();
        JsonArray log = new JsonArray();
        JsonObject result = new JsonObject();
        result.add("district", districtInfo(view.summary()));
        result.add("abilities", abilities(view.abilities()));
        result.add("residents", manager ? residents : JsonNull.INSTANCE);
        result.add("log", manager ? log : JsonNull.INSTANCE);
        result.addProperty("residentsTruncated", false);
        result.addProperty("logTruncated", view.logTruncated());
        if (!manager) {
            return result;
        }
        ResponseBudget budget = ResponseBudget.startingWith(result);
        boolean admin = view.access() == DistrictAccess.ADMIN;
        List<DistrictQueryService.ResidentView> rows = view.residents() == null ? List.of() : view.residents();
        if (!fill(budget, residents, rows, row -> resident(row, admin))) {
            result.addProperty("residentsTruncated", true);
        }
        List<DistrictLogEntry> entries = view.log() == null ? List.of() : view.log();
        if (!fill(budget, log, entries, DistrictJson::districtLog)) {
            result.addProperty("logTruncated", true);
        }
        return result;
    }

    private static JsonObject districtInfo(DistrictQueryService.DistrictSummaryView summary) {
        DistrictRecord district = summary.district();
        JsonObject json = new JsonObject();
        json.addProperty("districtId", district.districtId());
        json.addProperty("displayName", district.displayName());
        json.addProperty("academyName", summary.academy().shortName());
        json.addProperty("academyFullName", summary.academy().fullName());
        json.addProperty("wardenName", district.wardenName());
        json.add("bounds", bounds(district.bounds()));
        json.addProperty("area", district.bounds().area());
        json.addProperty("residentCount", summary.residentCount());
        json.add("rules", strings(district.rules()));
        json.addProperty("createdAt", district.createdAt());
        json.addProperty("plotCount", summary.plotCount());
        json.addProperty("vacantPlotCount", summary.vacantPlotCount());
        return json;
    }

    private static JsonObject abilities(Abilities abilities) {
        JsonObject json = new JsonObject();
        json.addProperty("build", abilities.build());
        json.addProperty("interact", abilities.interact());
        json.addProperty("manageResidents", abilities.manageResidents());
        json.addProperty("viewRoster", abilities.viewRoster());
        json.addProperty("editClaim", abilities.editClaim());
        json.addProperty("deleteDistrict", abilities.deleteDistrict());
        json.addProperty("appointWarden", abilities.appointWarden());
        json.addProperty("retrySync", abilities.retrySync());
        json.addProperty("managePermissions", abilities.managePermissions());
        json.addProperty("manageRegionRules", abilities.manageRegionRules());
        json.addProperty("viewPlotList", abilities.viewPlotList());
        json.addProperty("viewPlotStatus", abilities.viewPlotStatus());
        json.addProperty("inspectPlots", abilities.inspectPlots());
        json.addProperty("overridePlots", abilities.overridePlots());
        json.addProperty("managePlots", abilities.managePlots());
        json.addProperty("managePlotMarket", abilities.managePlotMarket());
        json.addProperty("manageFrozenPlots", abilities.manageFrozenPlots());
        return json;
    }

    /** 名单一行; syncError 只给管理员, 区务长看到的恒为 null。 */
    static JsonObject resident(DistrictQueryService.ResidentView view, boolean admin) {
        JsonObject json = new JsonObject();
        json.addProperty("playerName", view.member().name());
        json.addProperty("joinedAt", view.member().joinedAt());
        json.addProperty("addedBy", view.member().addedByName());
        json.addProperty("lastSeenAt", view.lastSeenAt());
        json.addProperty("syncStatus", view.member().syncStatus().wire());
        json.addProperty("syncError", admin ? view.member().syncError() : null);
        json.addProperty("isWarden", view.isWarden());
        json.add("plot", plotRef(view.plot()));
        json.addProperty("friendOfPlotCount", view.friendOfPlotCount());
        return json;
    }

    // ================================================================
    // district.permissions (与 district.setPermission / district.resetPermissions 的回执)
    // ================================================================

    static JsonObject permissions(DistrictRecord district, DistrictAccess access, PermissionCells cells) {
        JsonObject editable = new JsonObject();
        editable.addProperty("member", access.manager());
        editable.addProperty("region", access == DistrictAccess.ADMIN);
        JsonArray groups = new JsonArray();
        for (PermissionCatalog.Group group : PermissionCatalog.GROUPS) {
            JsonArray items = new JsonArray();
            for (PermissionItemDef item : group.items()) {
                items.add(permissionItem(item, cells, access));
            }
            JsonObject json = new JsonObject();
            json.addProperty("groupId", group.groupId());
            json.addProperty("label", group.label());
            json.addProperty("scope", group.scope().wire());
            json.add("items", items);
            groups.add(json);
        }
        JsonArray fixedRules = new JsonArray();
        for (PermissionCatalog.FixedRule rule : PermissionCatalog.FIXED_RULES) {
            JsonObject json = new JsonObject();
            json.addProperty("ruleId", rule.ruleId());
            json.addProperty("label", rule.label());
            json.addProperty("valueText", rule.valueText());
            json.addProperty("detail", rule.detail());
            fixedRules.add(json);
        }
        JsonObject result = new JsonObject();
        result.addProperty("districtId", district.districtId());
        result.add("editable", editable);
        result.add("groups", groups);
        result.add("fixedRules", fixedRules);
        return result;
    }

    /**
     * 公共区域开关的一项, 按对这个区的身份裁剪: 住户列只给本区的人 (NONE 为 null); 外人列与全区列对谁都公开;
     * flanIds / flanInverted 与 districtRisk 只给管理员; outsiderRisk 只给管理者。member 项的全区列、region 项的
     * 住户与外人列结构上恒为 null。
     */
    static JsonObject permissionItem(PermissionItemDef item, PermissionCells cells, DistrictAccess access) {
        boolean admin = access == DistrictAccess.ADMIN;
        JsonObject json = new JsonObject();
        json.addProperty("permissionId", item.permissionId());
        json.addProperty("label", item.label());
        json.addProperty("detail", item.detail());
        json.addProperty("scope", item.scope().wire());
        json.add("current", districtValues(item, access, audience -> cells.district(item, audience)));
        json.add("defaults", districtValues(item, access, item::districtDefault));
        if (admin) {
            json.add("flanIds", strings(item.flanIds()));
            json.addProperty("flanInverted", item.flanInverted());
        } else {
            json.add("flanIds", JsonNull.INSTANCE);
            json.add("flanInverted", JsonNull.INSTANCE);
        }
        json.addProperty("outsiderRisk", access.manager() ? item.outsiderRisk() : null);
        json.addProperty("districtRisk", admin ? item.districtRisk() : null);
        return json;
    }

    private static JsonObject districtValues(PermissionItemDef item, DistrictAccess access,
                                             Function<DistrictAudience, Boolean> value) {
        boolean member = item.scope() == PermissionScope.MEMBER;
        JsonObject json = new JsonObject();
        json.addProperty("resident", member && access != DistrictAccess.NONE
                ? value.apply(DistrictAudience.RESIDENT) : null);
        json.addProperty("outsider", member ? value.apply(DistrictAudience.OUTSIDER) : null);
        json.addProperty("district", member ? null : value.apply(DistrictAudience.DISTRICT));
        return json;
    }

    /**
     * district.resetPermissions 的回执: 改完后的整张表先装 (必需), 每改一格一条的记录按预算装; 装不下时置 ★logEntriesTruncated,
     * ★changedCount 永远是实际改动的格数 (界面按它写"已把 N 处恢复成默认值")。
     */
    static JsonObject resetPermissions(DistrictRecord district, DistrictAccess access, PermissionCells cells,
                                       List<DistrictLogEntry> entries) {
        JsonArray logEntries = new JsonArray();
        JsonObject result = new JsonObject();
        result.add("permissions", permissions(district, access, cells));
        result.add("logEntries", logEntries);
        result.addProperty("changedCount", entries.size());
        result.addProperty("logEntriesTruncated", false);
        ResponseBudget budget = ResponseBudget.startingWith(result);
        if (!fill(budget, logEntries, entries, DistrictJson::districtLog)) {
            result.addProperty("logEntriesTruncated", true);
        }
        return result;
    }

    // ================================================================
    // district.plots
    // ================================================================

    static JsonObject plots(DistrictQueryService.PlotsView view) {
        DistrictRecord district = view.district();
        DistrictAccess access = view.access();
        JsonArray plots = new JsonArray();
        JsonArray deleted = new JsonArray();
        JsonObject result = new JsonObject();
        result.addProperty("districtId", district.districtId());
        result.add("bounds", bounds(district.bounds()));
        result.addProperty("myPlotId", view.myPlot() == null ? null : view.myPlot().plotId());
        result.add("residentDefaults", strings(view.residentDefaults()));
        result.add("rules", rules(district));
        result.add("market", market(view.market()));
        result.add("plots", plots);
        result.add("deletedPlots", view.tombstones() == null ? JsonNull.INSTANCE : deleted);
        result.addProperty("plotsTruncated", false);
        result.addProperty("deletedPlotsTruncated", view.tombstonesTruncated());

        ResponseBudget budget = ResponseBudget.startingWith(result);
        if (!fill(budget, plots, view.plots(), summary -> plotSummary(district, summary, access))) {
            result.addProperty("plotsTruncated", true);
        }
        if (view.tombstones() != null) {
            for (DistrictQueryService.TombstoneView tombstone : view.tombstones()) {
                JsonArray log = new JsonArray();
                JsonObject json = new JsonObject();
                json.addProperty("plotId", tombstone.tombstone().plotId());
                json.addProperty("code", tombstone.tombstone().code());
                json.add("bounds", plotBounds(district.bounds().dimension(), tombstone.tombstone().area()));
                json.addProperty("deletedAt", tombstone.tombstone().deletedAt());
                json.addProperty("deletedBy", tombstone.tombstone().deletedByName());
                json.add("log", log);
                json.addProperty("logTruncated", tombstone.logTruncated());
                if (!budget.tryAdd(deleted, json)) {
                    result.addProperty("deletedPlotsTruncated", true);
                    break;
                }
                // 墓碑已经装进去了 (它的骨架已计入), 它的记录逐条再算。
                if (!fill(budget, log, tombstone.log(), DistrictJson::plotLog)) {
                    json.addProperty("logTruncated", true);
                    result.addProperty("deletedPlotsTruncated", true);
                    break;
                }
            }
        }
        return result;
    }

    static JsonObject rules(DistrictRecord district) {
        JsonObject json = new JsonObject();
        json.addProperty("edgeGap", DistrictLimits.EDGE_GAP);
        json.addProperty("minSide", district.minSide());
        json.addProperty("maxSide", district.maxSide());
        return json;
    }

    private static JsonObject market(DistrictQueryService.MarketView market) {
        JsonObject json = new JsonObject();
        json.addProperty("unitPrice", market.unitPrice());
        json.addProperty("open", market.open());
        json.addProperty("viewerBlock", market.viewerBlock() == null ? null : market.viewerBlock().wire());
        json.addProperty("viewerBalance", market.viewerBalance());
        return json;
    }

    /**
     * 地块列表一行, 按对这个区的身份裁剪: 朋友数、生效状态与 canRestore 只给管理者 (住户为 null), 失败原因只给管理员。
     */
    static JsonObject plotSummary(DistrictRecord district, DistrictQueryService.PlotSummaryView view,
                                  DistrictAccess access) {
        boolean manager = access.manager();
        boolean admin = access == DistrictAccess.ADMIN;
        PlotRecord plot = view.plot();
        JsonObject json = new JsonObject();
        json.addProperty("plotId", plot.plotId());
        json.addProperty("code", plot.code());
        json.addProperty("status", plot.status().wire());
        json.addProperty("ownerName", plot.status() == PlotStatus.OWNED ? plot.ownerName() : null);
        json.add("frozen", freeze(view.freeze(), manager));
        json.add("bounds", plotBounds(district.bounds().dimension(), plot.area()));
        json.addProperty("area", view.area());
        json.addProperty("price", view.price());
        json.addProperty("friendCount", manager ? view.friendCount() : null);
        json.addProperty("syncStatus", manager ? plot.syncStatus().wire() : null);
        json.addProperty("syncError", admin ? plot.syncError() : null);
        json.add("openToResidents", strings(view.openToResidents()));
        json.addProperty("residentColumnIsDefault", view.residentColumnIsDefault());
        return json;
    }

    private static JsonElement freeze(@Nullable DistrictQueryService.FreezeView freeze, boolean showRestore) {
        if (freeze == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.addProperty("formerOwnerName", freeze.formerOwnerName());
        json.addProperty("frozenAt", freeze.frozenAt());
        json.addProperty("reclaimAt", freeze.reclaimAt());
        json.addProperty("canRestore", showRestore ? freeze.canRestore() : null);
        return json;
    }

    // ================================================================
    // plot.detail (与 plot.setPermission / plot.resetPermissions / plot.addFriend / plot.restoreFriend 的回执)
    // ================================================================

    /**
     * 一块地的朋友、三列与记录。户主只拿到本任期的记录, 管理员另外拿到历任的归档 (服务层已按关系取好);
     * syncError、flanIds 只给管理员 (按对这块地的关系判)。
     */
    static JsonObject plotDetail(DistrictQueryService.PlotDetailView view) {
        boolean admin = view.relation() == PlotRelation.ADMIN;
        PlotRecord plot = view.plot();
        DistrictRecord district = view.district();
        JsonObject info = new JsonObject();
        info.addProperty("plotId", plot.plotId());
        info.addProperty("code", plot.code());
        info.addProperty("districtId", district.districtId());
        info.addProperty("status", plot.status().wire());
        info.addProperty("ownerName", plot.status() == PlotStatus.OWNED ? plot.ownerName() : null);
        info.add("frozen", freeze(view.freeze(), admin));
        info.add("bounds", plotBounds(district.bounds().dimension(), plot.area()));
        info.addProperty("area", plot.area().area());
        info.addProperty("syncStatus", plot.syncStatus().wire());
        info.addProperty("syncError", admin ? plot.syncError() : null);

        JsonArray friends = new JsonArray();
        for (DistrictQueryService.FriendView friend : view.friends()) {
            friends.add(friend(friend.friend(), friend.isResident()));
        }
        JsonArray log = new JsonArray();
        JsonObject result = new JsonObject();
        result.add("plot", info);
        result.addProperty("viewerRelation", view.relation().wire());
        result.addProperty("editable", view.editable());
        result.addProperty("friendLimit", DistrictLimits.FRIEND_LIMIT);
        result.add("friends", friends);
        result.add("groups", plotGroups(view.cells(), admin));
        result.add("log", log);
        result.addProperty("logTruncated", view.logTruncated());
        ResponseBudget budget = ResponseBudget.startingWith(result);
        if (!fill(budget, log, view.log(), DistrictJson::plotLog)) {
            result.addProperty("logTruncated", true);
        }
        return result;
    }

    /**
     * plot.resetPermissions 的回执: 恢复之后的整块地与每改一格一条的记录。先装详情的必需部分与改动记录, 详情里的地块记录用剩下的预算
     * (新写的那几条本来就在最前面); 改动记录装不下时置 ★logEntriesTruncated, ★changedCount 永远是实际改动的格数。
     */
    static JsonObject resetPlotPermissions(DistrictQueryService.PlotDetailView view, List<PlotLogEntry> entries) {
        JsonObject detailSkeleton = plotDetail(withoutLog(view));
        JsonArray logEntries = new JsonArray();
        JsonObject result = new JsonObject();
        result.add("detail", detailSkeleton);
        result.add("logEntries", logEntries);
        result.addProperty("changedCount", entries.size());
        result.addProperty("logEntriesTruncated", false);
        ResponseBudget budget = ResponseBudget.startingWith(result);
        if (!fill(budget, logEntries, entries, DistrictJson::plotLog)) {
            result.addProperty("logEntriesTruncated", true);
        }
        JsonArray detailLog = detailSkeleton.getAsJsonArray("log");
        if (!fill(budget, detailLog, view.log(), DistrictJson::plotLog)) {
            detailSkeleton.addProperty("logTruncated", true);
        }
        return result;
    }

    private static DistrictQueryService.PlotDetailView withoutLog(DistrictQueryService.PlotDetailView view) {
        return new DistrictQueryService.PlotDetailView(view.district(), view.plot(), view.relation(), view.editable(),
                view.friends(), view.cells(), view.freeze(), List.of(), view.logTruncated());
    }

    static JsonObject friend(FriendRecord friend, boolean isResident) {
        JsonObject json = new JsonObject();
        json.addProperty("playerName", friend.name());
        json.addProperty("addedAt", friend.addedAt());
        json.addProperty("addedBy", friend.addedByName());
        json.addProperty("syncStatus", friend.syncStatus().wire());
        json.addProperty("isResident", isResident);
        json.addProperty("suspended", friend.suspended());
        json.addProperty("suspendedAt", friend.suspendedAt());
        return json;
    }

    /** 地块开关组: 只含 member 组, 组对象不带 scope; 三列都是非 null 的布尔。 */
    private static JsonArray plotGroups(PermissionCells cells, boolean admin) {
        JsonArray groups = new JsonArray();
        for (PermissionCatalog.Group group : PermissionCatalog.GROUPS) {
            if (group.scope() != PermissionScope.MEMBER) {
                continue;
            }
            JsonArray items = new JsonArray();
            for (PermissionItemDef item : group.items()) {
                items.add(plotPermissionItem(item, cells, admin));
            }
            JsonObject json = new JsonObject();
            json.addProperty("groupId", group.groupId());
            json.addProperty("label", group.label());
            json.add("items", items);
            groups.add(json);
        }
        return groups;
    }

    /** 地块开关一项; risk 是目录里的 plotRisk (所有人都能看到), flanIds / flanInverted 只给管理员。 */
    static JsonObject plotPermissionItem(PermissionItemDef item, PermissionCells cells, boolean admin) {
        JsonObject current = new JsonObject();
        JsonObject defaults = new JsonObject();
        for (PlotAudience audience : PlotAudience.values()) {
            current.addProperty(audience.wire(), cells.plot(item, audience));
            defaults.addProperty(audience.wire(), item.plotDefault(audience));
        }
        JsonObject json = new JsonObject();
        json.addProperty("permissionId", item.permissionId());
        json.addProperty("label", item.label());
        json.addProperty("detail", item.detail());
        json.add("current", current);
        json.add("defaults", defaults);
        if (admin) {
            json.add("flanIds", strings(item.flanIds()));
            json.addProperty("flanInverted", item.flanInverted());
        } else {
            json.add("flanIds", JsonNull.INSTANCE);
            json.add("flanInverted", JsonNull.INSTANCE);
        }
        json.addProperty("risk", item.plotRisk());
        return json;
    }

    // ================================================================
    // admin.district.archive
    // ================================================================

    static JsonObject archive(DistrictQueryService.ArchiveView view) {
        JsonArray districts = new JsonArray();
        JsonObject result = new JsonObject();
        result.add("districts", districts);
        result.addProperty("truncated", view.truncated());
        ResponseBudget budget = ResponseBudget.startingWith(result);
        for (DistrictQueryService.ArchivedDistrictView archived : view.districts()) {
            DistrictRecord district = archived.district();
            Academy academy = archived.academy();
            JsonArray log = new JsonArray();
            JsonObject json = new JsonObject();
            json.addProperty("districtId", district.districtId());
            json.addProperty("displayName", district.displayName());
            json.addProperty("academyName", academy.shortName());
            json.addProperty("academyFullName", academy.fullName());
            json.addProperty("unboundAt", district.unboundAt());
            json.addProperty("unboundBy", district.unboundByName());
            json.addProperty("memberCount", district.unboundMemberCount());
            json.add("log", log);
            json.addProperty("logTruncated", archived.logTruncated());
            if (!budget.tryAdd(districts, json)) {
                result.addProperty("truncated", true);
                break;
            }
            if (!fill(budget, log, archived.log(), DistrictJson::districtLog)) {
                json.addProperty("logTruncated", true);
                result.addProperty("truncated", true);
                break;
            }
        }
        return result;
    }
}
