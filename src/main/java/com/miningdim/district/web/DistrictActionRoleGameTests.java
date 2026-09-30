package com.miningdim.district.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.DistrictActionNames;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

import static com.miningdim.district.web.DistrictWebTestSupport.Cast;
import static com.miningdim.district.web.DistrictWebTestSupport.area;
import static com.miningdim.district.web.DistrictWebTestSupport.array;
import static com.miningdim.district.web.DistrictWebTestSupport.call;
import static com.miningdim.district.web.DistrictWebTestSupport.find;
import static com.miningdim.district.web.DistrictWebTestSupport.nullKey;
import static com.miningdim.district.web.DistrictWebTestSupport.payload;
import static com.miningdim.district.web.DistrictWebTestSupport.permissionItem;
import static com.miningdim.district.web.DistrictWebTestSupport.reject;
import static com.miningdim.district.web.DistrictWebTestSupport.str;

/**
 * 平板动作按身份走一遍 (设计文档第七、十四章): 管理员、本区区务长、别区区务长、有地块的住户、没有地块的住户、外人,
 * 每种身份都经派发器里真实登记的 handler 调用, 核对放行与拒绝的路径和回执形状 (含按身份裁剪的字段)。
 *
 * 固定的小世界: 阿拜多斯 (区务长 W_Abydos, 住户 Res_Owner 有一块地、Res_Plain 没有地, 另有一块空置地) 与千年
 * (区务长 W_Mill); Op_Admin 是 OP, Out_Sider 谁都不是。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictActionRoleGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_action_roles";
    private static final String ABYDOS = "abydos";

    private record World(ServerPlayer admin, ServerPlayer wardenHere, ServerPlayer wardenThere, ServerPlayer owner,
                         ServerPlayer plain, ServerPlayer outsider, String ownedPlot, String vacantPlot) {
    }

    private static World world(DistrictTestEnv env, Cast cast) {
        ServerPlayer admin = cast.op("Op_Admin");
        ServerPlayer wardenHere = cast.player("W_Abydos");
        ServerPlayer wardenThere = cast.player("W_Mill");
        ServerPlayer owner = cast.player("Res_Owner");
        ServerPlayer plain = cast.player("Res_Plain");
        ServerPlayer outsider = cast.player("Out_Sider");
        env.abydos();
        env.millennium();
        env.resident(ABYDOS, "W_Abydos");
        env.warden(ABYDOS, "W_Abydos");
        env.resident("millennium", "W_Mill");
        env.warden("millennium", "W_Mill");
        env.resident(ABYDOS, "Res_Owner");
        env.resident(ABYDOS, "Res_Plain");
        String owned = env.plot(ABYDOS, 10, 10, 25, 25);
        String vacant = env.plot(ABYDOS, 40, 10, 55, 25);
        env.own(owned, "Res_Owner");
        return new World(admin, wardenHere, wardenThere, owner, plain, outsider, owned, vacant);
    }

    private static JsonObject district(String districtId) {
        return payload("districtId", districtId);
    }

    private static JsonObject plot(String plotId) {
        return payload("districtId", ABYDOS, "plotId", plotId);
    }

    /** 管理员门的拒绝与 WebUiPermissions.requireOp 同形: params 只有 action, 文案"需要 OP 权限"。 */
    private static void assertOpGate(GameTestHelper helper, ServerPlayer sender, String action, JsonObject payload) {
        WebUiBusinessException denied = reject(helper, action, sender, payload, WebUiErrorCodes.PERMISSION_DENIED);
        helper.assertTrue(denied.params().equals(Map.of("action", action)) && "需要 OP 权限".equals(denied.getMessage()),
                action + " 的管理员门应只带 {action}, 实得 " + denied.params() + " " + denied.getMessage());
    }

    private static void assertDenied(GameTestHelper helper, ServerPlayer sender, String action, JsonObject payload,
                                     String requires) {
        WebUiBusinessException denied = reject(helper, action, sender, payload, WebUiErrorCodes.PERMISSION_DENIED);
        helper.assertTrue(action.equals(denied.params().get("action")) && requires.equals(denied.params().get("requires")),
                action + " 应拒绝 {action, requires=" + requires + "}, 实得 " + denied.params());
    }

    // ================================================================
    // 管理员
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminReachesEveryActionAndSeesAdminOnlyFields(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            World w = world(env, cast);
            env.recording().failNext("setMember", "区块未加载");
            env.resident(ABYDOS, "Fail_Fa");

            JsonObject state = call(helper, DistrictActionNames.STATE, w.admin(), payload());
            helper.assertTrue("admin".equals(str(state.getAsJsonObject("viewer"), "role"))
                    && "Op_Admin".equals(str(state.getAsJsonObject("viewer"), "playerName")), "OP 的全局身份是 admin");
            helper.assertTrue(nullKey(state, "residency"), "管理员不在名单上: residency 为 null (键在)");
            JsonObject abydos = find(array(state, "districts"), "districtId", ABYDOS);
            helper.assertTrue(abydos != null && abydos.getAsJsonObject("syncIssues").get("failed").getAsInt() == 1,
                    "管理员看得到 syncIssues, 实为 " + abydos);

            JsonObject detail = call(helper, DistrictActionNames.DETAIL, w.admin(), district(ABYDOS));
            JsonObject failed = find(array(detail, "residents"), "playerName", "Fail_Fa");
            helper.assertTrue(failed != null && "failed".equals(str(failed, "syncStatus"))
                    && "区块未加载".equals(str(failed, "syncError")), "管理员看得到失败原文, 实为 " + failed);
            helper.assertTrue(detail.getAsJsonObject("abilities").get("editClaim").getAsBoolean()
                    && array(detail, "log").size() > 0 && !detail.get("residentsTruncated").getAsBoolean(),
                    "管理员的 abilities 与记录");

            JsonObject permissions = call(helper, DistrictActionNames.PERMISSIONS, w.admin(), district(ABYDOS));
            JsonObject place = permissionItem(permissions, "place");
            JsonObject pvp = permissionItem(permissions, "pvp");
            helper.assertTrue(permissions.getAsJsonObject("editable").get("region").getAsBoolean()
                            && place.getAsJsonArray("flanIds").size() == 1 && !nullKey(place, "outsiderRisk")
                            && !nullKey(pvp, "districtRisk") && !pvp.get("flanInverted").getAsBoolean(),
                    "管理员看得到 flanIds 与两种风险提示, 实为 " + place + " / " + pvp);

            JsonObject toggled = call(helper, DistrictActionNames.SET_PERMISSION, w.admin(), payload("districtId",
                    ABYDOS, "permissionId", "pvp", "audience", "district", "enabled", true));
            JsonObject change = toggled.getAsJsonObject("logEntry").getAsJsonObject("permission");
            helper.assertTrue("district".equals(str(change, "audience")) && change.get("to").getAsBoolean()
                            && "admin".equals(str(toggled.getAsJsonObject("logEntry"), "actorRole")),
                    "管理员改区域规则, 实为 " + toggled);

            JsonObject created = call(helper, DistrictActionNames.PLOT_CREATE, w.admin(),
                    payload("districtId", ABYDOS, "area", area(70, 10, 85, 25)));
            JsonObject newPlot = created.getAsJsonObject("plot");
            helper.assertTrue("vacant".equals(str(newPlot, "status")) && newPlot.get("friendCount").getAsInt() == 0
                            && newPlot.has("syncError") && newPlot.get("price").getAsLong() == 1280L
                            && "createPlot".equals(str(created.getAsJsonObject("logEntry"), "action")),
                    "管理员划地块的回执, 实为 " + created);

            JsonObject resized = call(helper, DistrictActionNames.PLOT_RESIZE, w.admin(),
                    payload("districtId", ABYDOS, "plotId", w.ownedPlot(), "area", area(10, 10, 26, 25)));
            helper.assertTrue(resized.get("ownerNotified").getAsBoolean() && str(resized.getAsJsonObject("logEntry"),
                    "reason").startsWith("管理员代改"), "管理员调有户主的地块 = 代改并通知户主, 实为 " + resized);

            JsonObject plotDetail = call(helper, DistrictActionNames.PLOT_DETAIL, w.admin(), plot(w.ownedPlot()));
            JsonObject firstItem = plotDetail.getAsJsonArray("groups").get(0).getAsJsonObject()
                    .getAsJsonArray("items").get(0).getAsJsonObject();
            helper.assertTrue("admin".equals(str(plotDetail, "viewerRelation")) && plotDetail.get("editable")
                            .getAsBoolean() && firstItem.get("flanIds").isJsonArray()
                            && plotDetail.getAsJsonObject("plot").has("syncError"),
                    "管理员看别人的地块: 关系 admin、可代改、看得到 flanIds, 实为 " + plotDetail.get("viewerRelation"));

            JsonObject onBehalf = call(helper, DistrictActionNames.PLOT_SET_PERMISSION, w.admin(),
                    payload("districtId", ABYDOS, "plotId", w.ownedPlot(), "permissionId", "bed",
                            "audience", "resident", "enabled", true));
            JsonObject onBehalfLog = onBehalf.getAsJsonObject("logEntry");
            helper.assertTrue(onBehalfLog.get("onBehalfOfOwner").getAsBoolean()
                    && "admin".equals(str(onBehalfLog, "actorRole")), "管理员改户主的地块记为代改, 实为 " + onBehalfLog);

            JsonObject pricing = call(helper, DistrictActionNames.SET_PLOT_PRICING, w.admin(),
                    payload("districtId", ABYDOS, "unitPrice", 7, "minSide", 8, "maxSide", 48));
            helper.assertTrue(pricing.get("unitPrice").getAsInt() == 7
                            && "单价 5 → 7 信用点/格".equals(str(pricing.getAsJsonObject("logEntry"), "reason"))
                            && pricing.getAsJsonObject("rules").get("edgeGap").getAsInt() == 2,
                    "改单价的回执与缘由, 实为 " + pricing);
            JsonObject opened = call(helper, DistrictActionNames.SET_PURCHASE_OPEN, w.admin(),
                    payload("districtId", ABYDOS, "open", true));
            JsonObject again = call(helper, DistrictActionNames.SET_PURCHASE_OPEN, w.admin(),
                    payload("districtId", ABYDOS, "open", true));
            helper.assertTrue("开放购买".equals(str(opened.getAsJsonObject("logEntry"), "reason"))
                    && nullKey(again, "logEntry") && again.get("open").getAsBoolean(), "开放购买; 再开一次不写记录");

            JsonObject retried = call(helper, DistrictActionNames.RETRY_SYNC, w.admin(),
                    payload("districtId", ABYDOS, "playerName", "fail_fa"));
            helper.assertTrue("synced".equals(str(retried.getAsJsonObject("resident"), "syncStatus"))
                    && "Fail_Fa".equals(str(retried.getAsJsonObject("resident"), "playerName")),
                    "重试同步成功, 回显规范名, 实为 " + retried);

            JsonObject warden = call(helper, DistrictActionNames.SET_WARDEN, w.admin(),
                    payload("districtId", ABYDOS, "playerName", "res_plain"));
            helper.assertTrue("Res_Plain".equals(str(warden, "wardenName"))
                    && "appoint".equals(str(warden.getAsJsonObject("logEntry"), "action")), "换区务长, 实为 " + warden);

            JsonObject archive = call(helper, DistrictActionNames.ARCHIVE, w.admin(), payload());
            helper.assertTrue(array(archive, "districts").isEmpty() && !archive.get("truncated").getAsBoolean(),
                    "还没有解绑过的区, 实为 " + archive);
        }
        helper.succeed();
    }

    // ================================================================
    // 本区区务长
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wardenManagesResidentsPublicAreaAndVacantPlotsOnly(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            World w = world(env, cast);
            ServerPlayer warden = w.wardenHere();
            env.recording().failNext("setMember", "区块未加载");
            env.resident(ABYDOS, "Fail_Fa");

            JsonObject state = call(helper, DistrictActionNames.STATE, warden, payload());
            JsonObject residency = state.getAsJsonObject("residency");
            helper.assertTrue("warden".equals(str(state.getAsJsonObject("viewer"), "role"))
                            && residency.get("isWarden").getAsBoolean() && nullKey(residency, "plot")
                            && nullKey(find(array(state, "districts"), "districtId", ABYDOS), "syncIssues"),
                    "区务长: 全局身份 warden, 有居住权卡片, 看不到 syncIssues, 实为 " + state);

            JsonObject detail = call(helper, DistrictActionNames.DETAIL, warden, district(ABYDOS));
            JsonObject abilities = detail.getAsJsonObject("abilities");
            helper.assertTrue(abilities.get("manageResidents").getAsBoolean() && abilities.get("managePlots")
                            .getAsBoolean() && !abilities.get("editClaim").getAsBoolean()
                            && !abilities.get("managePlotMarket").getAsBoolean()
                            && !abilities.get("manageRegionRules").getAsBoolean(),
                    "区务长的 abilities, 实为 " + abilities);
            JsonObject failed = find(array(detail, "residents"), "playerName", "Fail_Fa");
            helper.assertTrue(failed != null && "failed".equals(str(failed, "syncStatus"))
                    && nullKey(failed, "syncError"), "区务长看得到名单, 但失败原文恒为 null, 实为 " + failed);

            env.seen("New_Comer");
            JsonObject added = call(helper, DistrictActionNames.ADD_RESIDENT, warden,
                    payload("districtId", ABYDOS, "playerName", "new_comer", "allowNeverJoined", false));
            helper.assertTrue("New_Comer".equals(str(added.getAsJsonObject("resident"), "playerName"))
                            && nullKey(added.getAsJsonObject("resident"), "syncError")
                            && "warden".equals(str(added.getAsJsonObject("logEntry"), "actorRole"))
                            && nullKey(added, "frozenPlot"),
                    "区务长加住户: 存规范名, 记录身份 warden, 实为 " + added);
            JsonObject removed = call(helper, DistrictActionNames.REMOVE_RESIDENT, warden,
                    payload("districtId", ABYDOS, "playerName", "New_Comer", "reasonKind", "inactive",
                            "reason", "长期不上线"));
            helper.assertTrue("remove".equals(str(removed.getAsJsonObject("logEntry"), "action"))
                            && nullKey(removed, "frozenPlot") && nullKey(removed, "reclaimAt")
                            && removed.get("suspendedFriendOfPlots").getAsInt() == 0,
                    "区务长移出住户, 实为 " + removed);

            JsonObject outsiderPlace = call(helper, DistrictActionNames.SET_PERMISSION, warden,
                    payload("districtId", ABYDOS, "permissionId", "place", "audience", "outsider", "enabled", true));
            JsonObject item = outsiderPlace.getAsJsonObject("item");
            helper.assertTrue(item.getAsJsonObject("current").get("outsider").getAsBoolean()
                            && !nullKey(item, "outsiderRisk") && nullKey(item, "flanIds")
                            && nullKey(item, "districtRisk"),
                    "区务长改外人列: 看得到外人风险提示, 看不到 flanIds, 实为 " + item);
            assertDenied(helper, warden, DistrictActionNames.SET_PERMISSION, payload("districtId", ABYDOS,
                    "permissionId", "pvp", "audience", "district", "enabled", true), "admin");
            assertDenied(helper, warden, DistrictActionNames.RESET_PERMISSIONS, payload("districtId", ABYDOS,
                    "scope", "all"), "admin");
            JsonObject reset = call(helper, DistrictActionNames.RESET_PERMISSIONS, warden,
                    payload("districtId", ABYDOS, "scope", "member"));
            helper.assertTrue(reset.get("changedCount").getAsInt() == 1 && array(reset, "logEntries").size() == 1
                    && "恢复默认".equals(str(array(reset, "logEntries").get(0).getAsJsonObject(), "reason")),
                    "区务长恢复住户与外人列, 实为 " + reset.get("changedCount"));

            JsonObject plots = call(helper, DistrictActionNames.PLOTS, warden, district(ABYDOS));
            JsonObject ownedRow = find(array(plots, "plots"), "plotId", w.ownedPlot());
            helper.assertTrue(ownedRow.get("friendCount").isJsonPrimitive() && "synced".equals(str(ownedRow,
                    "syncStatus")) && nullKey(ownedRow, "syncError") && nullKey(plots, "deletedPlots"),
                    "区务长看得到朋友数与生效状态, 看不到失败原文与墓碑, 实为 " + ownedRow);

            JsonObject created = call(helper, DistrictActionNames.PLOT_CREATE, warden,
                    payload("districtId", ABYDOS, "area", area(70, 10, 85, 25)));
            String newPlot = str(created.getAsJsonObject("plot"), "plotId");
            helper.assertTrue(nullKey(created.getAsJsonObject("plot"), "syncError")
                    && "warden".equals(str(created.getAsJsonObject("logEntry"), "actorRole")), "区务长划地块");
            JsonObject resized = call(helper, DistrictActionNames.PLOT_RESIZE, warden,
                    payload("districtId", ABYDOS, "plotId", newPlot, "area", area(70, 10, 86, 25)));
            helper.assertTrue(!resized.get("ownerNotified").getAsBoolean(), "空置地块调范围不通知户主");
            call(helper, DistrictActionNames.PLOT_DELETE, warden, plot(newPlot));
            reject(helper, DistrictActionNames.PLOT_RESIZE, warden, payload("districtId", ABYDOS,
                    "plotId", w.ownedPlot(), "area", area(10, 10, 26, 25)), WebUiErrorCodes.PLOT_OCCUPIED);
            reject(helper, DistrictActionNames.PLOT_DELETE, warden, plot(w.ownedPlot()), WebUiErrorCodes.PLOT_OCCUPIED);

            WebUiBusinessException notTheirs = reject(helper, DistrictActionNames.PLOT_DETAIL, warden,
                    plot(w.ownedPlot()), WebUiErrorCodes.PERMISSION_DENIED);
            helper.assertTrue("别人的地块由户主做主，区务长只能看地块列表，不能看或改朋友和权限".equals(notTheirs.getMessage()),
                    "区务长看别人的地块用专门的文案, 实为 " + notTheirs.getMessage());
            reject(helper, DistrictActionNames.PLOT_ADD_FRIEND, warden, payload("districtId", ABYDOS,
                    "plotId", w.ownedPlot(), "playerName", "Out_Sider"), WebUiErrorCodes.PERMISSION_DENIED);

            assertOpGate(helper, warden, DistrictActionNames.RETRY_SYNC, payload("districtId", ABYDOS,
                    "playerName", "Fail_Fa"));
            assertOpGate(helper, warden, DistrictActionNames.SET_WARDEN, payload("districtId", ABYDOS,
                    "playerName", null));
            assertOpGate(helper, warden, DistrictActionNames.DELETE, district(ABYDOS));
            assertOpGate(helper, warden, DistrictActionNames.SET_PLOT_PRICING, payload("districtId", ABYDOS,
                    "unitPrice", 1, "minSide", 8, "maxSide", 48));
            assertOpGate(helper, warden, DistrictActionNames.SET_PURCHASE_OPEN, payload("districtId", ABYDOS,
                    "open", true));
            assertOpGate(helper, warden, DistrictActionNames.PLOT_UNFREEZE, plot(w.vacantPlot()));
            assertOpGate(helper, warden, DistrictActionNames.PLOT_RECLAIM_NOW, plot(w.vacantPlot()));
            assertOpGate(helper, warden, DistrictActionNames.ARCHIVE, payload());
            helper.assertTrue(env.districtRecord(ABYDOS).live() && !env.districtRecord(ABYDOS).purchaseOpen(),
                    "被管理员门拦下的动作一行都没写");
        }
        helper.succeed();
    }

    // ================================================================
    // 别区的区务长
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wardenOfAnotherDistrictIsAnOutsiderHere(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            World w = world(env, cast);
            ServerPlayer other = w.wardenThere();
            JsonObject state = call(helper, DistrictActionNames.STATE, other, payload());
            helper.assertTrue("warden".equals(str(state.getAsJsonObject("viewer"), "role"))
                    && "millennium".equals(str(state.getAsJsonObject("residency"), "districtId")),
                    "别区区务长的全局身份仍是 warden, 居住权在千年");

            assertDenied(helper, other, DistrictActionNames.DETAIL, district(ABYDOS), "member");
            assertDenied(helper, other, DistrictActionNames.PLOTS, district(ABYDOS), "member");
            WebUiBusinessException plotDenied = reject(helper, DistrictActionNames.PLOT_DETAIL, other,
                    plot(w.ownedPlot()), WebUiErrorCodes.PERMISSION_DENIED);
            helper.assertTrue("只有户主本人和管理员能看这块地的朋友和权限".equals(plotDenied.getMessage()),
                    "别区区务长在这里不算区务长, 用通用文案, 实为 " + plotDenied.getMessage());

            JsonObject permissions = call(helper, DistrictActionNames.PERMISSIONS, other, district(ABYDOS));
            JsonObject place = permissionItem(permissions, "place");
            helper.assertTrue(nullKey(place.getAsJsonObject("current"), "resident")
                            && nullKey(place.getAsJsonObject("defaults"), "resident")
                            && place.getAsJsonObject("current").get("outsider").isJsonPrimitive()
                            && nullKey(place, "outsiderRisk")
                            && !permissions.getAsJsonObject("editable").get("member").getAsBoolean(),
                    "别区区务长按外人裁剪: 没有住户列与风险提示, 不可改, 实为 " + place);

            assertDenied(helper, other, DistrictActionNames.ADD_RESIDENT, payload("districtId", ABYDOS,
                    "playerName", "Out_Sider"), "manager");
            assertDenied(helper, other, DistrictActionNames.REMOVE_RESIDENT, payload("districtId", ABYDOS,
                    "playerName", "Res_Plain", "reasonKind", "other", "reason", "x"), "manager");
            assertDenied(helper, other, DistrictActionNames.SET_PERMISSION, payload("districtId", ABYDOS,
                    "permissionId", "bed", "audience", "outsider", "enabled", true), "manager");
            assertDenied(helper, other, DistrictActionNames.PLOT_CREATE, payload("districtId", ABYDOS,
                    "area", area(70, 10, 85, 25)), "manager");
            assertDenied(helper, other, DistrictActionNames.PLOT_RESIZE, payload("districtId", ABYDOS,
                    "plotId", w.vacantPlot(), "area", area(40, 10, 56, 25)), "manager");

            JsonObject own = call(helper, DistrictActionNames.DETAIL, other, district("millennium"));
            helper.assertTrue(own.get("residents").isJsonArray(), "在自己的区照常是区务长");
        }
        helper.succeed();
    }

    // ================================================================
    // 有地块的住户
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void residentWithPlotManagesOnlyTheirOwnPlot(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            World w = world(env, cast);
            ServerPlayer owner = w.owner();
            JsonObject state = call(helper, DistrictActionNames.STATE, owner, payload());
            JsonObject residency = state.getAsJsonObject("residency");
            helper.assertTrue("resident".equals(str(state.getAsJsonObject("viewer"), "role"))
                            && w.ownedPlot().equals(str(residency.getAsJsonObject("plot"), "plotId"))
                            && "synced".equals(str(residency, "syncStatus")) && !residency.get("isWarden").getAsBoolean(),
                    "住户的居住权卡片带着自己的地块, 实为 " + residency);

            JsonObject detail = call(helper, DistrictActionNames.DETAIL, owner, district(ABYDOS));
            helper.assertTrue(nullKey(detail, "residents") && nullKey(detail, "log")
                            && detail.getAsJsonObject("abilities").get("build").getAsBoolean()
                            && !detail.getAsJsonObject("abilities").get("viewPlotStatus").getAsBoolean(),
                    "住户: 名单与记录为 null (不是空列表), 能建造, 看不到地块状态");

            JsonObject permissions = call(helper, DistrictActionNames.PERMISSIONS, owner, district(ABYDOS));
            JsonObject place = permissionItem(permissions, "place");
            helper.assertTrue(place.getAsJsonObject("current").get("resident").getAsBoolean()
                            && nullKey(place, "outsiderRisk") && nullKey(place, "flanIds")
                            && !permissions.getAsJsonObject("editable").get("member").getAsBoolean(),
                    "住户看得到住户列 (只读), 实为 " + place);

            JsonObject plots = call(helper, DistrictActionNames.PLOTS, owner, district(ABYDOS));
            JsonObject market = plots.getAsJsonObject("market");
            JsonObject ownedRow = find(array(plots, "plots"), "plotId", w.ownedPlot());
            helper.assertTrue(w.ownedPlot().equals(str(plots, "myPlotId"))
                            && "ALREADY_OWNS_PLOT".equals(str(market, "viewerBlock")) && nullKey(market, "viewerBalance")
                            && nullKey(ownedRow, "friendCount") && nullKey(ownedRow, "syncStatus")
                            && "Res_Owner".equals(str(ownedRow, "ownerName")) && nullKey(plots, "deletedPlots"),
                    "住户的地块列表: 看不到朋友数与生效状态, 自己已有地块, 实为 " + ownedRow + " " + market);

            JsonObject mine = call(helper, DistrictActionNames.PLOT_DETAIL, owner, plot(w.ownedPlot()));
            helper.assertTrue("owner".equals(str(mine, "viewerRelation")) && mine.get("editable").getAsBoolean()
                            && mine.get("friendLimit").getAsInt() == 8 && nullKey(mine.getAsJsonObject("plot"), "syncError")
                            && nullKey(mine.getAsJsonArray("groups").get(0).getAsJsonObject().getAsJsonArray("items")
                            .get(0).getAsJsonObject(), "flanIds"),
                    "户主看自己的地块: 关系 owner, 看不到 flanIds 与失败原文");

            JsonObject changed = call(helper, DistrictActionNames.PLOT_SET_PERMISSION, owner,
                    payload("districtId", ABYDOS, "plotId", w.ownedPlot(), "permissionId", "bed",
                            "audience", "resident", "enabled", true));
            JsonObject changedLog = changed.getAsJsonObject("logEntry");
            helper.assertTrue("owner".equals(str(changedLog, "actorRole")) && !changedLog.get("onBehalfOfOwner")
                            .getAsBoolean() && changed.getAsJsonObject("item").getAsJsonObject("current").get("resident")
                            .getAsBoolean() && str(changedLog, "entryId").startsWith("p"),
                    "户主改自己的地块, 实为 " + changedLog);

            JsonObject friend = call(helper, DistrictActionNames.PLOT_ADD_FRIEND, owner,
                    payload("districtId", ABYDOS, "plotId", w.ownedPlot(), "playerName", "res_plain",
                            "allowNeverJoined", false));
            helper.assertTrue("Res_Plain".equals(str(friend.getAsJsonObject("friend"), "playerName"))
                            && friend.getAsJsonObject("friend").get("isResident").getAsBoolean()
                            && "synced".equals(str(friend.getAsJsonObject("friend"), "syncStatus"))
                            && nullKey(friend.getAsJsonObject("friend"), "suspendedAt"),
                    "加朋友回显规范名, 实为 " + friend);
            reject(helper, DistrictActionNames.PLOT_RESTORE_FRIEND, owner, payload("districtId", ABYDOS,
                    "plotId", w.ownedPlot(), "playerName", "Res_Plain"), WebUiErrorCodes.FRIEND_NOT_SUSPENDED);

            JsonObject reset = call(helper, DistrictActionNames.PLOT_RESET_PERMISSIONS, owner, plot(w.ownedPlot()));
            helper.assertTrue(reset.get("changedCount").getAsInt() == 1 && array(reset, "logEntries").size() == 1
                            && !reset.get("logEntriesTruncated").getAsBoolean()
                            && "owner".equals(str(reset.getAsJsonObject("detail"), "viewerRelation")),
                    "户主恢复默认: 一格一条, 回执带整块地, 实为 " + reset.get("changedCount"));

            assertDenied(helper, owner, DistrictActionNames.PLOT_DETAIL, plot(w.vacantPlot()), "owner");
            assertDenied(helper, owner, DistrictActionNames.ADD_RESIDENT, payload("districtId", ABYDOS,
                    "playerName", "Out_Sider"), "manager");
            assertDenied(helper, owner, DistrictActionNames.PLOT_CREATE, payload("districtId", ABYDOS,
                    "area", area(70, 10, 85, 25)), "manager");
            reject(helper, DistrictActionNames.PLOT_BUY, owner, payload("districtId", ABYDOS,
                    "plotId", w.vacantPlot(), "expectedPrice", 1280, "expectedBounds", area(40, 10, 55, 25)),
                    WebUiErrorCodes.ALREADY_OWNS_PLOT);
        }
        helper.succeed();
    }

    // ================================================================
    // 没有地块的住户
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void residentWithoutPlotBuysAfterAdminOpensPurchase(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            World w = world(env, cast);
            ServerPlayer plain = w.plain();
            env.fund("Res_Plain", 10_000L);

            JsonObject closed = call(helper, DistrictActionNames.PLOTS, plain, district(ABYDOS));
            helper.assertTrue(nullKey(closed, "myPlotId")
                            && "PURCHASE_CLOSED".equals(str(closed.getAsJsonObject("market"), "viewerBlock"))
                            && nullKey(closed.getAsJsonObject("market"), "viewerBalance"),
                    "购买未开放: viewerBlock PURCHASE_CLOSED, 不给余额, 实为 " + closed.get("market"));
            reject(helper, DistrictActionNames.PLOT_BUY, plain, payload("districtId", ABYDOS, "plotId", w.vacantPlot(),
                    "expectedPrice", 1280, "expectedBounds", area(40, 10, 55, 25)), WebUiErrorCodes.PURCHASE_CLOSED);

            call(helper, DistrictActionNames.SET_PURCHASE_OPEN, w.admin(), payload("districtId", ABYDOS, "open", true));
            JsonObject open = call(helper, DistrictActionNames.PLOTS, plain, district(ABYDOS));
            JsonObject market = open.getAsJsonObject("market");
            JsonObject vacantRow = find(array(open, "plots"), "plotId", w.vacantPlot());
            helper.assertTrue(nullKey(market, "viewerBlock") && market.get("viewerBalance").getAsLong() == 10_000L
                            && vacantRow.get("price").getAsLong() == 1280L && market.get("open").getAsBoolean(),
                    "开放后能买: 余额与价格都给出, 实为 " + market + " " + vacantRow.get("price"));

            // 界面把确认框里那一行的 bounds 原样送回来 (多出的 dimension 键服务端不读)。
            JsonObject seenBounds = vacantRow.getAsJsonObject("bounds");
            WebUiBusinessException changed = reject(helper, DistrictActionNames.PLOT_BUY, plain,
                    payload("districtId", ABYDOS, "plotId", w.vacantPlot(), "expectedPrice", 999,
                            "expectedBounds", seenBounds.deepCopy()),
                    WebUiErrorCodes.PRICE_CHANGED);
            helper.assertTrue("1280".equals(changed.params().get("price")), "价格变了回新价, 实为 " + changed.params());
            WebUiBusinessException moved = reject(helper, DistrictActionNames.PLOT_BUY, plain,
                    payload("districtId", ABYDOS, "plotId", w.vacantPlot(), "expectedPrice", 1280,
                            "expectedBounds", area(40, 10, 55, 24)),
                    WebUiErrorCodes.PLOT_CHANGED);
            helper.assertTrue("25".equals(moved.params().get("maxZ")) && env.plotRecord(w.vacantPlot()).vacant(),
                    "范围对不上报 PLOT_CHANGED 并带现在的范围, 实为 " + moved.params());

            JsonObject bought = call(helper, DistrictActionNames.PLOT_BUY, plain,
                    payload("districtId", ABYDOS, "plotId", w.vacantPlot(), "expectedPrice", 1280,
                            "expectedBounds", seenBounds.deepCopy()));
            JsonObject buyLog = bought.getAsJsonObject("logEntry");
            helper.assertTrue(w.vacantPlot().equals(str(bought.getAsJsonObject("plot"), "plotId"))
                            && bought.get("price").getAsLong() == 1280L && bought.get("balanceAfter").getAsLong() == 8720L
                            && env.balance("Res_Plain") == 8720L && "buyPlot".equals(str(buyLog, "action"))
                            && "resident".equals(str(buyLog, "actorRole")) && "1,280 信用点".equals(str(buyLog, "reason")),
                    "买地: 扣款、过户、记录, 实为 " + bought);

            JsonObject state = call(helper, DistrictActionNames.STATE, plain, payload());
            helper.assertTrue(w.vacantPlot().equals(str(state.getAsJsonObject("residency").getAsJsonObject("plot"),
                    "plotId")), "买完居住权卡片带上新地块");
            reject(helper, DistrictActionNames.PLOT_BUY, plain, payload("districtId", ABYDOS, "plotId", w.ownedPlot(),
                    "expectedPrice", 1280, "expectedBounds", area(10, 10, 25, 25)), WebUiErrorCodes.ALREADY_OWNS_PLOT);
        }
        helper.succeed();
    }

    // ================================================================
    // 外人
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void outsiderSeesOnlyPublicInformation(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            World w = world(env, cast);
            ServerPlayer outsider = w.outsider();
            call(helper, DistrictActionNames.PLOT_ADD_FRIEND, w.owner(), payload("districtId", ABYDOS,
                    "plotId", w.ownedPlot(), "playerName", "Out_Sider"));

            JsonObject state = call(helper, DistrictActionNames.STATE, outsider, payload());
            JsonArray friendOf = array(state, "friendOf");
            helper.assertTrue("outsider".equals(str(state.getAsJsonObject("viewer"), "role"))
                            && nullKey(state, "residency") && array(state, "districts").size() == 2
                            && nullKey(array(state, "districts").get(0).getAsJsonObject(), "syncIssues")
                            && friendOf.size() == 1 && "Res_Owner".equals(str(friendOf.get(0).getAsJsonObject(),
                            "ownerName")) && !state.get("friendOfTruncated").getAsBoolean(),
                    "外人: 公开摘要, 外加自己是哪块地的朋友, 实为 " + state);

            JsonObject permissions = call(helper, DistrictActionNames.PERMISSIONS, outsider, district(ABYDOS));
            JsonObject bed = permissionItem(permissions, "bed");
            JsonObject pvp = permissionItem(permissions, "pvp");
            helper.assertTrue(nullKey(bed.getAsJsonObject("current"), "resident")
                            && bed.getAsJsonObject("current").get("outsider").getAsBoolean()
                            && nullKey(bed.getAsJsonObject("current"), "district")
                            && !pvp.getAsJsonObject("current").get("district").getAsBoolean()
                            && nullKey(pvp.getAsJsonObject("current"), "outsider") && nullKey(pvp, "districtRisk")
                            && !permissions.getAsJsonObject("editable").get("member").getAsBoolean()
                            && !permissions.getAsJsonObject("editable").get("region").getAsBoolean()
                            && array(permissions, "fixedRules").size() == 1,
                    "外人: 外人列与全区列公开, 住户列为 null, 实为 " + bed + " / " + pvp);

            WebUiBusinessException detail = reject(helper, DistrictActionNames.DETAIL, outsider, district(ABYDOS),
                    WebUiErrorCodes.PERMISSION_DENIED);
            helper.assertTrue("你不是这个自管区的住户，只能看公开信息".equals(detail.getMessage())
                    && "member".equals(detail.params().get("requires")), "外人看详情被拒, 实为 " + detail.params());
            assertDenied(helper, outsider, DistrictActionNames.PLOTS, district(ABYDOS), "member");
            assertDenied(helper, outsider, DistrictActionNames.PLOT_DETAIL, plot(w.ownedPlot()), "owner");
            assertDenied(helper, outsider, DistrictActionNames.ADD_RESIDENT, payload("districtId", ABYDOS,
                    "playerName", "Out_Sider"), "manager");
            WebUiBusinessException buy = reject(helper, DistrictActionNames.PLOT_BUY, outsider,
                    payload("districtId", ABYDOS, "plotId", w.vacantPlot(), "expectedPrice", 1280,
                            "expectedBounds", area(40, 10, 55, 25)),
                    WebUiErrorCodes.NOT_RESIDENT);
            helper.assertTrue("只有本区住户能买本区的地块".equals(buy.getMessage()), "外人买地的文案");
            assertOpGate(helper, outsider, DistrictActionNames.ARCHIVE, payload());
            reject(helper, DistrictActionNames.DETAIL, outsider, district("nowhere"), WebUiErrorCodes.DISTRICT_NOT_FOUND);
            helper.assertTrue(List.of(w.ownedPlot(), w.vacantPlot()).stream().allMatch(id -> env.plotRecord(id) != null),
                    "外人的一切尝试都没有改动地块");
        }
        helper.succeed();
    }
}
