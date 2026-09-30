package com.miningdim.district.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictLimits;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.access.Actor;
import com.miningdim.district.core.DistrictActionNames;
import com.miningdim.district.core.DistrictAudience;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionItemDef;
import com.miningdim.district.core.PlotAudience;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.miningdim.district.DistrictTestEnv.player;
import static com.miningdim.district.web.DistrictWebTestSupport.Cast;
import static com.miningdim.district.web.DistrictWebTestSupport.area;
import static com.miningdim.district.web.DistrictWebTestSupport.array;
import static com.miningdim.district.web.DistrictWebTestSupport.call;
import static com.miningdim.district.web.DistrictWebTestSupport.find;
import static com.miningdim.district.web.DistrictWebTestSupport.nullKey;
import static com.miningdim.district.web.DistrictWebTestSupport.payload;
import static com.miningdim.district.web.DistrictWebTestSupport.permissionItem;
import static com.miningdim.district.web.DistrictWebTestSupport.raw;
import static com.miningdim.district.web.DistrictWebTestSupport.reject;
import static com.miningdim.district.web.DistrictWebTestSupport.str;

/**
 * 平板层的通用约定 (设计文档第十四、十五章): 26 条全部登记且都不进批量、可空字段写 JSON null、数据库失败转
 * STORE_FAILED、抛出的码都在 WebUiErrorCodes 登记过、回显的输入被截断、回执不超过下行上限且截断标记正确、
 * 入参的读取档位 (机器字段先报、开关值只收布尔、缺键不等于撤销) 与按身份裁剪的矩阵。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_webui";
    private static final String ABYDOS = "abydos";

    // ================================================================
    // 登记与批量
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void all26ActionsAreRegistered(GameTestHelper helper) {
        Set<String> declared = new HashSet<>();
        for (Field field : DistrictActionNames.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    declared.add((String) field.get(null));
                } catch (IllegalAccessException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        }
        List<String> names = DistrictWebUiActions.actionNames();
        helper.assertTrue(names.size() == 26 && declared.equals(new HashSet<>(names)),
                "26 条动作与 DistrictActionNames 逐条相同, 实为 " + names.size() + " / " + declared.size());
        for (String name : names) {
            helper.assertTrue(WebUiServerDispatcher.resolve(name) == DistrictWebUiActions.ACTIONS.get(name),
                    "派发器里登记的就是本类的 handler: " + name);
            helper.assertTrue(WebUiServerDispatcher.registeredActions().contains(name), "握手清单里有 " + name);
        }
        helper.assertTrue(DistrictWebUiActions.writeActionNames().size() == 20, "写动作 20 条");
        helper.succeed();
    }

    /** 批量是安全边界: 26 条一条都不许进批 (写动作重放即二次副作用; 读动作阶段 1 也不加)。经真实的 system.batch 核。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void noDistrictActionIsBatchable(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            ServerPlayer sender = cast.player("Batch_Bot");
            List<String> names = DistrictWebUiActions.actionNames();
            for (List<String> chunk : List.of(names.subList(0, 13), names.subList(13, names.size()))) {
                JsonArray calls = new JsonArray();
                for (String name : chunk) {
                    calls.add(payload("action", name, "payload", payload()));
                }
                String result = WebUiServerDispatcher.resolve("system.batch").handle(sender, payload("calls", calls));
                JsonArray results = JsonParser.parseString(result).getAsJsonObject().getAsJsonArray("results");
                helper.assertTrue(results.size() == chunk.size(), "批里每条都有回执");
                for (JsonElement element : results) {
                    JsonObject entry = element.getAsJsonObject();
                    helper.assertTrue(!entry.get("ok").getAsBoolean() && WebUiErrorCodes.ACTION_NOT_BATCHABLE.equals(
                                    str(entry.getAsJsonObject("error"), "errorCode")),
                            "进批必须逐条拒 ACTION_NOT_BATCHABLE: " + entry);
                }
            }
            helper.assertTrue(env.repo.liveDistricts().isEmpty(), "被拒的批没有执行任何一条");
        }
        helper.succeed();
    }

    // ================================================================
    // 序列化与错误映射
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nullableFieldsAreSerializedAsNull(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            ServerPlayer outsider = cast.player("Out_Sider");
            ServerPlayer resident = cast.player("Res_Plain");
            env.abydos();
            env.resident(ABYDOS, "Res_Plain");
            String vacant = env.plot(ABYDOS, 10, 10, 25, 25);

            JsonObject state = call(helper, DistrictActionNames.STATE, outsider, payload());
            JsonObject summary = array(state, "districts").get(0).getAsJsonObject();
            helper.assertTrue(nullKey(state, "residency") && nullKey(summary, "wardenName")
                    && nullKey(summary, "syncIssues"), "district.state 的可空字段写 null, 实为 " + state);

            JsonObject detail = call(helper, DistrictActionNames.DETAIL, resident, payload("districtId", ABYDOS));
            helper.assertTrue(nullKey(detail, "residents") && nullKey(detail, "log")
                    && nullKey(detail.getAsJsonObject("district"), "wardenName"), "district.detail 住户视角的 null 键");

            JsonObject plots = call(helper, DistrictActionNames.PLOTS, resident, payload("districtId", ABYDOS));
            JsonObject row = find(array(plots, "plots"), "plotId", vacant);
            helper.assertTrue(nullKey(plots, "myPlotId") && nullKey(plots, "deletedPlots")
                            && nullKey(plots.getAsJsonObject("market"), "viewerBalance")
                            && nullKey(row, "ownerName") && nullKey(row, "frozen") && nullKey(row, "friendCount")
                            && nullKey(row, "syncStatus") && nullKey(row, "syncError"),
                    "district.plots 住户视角的 null 键, 实为 " + row);

            JsonObject permissions = call(helper, DistrictActionNames.PERMISSIONS, outsider,
                    payload("districtId", ABYDOS));
            JsonObject place = permissionItem(permissions, "place");
            helper.assertTrue(nullKey(place.getAsJsonObject("current"), "resident")
                            && nullKey(place.getAsJsonObject("current"), "district") && nullKey(place, "flanIds")
                            && nullKey(place, "flanInverted") && nullKey(place, "outsiderRisk")
                            && nullKey(place, "districtRisk") && nullKey(permissionItem(permissions, "anvil"), "detail"),
                    "district.permissions 外人视角的 null 键, 实为 " + place);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void storeFailureMapsToStoreFailed(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            ServerPlayer admin = cast.op("Op_Admin");
            env.abydos();
            env.seen("Victim_V");
            env.exec("CREATE TEMP TRIGGER district_test_fail_member BEFORE INSERT ON district_member "
                    + "BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
            WebUiBusinessException failed = reject(helper, DistrictActionNames.ADD_RESIDENT, admin,
                    payload("districtId", ABYDOS, "playerName", "Victim_V"), WebUiErrorCodes.STORE_FAILED);
            helper.assertTrue("数据库读写失败，这次操作没有生效".equals(failed.getMessage()) && failed.params().isEmpty(),
                    "STORE_FAILED 的文案, 实为 " + failed.getMessage());
            helper.assertTrue(env.repo.membersOf(ABYDOS).isEmpty() && env.repo.districtLog(ABYDOS, 10).isEmpty(),
                    "事务整体回滚: 没有名单行, 也没有记录行");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyThrownCodeIsARegisteredConstant(GameTestHelper helper) {
        Set<String> registered = new HashSet<>();
        for (Field field : WebUiErrorCodes.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && field.getType() == String.class) {
                try {
                    registered.add((String) field.get(null));
                } catch (IllegalAccessException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        }
        for (DistrictError error : DistrictError.values()) {
            helper.assertTrue(registered.contains(error.wire()), "拒绝码 " + error.wire() + " 必须在 WebUiErrorCodes 登记");
            helper.assertTrue(error.wire().equals(error.name()), "码名与枚举名一致: " + error);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void echoedNamesAreTruncated(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            ServerPlayer admin = cast.op("Op_Admin");
            ServerPlayer owner = cast.player("Res_Owner");
            env.abydos();
            env.resident(ABYDOS, "Res_Owner");
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);
            env.own(plot, "Res_Owner");
            String longName = "x".repeat(500);
            int echoed = DistrictLimits.ECHO_MAX_CHARS + 3;

            WebUiBusinessException friend = reject(helper, DistrictActionNames.PLOT_REMOVE_FRIEND, owner,
                    payload("districtId", ABYDOS, "plotId", plot, "playerName", longName),
                    WebUiErrorCodes.FRIEND_NOT_FOUND);
            helper.assertTrue(friend.params().get("playerName").length() == echoed
                            && friend.getMessage().length() < echoed + 20,
                    "移除朋友回显的名字截断到 64 字, 实为 " + friend.params().get("playerName").length());
            WebUiBusinessException resident = reject(helper, DistrictActionNames.REMOVE_RESIDENT, admin,
                    payload("districtId", ABYDOS, "playerName", longName, "reasonKind", "other", "reason", "x"),
                    WebUiErrorCodes.NOT_RESIDENT);
            helper.assertTrue(resident.params().get("playerName").length() == echoed, "移出住户回显截断");
            WebUiBusinessException district = reject(helper, DistrictActionNames.DETAIL, admin,
                    payload("districtId", longName), WebUiErrorCodes.DISTRICT_NOT_FOUND);
            helper.assertTrue(district.params().get("districtId").length() == echoed, "districtId 回显截断");
            WebUiBusinessException kind = reject(helper, DistrictActionNames.REMOVE_RESIDENT, admin,
                    payload("districtId", ABYDOS, "playerName", "Res_Owner", "reasonKind", longName, "reason", "x"),
                    WebUiErrorCodes.INVALID_REQUEST);
            helper.assertTrue("reasonKind".equals(kind.params().get("field"))
                    && kind.params().get("value").length() == echoed, "枚举取值回显截断, 实为 " + kind.params());
        }
        helper.succeed();
    }

    /**
     * 移出原因有服务端上限: 超长的原因在任何写入之前拒掉 (否则回执超过下行上限、一条记录占满 district.detail 的预算)。
     * 由区务长经真实 handler 发, 这正是能拿它挤掉自己旧记录的身份; 恰好 200 字 (Gson 转义后六倍体积) 仍照常移出。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removeReasonLengthIsCappedBeforeAnyWrite(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            ServerPlayer warden = cast.player("Warden_Wu");
            env.abydos();
            env.resident(ABYDOS, "Warden_Wu");
            env.warden(ABYDOS, "Warden_Wu");
            env.resident(ABYDOS, "Target_Tim");
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);
            env.own(plot, "Target_Tim");
            int logBefore = env.repo.districtLog(ABYDOS, 100).size();
            int max = DistrictLimits.MAX_REMOVE_REASON_CHARS;

            for (String reason : List.of("x".repeat(32_000), "<".repeat(6_000), "<".repeat(max + 1))) {
                WebUiBusinessException tooLong = reject(helper, DistrictActionNames.REMOVE_RESIDENT, warden,
                        payload("districtId", ABYDOS, "playerName", "Target_Tim", "reasonKind", "violation",
                                "reason", reason), WebUiErrorCodes.INVALID_REQUEST);
                helper.assertTrue("reason".equals(tooLong.params().get("field")) && tooLong.params().size() == 1
                                && tooLong.getMessage().equals("移出原因最多 " + max + " 个字"),
                        reason.length() + " 字的原因: params 只有 field, 不回显原文, 实为 " + tooLong.params()
                                + " / " + tooLong.getMessage());
            }
            helper.assertTrue(env.repo.memberByUuid(DistrictTestEnv.uuidOf("Target_Tim")).isPresent()
                            && env.plotRecord(plot).ownerUuid() != null && !env.plotRecord(plot).frozen()
                            && env.repo.districtLog(ABYDOS, 100).size() == logBefore,
                    "被拒的超长原因一行都不写: 名单行还在, 地块没冻结, 本区记录不变");

            String exact = "<".repeat(max);
            String json = raw(helper, DistrictActionNames.REMOVE_RESIDENT, warden, payload("districtId", ABYDOS,
                    "playerName", "Target_Tim", "reasonKind", "violation", "reason", "  " + exact + "  "));
            JsonObject removed = JsonParser.parseString(json).getAsJsonObject();
            helper.assertTrue(exact.equals(str(removed.getAsJsonObject("logEntry"), "reason"))
                            && json.length() <= FriendlyByteBuf.MAX_STRING_LENGTH,
                    "去掉首尾空白后恰好 " + max + " 字照常移出, 原因原文进记录, 回执 " + json.length() + " 字");
            helper.assertTrue(env.plotRecord(plot).frozen(), "照常移出后地块冻结");
        }
        helper.succeed();
    }

    // ================================================================
    // 回执体积
    // ================================================================

    /**
     * 60 名住户、200 多条本区记录、40 块地、30 个墓碑、一块记录过百的地、全部改成非默认再恢复: district.detail、
     * district.plots、plot.detail、district.resetPermissions、plot.resetPermissions 都不超过下行上限, 截断标记与内容
     * 对得上。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 400)
    public static void responsesStayUnderCapForALargeDistrict(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            ServerPlayer admin = cast.op("Op_Admin");
            ServerPlayer owner = cast.player("Res_00");
            env.district(ABYDOS, 0, 0, 399, 399);
            for (int i = 0; i < 60; i++) {
                env.resident(ABYDOS, String.format("Res_%02d", i));
            }
            List<String> plots = new ArrayList<>();
            for (int i = 0; i < 70; i++) {
                int x = 5 + (i % 10) * 14;
                int z = 5 + (i / 10) * 14;
                plots.add(env.plot(ABYDOS, x, z, x + 9, z + 9));
            }
            for (String deleted : plots.subList(40, 70)) {
                env.ctx.layout().delete(env.admin, ABYDOS, deleted);
            }
            for (int i = 0; i < 50; i++) {
                env.ctx.permissions().setPermission(env.admin, ABYDOS, "bed", "outsider", i % 2 == 0 ? false : true);
            }
            String heavy = plots.get(0);
            env.own(heavy, "Res_00");
            Actor ownerActor = player("Res_00");
            for (int i = 0; i < 110; i++) {
                env.ctx.plotOwners().setPermission(ownerActor, ABYDOS, heavy, "bed", "resident", i % 2 == 0);
            }

            String detailJson = raw(helper, DistrictActionNames.DETAIL, admin, payload("districtId", ABYDOS));
            JsonObject detail = JsonParser.parseString(detailJson).getAsJsonObject();
            helper.assertTrue(detailJson.length() <= FriendlyByteBuf.MAX_STRING_LENGTH,
                    "district.detail 超长: " + detailJson.length());
            helper.assertTrue(detail.get("logTruncated").getAsBoolean() && array(detail, "log").size() <= 100
                            && (detail.get("residentsTruncated").getAsBoolean() || array(detail, "residents").size() == 60),
                    "district.detail 记录超过上限置截断, 名单没截断就是全的, 实为 residents "
                            + array(detail, "residents").size() + " log " + array(detail, "log").size());

            String plotsJson = raw(helper, DistrictActionNames.PLOTS, admin, payload("districtId", ABYDOS));
            JsonObject plotsView = JsonParser.parseString(plotsJson).getAsJsonObject();
            helper.assertTrue(plotsJson.length() <= FriendlyByteBuf.MAX_STRING_LENGTH,
                    "district.plots 超长: " + plotsJson.length());
            helper.assertTrue(plotsView.get("deletedPlotsTruncated").getAsBoolean()
                            && array(plotsView, "deletedPlots").size() <= DistrictLimits.TOMBSTONE_LIMIT
                            && (plotsView.get("plotsTruncated").getAsBoolean() || array(plotsView, "plots").size() == 40),
                    "district.plots: 30 个墓碑超过 20 个的上限置截断, 实为 " + array(plotsView, "deletedPlots").size());

            String plotJson = raw(helper, DistrictActionNames.PLOT_DETAIL, admin,
                    payload("districtId", ABYDOS, "plotId", heavy));
            JsonObject plotView = JsonParser.parseString(plotJson).getAsJsonObject();
            helper.assertTrue(plotJson.length() <= FriendlyByteBuf.MAX_STRING_LENGTH,
                    "plot.detail 超长: " + plotJson.length());
            helper.assertTrue(plotView.get("logTruncated").getAsBoolean() && array(plotView, "log").size() <= 100,
                    "plot.detail 记录过百置截断, 实为 " + array(plotView, "log").size());

            // 地块三列全部改成非默认, 再由户主恢复默认: 87 格一格一条。
            int flipped = 0;
            for (PermissionItemDef item : PermissionCatalog.memberItems()) {
                for (PlotAudience column : PlotAudience.values()) {
                    boolean current = env.repo.plotCells(heavy).plot(item, column);
                    boolean target = !item.plotDefault(column);
                    if (current != target) {
                        env.ctx.plotOwners().setPermission(ownerActor, ABYDOS, heavy, item.permissionId(),
                                column.wire(), target);
                    }
                    flipped++;
                }
            }
            String resetJson = raw(helper, DistrictActionNames.PLOT_RESET_PERMISSIONS, owner,
                    payload("districtId", ABYDOS, "plotId", heavy));
            JsonObject reset = JsonParser.parseString(resetJson).getAsJsonObject();
            helper.assertTrue(resetJson.length() <= FriendlyByteBuf.MAX_STRING_LENGTH,
                    "plot.resetPermissions 超长: " + resetJson.length());
            helper.assertTrue(reset.get("changedCount").getAsInt() == flipped && (reset.get("logEntriesTruncated")
                            .getAsBoolean() || array(reset, "logEntries").size() == flipped),
                    "plot.resetPermissions: changedCount 是实际改动的格数 " + flipped + ", 实为 "
                            + reset.get("changedCount"));

            // 公共区域全部改成非默认, 再由管理员整张恢复。
            int districtCells = 0;
            for (PermissionItemDef item : PermissionCatalog.items()) {
                for (DistrictAudience column : DistrictAudience.values()) {
                    if (!column.appliesTo(item.scope())) {
                        continue;
                    }
                    boolean current = env.repo.districtCells(ABYDOS).district(item, column);
                    boolean target = !item.districtDefault(column);
                    if (current != target) {
                        env.ctx.permissions().setPermission(env.admin, ABYDOS, item.permissionId(), column.wire(),
                                target);
                    }
                    districtCells++;
                }
            }
            String districtResetJson = raw(helper, DistrictActionNames.RESET_PERMISSIONS, admin,
                    payload("districtId", ABYDOS, "scope", "all"));
            JsonObject districtReset = JsonParser.parseString(districtResetJson).getAsJsonObject();
            helper.assertTrue(districtResetJson.length() <= FriendlyByteBuf.MAX_STRING_LENGTH,
                    "district.resetPermissions 超长: " + districtResetJson.length());
            helper.assertTrue(districtReset.get("changedCount").getAsInt() == districtCells && districtCells == 65,
                    "district.resetPermissions: 65 格全部恢复, 实为 " + districtReset.get("changedCount"));
        }
        helper.succeed();
    }

    /** 21 个已解绑的区, 前 5 个各有 50 条记录: admin.district.archive 不超过下行上限, 置截断标记。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 400)
    public static void archiveStaysUnderCapAndFlagsTruncation(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            ServerPlayer admin = cast.op("Op_Admin");
            for (int cycle = 0; cycle < 21; cycle++) {
                env.advance(60_000L);
                // 解绑不删 Flan 领地: 第二次起是同一个学院把旧地重新绑回来, 走 bind 收编旧的父领地 (20.6)。
                String districtId = (cycle == 0 ? env.district(ABYDOS, 0, 0, 199, 199) : env.rebind(ABYDOS, 5, 5))
                        .districtId();
                if (cycle < 5) {
                    for (int i = 0; i < 50; i++) {
                        env.ctx.permissions().setPermission(env.admin, districtId, "bed", "outsider", i % 2 == 0
                                ? false : true);
                    }
                }
                env.ctx.admin().unbind(env.admin, districtId);
            }
            String json = raw(helper, DistrictActionNames.ARCHIVE, admin, payload());
            JsonObject archive = JsonParser.parseString(json).getAsJsonObject();
            helper.assertTrue(json.length() <= FriendlyByteBuf.MAX_STRING_LENGTH,
                    "admin.district.archive 超长: " + json.length());
            JsonArray districts = array(archive, "districts");
            helper.assertTrue(archive.get("truncated").getAsBoolean() && districts.size() <= DistrictLimits.ARCHIVE_LIMIT,
                    "21 个区超过 20 个的上限, 置截断, 实为 " + districts.size());
            JsonObject newest = districts.get(0).getAsJsonObject();
            helper.assertTrue("abydos-21".equals(str(newest, "districtId")) && newest.get("memberCount").getAsInt() == 0
                    && "Op_Admin".equals(str(newest, "unboundBy")), "解绑时间新的在前, 实为 " + newest);
        }
        helper.succeed();
    }

    /** 预算本身: 装不下即停, 此后一切加入都失败 (后面的列表不许比前面的列表更全)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void truncationFlagsAreSet(GameTestHelper helper) {
        JsonObject skeleton = new JsonObject();
        JsonArray first = new JsonArray();
        JsonArray second = new JsonArray();
        skeleton.add("first", first);
        skeleton.add("second", second);
        ResponseBudget budget = ResponseBudget.startingWith(skeleton, ResponseBudget.sizeOf(skeleton) + 25);
        helper.assertTrue(budget.tryAdd(first, payload("k", "0123456789")), "第一条装得下");
        helper.assertTrue(!budget.tryAdd(first, payload("k", "0123456789")) && budget.exhausted(), "第二条装不下");
        helper.assertTrue(!budget.tryAdd(second, payload()), "预算用尽后, 再短的条目也不加");
        helper.assertTrue(first.size() == 1 && second.isEmpty(), "只有第一条");
        helper.succeed();
    }

    // ================================================================
    // 入参的读取档位
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void missingWardenKeyIsInvalidRequestNotRevoke(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            ServerPlayer admin = cast.op("Op_Admin");
            env.abydos();
            env.resident(ABYDOS, "Ward_W");
            env.warden(ABYDOS, "Ward_W");
            WebUiBusinessException missing = reject(helper, DistrictActionNames.SET_WARDEN, admin,
                    payload("districtId", ABYDOS), WebUiErrorCodes.INVALID_REQUEST);
            helper.assertTrue("playerName".equals(missing.params().get("field"))
                    && "Ward_W".equals(env.districtRecord(ABYDOS).wardenName()), "缺键不撤销, 实为 " + missing.params());
            reject(helper, DistrictActionNames.SET_WARDEN, admin, payload("districtId", ABYDOS, "playerName", 5),
                    WebUiErrorCodes.INVALID_REQUEST);
            JsonObject revoked = call(helper, DistrictActionNames.SET_WARDEN, admin,
                    payload("districtId", ABYDOS, "playerName", null));
            helper.assertTrue(nullKey(revoked, "wardenName") && "revoke".equals(str(revoked.getAsJsonObject("logEntry"),
                    "action")) && env.districtRecord(ABYDOS).wardenName() == null, "显式 null 才是撤销");
            reject(helper, DistrictActionNames.SET_WARDEN, admin, payload("districtId", ABYDOS, "playerName", null),
                    WebUiErrorCodes.WARDEN_NOT_APPOINTED);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void switchValuesMustBeJsonBooleans(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            ServerPlayer admin = cast.op("Op_Admin");
            ServerPlayer owner = cast.player("Res_Owner");
            env.abydos();
            env.resident(ABYDOS, "Res_Owner");
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);
            env.own(plot, "Res_Owner");
            List<JsonObject> bad = new ArrayList<>();
            bad.add(payload("districtId", ABYDOS, "permissionId", "bed", "audience", "outsider"));
            bad.add(payload("districtId", ABYDOS, "permissionId", "bed", "audience", "outsider", "enabled", null));
            bad.add(payload("districtId", ABYDOS, "permissionId", "bed", "audience", "outsider", "enabled", "false"));
            bad.add(payload("districtId", ABYDOS, "permissionId", "bed", "audience", "outsider", "enabled", 0));
            for (JsonObject body : bad) {
                WebUiBusinessException rejected = reject(helper, DistrictActionNames.SET_PERMISSION, admin, body,
                        WebUiErrorCodes.INVALID_REQUEST);
                helper.assertTrue("enabled".equals(rejected.params().get("field")), "开关值只收布尔: " + body);
            }
            helper.assertTrue(env.repo.districtCells(ABYDOS).district(PermissionCatalog.find("bed").orElseThrow(),
                    DistrictAudience.OUTSIDER), "被拒的请求没有把开关写成关");
            WebUiBusinessException audience = reject(helper, DistrictActionNames.SET_PERMISSION, admin,
                    payload("districtId", ABYDOS, "permissionId", "bed", "audience", "district", "enabled", true),
                    WebUiErrorCodes.INVALID_REQUEST);
            helper.assertTrue("audience".equals(audience.params().get("field"))
                    && "district".equals(audience.params().get("value")), "member 项不收全区列, 实为 " + audience.params());
            WebUiBusinessException open = reject(helper, DistrictActionNames.SET_PURCHASE_OPEN, admin,
                    payload("districtId", ABYDOS, "open", "true"), WebUiErrorCodes.INVALID_REQUEST);
            helper.assertTrue("open".equals(open.params().get("field")) && !env.districtRecord(ABYDOS).purchaseOpen(),
                    "开放购买只收布尔");
            WebUiBusinessException plotSwitch = reject(helper, DistrictActionNames.PLOT_SET_PERMISSION, owner,
                    payload("districtId", ABYDOS, "plotId", plot, "permissionId", "bed", "audience", "friend",
                            "enabled", "true"), WebUiErrorCodes.INVALID_REQUEST);
            helper.assertTrue("enabled".equals(plotSwitch.params().get("field")), "地块开关只收布尔");
            WebUiBusinessException scope = reject(helper, DistrictActionNames.RESET_PERMISSIONS, admin,
                    payload("districtId", ABYDOS, "scope", "everything"), WebUiErrorCodes.INVALID_REQUEST);
            helper.assertTrue("scope".equals(scope.params().get("field")), "scope 只收 member / all");
            WebUiBusinessException kind = reject(helper, DistrictActionNames.REMOVE_RESIDENT, admin,
                    payload("districtId", ABYDOS, "playerName", "Res_Owner", "reasonKind", "bogus", "reason", "x"),
                    WebUiErrorCodes.INVALID_REQUEST);
            helper.assertTrue("reasonKind".equals(kind.params().get("field")) && env.repo.membersOf(ABYDOS).size() == 1,
                    "原因种类不在四种之内");
            reject(helper, DistrictActionNames.REMOVE_RESIDENT, admin, payload("districtId", ABYDOS,
                    "playerName", "Res_Owner", "reasonKind", "other", "reason", "   "), WebUiErrorCodes.REASON_REQUIRED);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void machineFieldsAreCheckedBeforeBusinessRules(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            ServerPlayer admin = cast.op("Op_Admin");
            ServerPlayer outsider = cast.player("Out_Sider");
            env.abydos();
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);

            WebUiBusinessException noDistrict = reject(helper, DistrictActionNames.ADD_RESIDENT, outsider,
                    payload("playerName", "Someone"), WebUiErrorCodes.INVALID_REQUEST);
            helper.assertTrue("districtId".equals(noDistrict.params().get("field")), "缺 districtId 先报形状错");
            WebUiBusinessException gateFirst = reject(helper, DistrictActionNames.SET_PLOT_PRICING, outsider,
                    payload(), WebUiErrorCodes.PERMISSION_DENIED);
            helper.assertTrue(DistrictActionNames.SET_PLOT_PRICING.equals(gateFirst.params().get("action")),
                    "admin.* 的管理员门排在一切入参读取之前");
            WebUiBusinessException gateBeforeArea = reject(helper, DistrictActionNames.PLOT_CREATE, outsider,
                    payload("districtId", ABYDOS, "area", payload("minX", 1)), WebUiErrorCodes.PERMISSION_DENIED);
            helper.assertTrue("manager".equals(gateBeforeArea.params().get("requires")), "坐标在身份门之后才校验");
            JsonObject missingMaxZ = area(40, 10, 55, 25);
            missingMaxZ.remove("maxZ");
            WebUiBusinessException badArea = reject(helper, DistrictActionNames.PLOT_CREATE, admin,
                    payload("districtId", ABYDOS, "area", missingMaxZ), WebUiErrorCodes.INVALID_AREA);
            helper.assertTrue("maxZ".equals(badArea.params().get("field")) && "坐标必须是整数".equals(badArea.getMessage()),
                    "缺坐标报 INVALID_AREA {field}, 实为 " + badArea.params());
            JsonObject fractional = area(40, 10, 55, 25);
            fractional.addProperty("minX", 40.5);
            WebUiBusinessException fraction = reject(helper, DistrictActionNames.PLOT_CREATE, admin,
                    payload("districtId", ABYDOS, "area", fractional), WebUiErrorCodes.INVALID_AREA);
            helper.assertTrue("minX".equals(fraction.params().get("field")), "小数坐标报 INVALID_AREA");

            WebUiBusinessException price = reject(helper, DistrictActionNames.SET_PLOT_PRICING, admin,
                    payload("districtId", ABYDOS, "unitPrice", "5", "minSide", 8, "maxSide", 48),
                    WebUiErrorCodes.INVALID_PRICE);
            helper.assertTrue("unitPrice".equals(price.params().get("field")), "单价不是整数报 INVALID_PRICE");
            reject(helper, DistrictActionNames.SET_PLOT_PRICING, admin, payload("districtId", ABYDOS,
                    "unitPrice", DistrictLimits.MAX_UNIT_PRICE + 1, "minSide", 8, "maxSide", 48),
                    WebUiErrorCodes.INVALID_PRICE);
            reject(helper, DistrictActionNames.SET_PLOT_PRICING, admin, payload("districtId", ABYDOS,
                    "unitPrice", 5, "minSide", 50, "maxSide", 10), WebUiErrorCodes.INVALID_SIZE_LIMIT);
            reject(helper, DistrictActionNames.SET_PLOT_PRICING, admin, payload("districtId", "nowhere",
                    "unitPrice", "x"), WebUiErrorCodes.DISTRICT_NOT_FOUND);

            for (Object expected : List.of(1280.5, "1280", 1_152_921_504_606_846_976L)) {
                WebUiBusinessException rejected = reject(helper, DistrictActionNames.PLOT_BUY, outsider,
                        payload("districtId", ABYDOS, "plotId", plot, "expectedPrice", expected),
                        WebUiErrorCodes.INVALID_REQUEST);
                helper.assertTrue("expectedPrice".equals(rejected.params().get("field")),
                        "expectedPrice 必须是安全范围内的整数, 且先于业务检查: " + expected);
            }
            WebUiBusinessException noPlot = reject(helper, DistrictActionNames.PLOT_BUY, outsider,
                    payload("districtId", ABYDOS, "expectedPrice", 1280), WebUiErrorCodes.INVALID_REQUEST);
            helper.assertTrue("plotId".equals(noPlot.params().get("field")), "缺 plotId 先报形状错");
            JsonObject noMaxZ = area(10, 10, 25, 25);
            noMaxZ.remove("maxZ");
            JsonObject halfBlock = area(10, 10, 25, 25);
            halfBlock.addProperty("minX", 10.5);
            for (Object bounds : List.of("10,10,25,25", noMaxZ, halfBlock)) {
                WebUiBusinessException rejected = reject(helper, DistrictActionNames.PLOT_BUY, outsider,
                        payload("districtId", ABYDOS, "plotId", plot, "expectedPrice", 1280, "expectedBounds", bounds),
                        WebUiErrorCodes.INVALID_REQUEST);
                helper.assertTrue("expectedBounds".equals(rejected.params().get("field")),
                        "expectedBounds 必须是四个整数坐标的范围对象, 且先于业务检查: " + bounds);
            }
            WebUiBusinessException noBounds = reject(helper, DistrictActionNames.PLOT_BUY, outsider,
                    payload("districtId", ABYDOS, "plotId", plot, "expectedPrice", 1280),
                    WebUiErrorCodes.INVALID_REQUEST);
            helper.assertTrue("expectedBounds".equals(noBounds.params().get("field")), "缺 expectedBounds 先报形状错");
            reject(helper, DistrictActionNames.PLOT_BUY, outsider, payload("districtId", ABYDOS, "plotId", plot,
                    "expectedPrice", 1280, "expectedBounds", area(10, 10, 25, 25)), WebUiErrorCodes.NOT_RESIDENT);
        }
        helper.succeed();
    }

    // ================================================================
    // 按身份裁剪
    // ================================================================

    /** 公共区域开关表 (district.permissions) 的裁剪矩阵: 外人 / 别区区务长 / 住户 / 本区区务长 / 管理员。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void permissionsMaskingMatrix(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            ServerPlayer admin = cast.op("Op_Admin");
            ServerPlayer warden = cast.player("W_Abydos");
            ServerPlayer otherWarden = cast.player("W_Mill");
            ServerPlayer resident = cast.player("Res_Plain");
            ServerPlayer outsider = cast.player("Out_Sider");
            env.abydos();
            env.millennium();
            env.resident(ABYDOS, "W_Abydos");
            env.warden(ABYDOS, "W_Abydos");
            env.resident("millennium", "W_Mill");
            env.warden("millennium", "W_Mill");
            env.resident(ABYDOS, "Res_Plain");

            record Row(String who, ServerPlayer sender, boolean seesResident, boolean flanIds, boolean outsiderRisk,
                       boolean districtRisk, boolean editMember, boolean editRegion) {
            }
            List<Row> rows = List.of(
                    new Row("外人", outsider, false, false, false, false, false, false),
                    new Row("别区区务长", otherWarden, false, false, false, false, false, false),
                    new Row("住户", resident, true, false, false, false, false, false),
                    new Row("本区区务长", warden, true, false, true, false, true, false),
                    new Row("管理员", admin, true, true, true, true, true, true));
            for (Row row : rows) {
                JsonObject view = call(helper, DistrictActionNames.PERMISSIONS, row.sender(), payload("districtId", ABYDOS));
                JsonObject place = permissionItem(view, "place");
                JsonObject explosions = permissionItem(view, "explosions");
                JsonObject editable = view.getAsJsonObject("editable");
                boolean ok = row.seesResident() == !nullKey(place.getAsJsonObject("current"), "resident")
                        && row.seesResident() == !nullKey(place.getAsJsonObject("defaults"), "resident")
                        && !nullKey(place.getAsJsonObject("current"), "outsider")
                        && !nullKey(explosions.getAsJsonObject("current"), "district")
                        && nullKey(explosions.getAsJsonObject("current"), "resident")
                        && row.flanIds() == place.get("flanIds").isJsonArray()
                        && row.flanIds() == explosions.get("flanInverted").isJsonPrimitive()
                        && row.outsiderRisk() == !nullKey(place, "outsiderRisk")
                        && nullKey(explosions, "outsiderRisk")
                        && row.districtRisk() == !nullKey(explosions, "districtRisk")
                        && nullKey(place, "districtRisk")
                        && row.editMember() == editable.get("member").getAsBoolean()
                        && row.editRegion() == editable.get("region").getAsBoolean()
                        && array(view, "groups").size() == PermissionCatalog.GROUPS.size();
                helper.assertTrue(ok, row.who() + " 的裁剪不对, 实为 place=" + place + " explosions=" + explosions
                        + " editable=" + editable);
            }
        }
        helper.succeed();
    }

    /** 冻结中的地块: 列表里的冻结信息 (canRestore 只给管理者)、住户列为空、没有价格; 管理员详情不可编辑。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void frozenPlotShapesPerViewer(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open(); Cast cast = new Cast(helper)) {
            ServerPlayer admin = cast.op("Op_Admin");
            ServerPlayer warden = cast.player("W_Abydos");
            ServerPlayer resident = cast.player("Res_Plain");
            env.abydos();
            env.resident(ABYDOS, "W_Abydos");
            env.warden(ABYDOS, "W_Abydos");
            env.resident(ABYDOS, "Res_Plain");
            env.resident(ABYDOS, "Res_Gone");
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);
            env.own(plot, "Res_Gone");

            JsonObject removed = call(helper, DistrictActionNames.REMOVE_RESIDENT, warden, payload("districtId", ABYDOS,
                    "playerName", "Res_Gone", "reasonKind", "violation", "reason", "违反区规：乱挖"));
            long at = removed.getAsJsonObject("logEntry").get("at").getAsLong();
            helper.assertTrue(plot.equals(str(removed.getAsJsonObject("frozenPlot"), "plotId"))
                            && removed.get("reclaimAt").getAsLong() == at + DistrictLimits.FREEZE_MS,
                    "移出户主: 回执带冻结的地块与到期时刻, 实为 " + removed);

            JsonObject managerRow = find(array(call(helper, DistrictActionNames.PLOTS, warden,
                    payload("districtId", ABYDOS)), "plots"), "plotId", plot);
            JsonObject residentRow = find(array(call(helper, DistrictActionNames.PLOTS, resident,
                    payload("districtId", ABYDOS)), "plots"), "plotId", plot);
            helper.assertTrue("frozen".equals(str(managerRow, "status")) && nullKey(managerRow, "ownerName")
                            && nullKey(managerRow, "price") && array(managerRow, "openToResidents").isEmpty()
                            && !managerRow.get("residentColumnIsDefault").getAsBoolean()
                            && !managerRow.getAsJsonObject("frozen").get("canRestore").getAsBoolean()
                            && "Res_Gone".equals(str(managerRow.getAsJsonObject("frozen"), "formerOwnerName")),
                    "区务长看冻结地块: canRestore 是布尔 (原户主不在名单上为假), 实为 " + managerRow);
            helper.assertTrue(nullKey(residentRow.getAsJsonObject("frozen"), "canRestore")
                            && residentRow.getAsJsonObject("frozen").get("reclaimAt").getAsLong() == at
                            + DistrictLimits.FREEZE_MS, "住户看冻结地块: canRestore 为 null, 实为 " + residentRow);

            JsonObject detail = call(helper, DistrictActionNames.PLOT_DETAIL, admin, payload("districtId", ABYDOS,
                    "plotId", plot));
            helper.assertTrue(!detail.get("editable").getAsBoolean() && "admin".equals(str(detail, "viewerRelation"))
                            && detail.getAsJsonObject("plot").getAsJsonObject("frozen").get("canRestore").isJsonPrimitive(),
                    "管理员看冻结地块: 不可编辑, 看得到 canRestore");
            JsonObject freezeLog = array(detail, "log").get(0).getAsJsonObject();
            helper.assertTrue("freeze".equals(str(freezeLog, "action")) && !str(freezeLog, "reason").contains("乱挖")
                    && "warden".equals(str(freezeLog, "actorRole")), "地块记录不抄移出原因, 实为 " + freezeLog);
            reject(helper, DistrictActionNames.PLOT_SET_PERMISSION, admin, payload("districtId", ABYDOS, "plotId", plot,
                    "permissionId", "bed", "audience", "friend", "enabled", true), WebUiErrorCodes.PLOT_FROZEN);
        }
        helper.succeed();
    }
}
