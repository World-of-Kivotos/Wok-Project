package com.miningdim.district.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictFeature;
import com.miningdim.district.DistrictServices;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

import static com.miningdim.district.web.DistrictWebTestSupport.area;
import static com.miningdim.district.web.DistrictWebTestSupport.payload;

/**
 * 生产开关与导航入口 (设计文档 20.9): hub.panels 只在自管区功能生效时下发 district (排在 case 之后, 第 13 条);
 * 功能关着 (服务不绑定) 时 26 条动作一律回 DISTRICT_DISABLED (不可同 id 重试, 无 params)。
 *
 * GameTest 服务端每轮都清掉 miningdim-*.toml, enabled 恒为 false, 功能恒为 OFF; 需要其它状态的用例用
 * {@link DistrictFeature#forceForTest} 翻, 结束时复原。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictFeatureGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_feature";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hubPanelsListDistrictOnlyWhenLive(GameTestHelper helper) throws Exception {
        helper.assertTrue(DistrictFeature.state() == DistrictFeature.State.OFF,
                "前提: GameTest 服务端上 enabled 恒为 false, 功能 OFF");
        ServerPlayer player = DistrictTestEnv.onlinePlayer(helper, "Hub_Viewer");
        try {
            List<String> off = panelIds(player);
            helper.assertTrue(off.size() == 12 && !off.contains("district"), "OFF: 12 条, 没有 district, 实为 " + off);
            try (AutoCloseable ignored = DistrictFeature.forceForTest(DistrictFeature.State.LIVE, null)) {
                List<String> live = panelIds(player);
                helper.assertTrue(live.size() == 13 && "district".equals(live.get(10))
                                && "case".equals(live.get(9)) && "settings".equals(live.get(11)),
                        "LIVE: 13 条, district 排在 case 之后 (第 11 位), 实为 " + live);
                JsonObject district = panel(player, "district");
                helper.assertTrue(district != null && district.get("enabled").getAsBoolean() && !district.has("lockCode"),
                        "district 下发时可进入, 不带锁码");
            }
            try (AutoCloseable ignored = DistrictFeature.forceForTest(DistrictFeature.State.DEGRADED,
                    "Flan 没有安装")) {
                List<String> degraded = panelIds(player);
                helper.assertTrue(degraded.size() == 12 && !degraded.contains("district"),
                        "DEGRADED: 不下发 (不是下发成锁着的), 实为 " + degraded);
            }
            helper.assertTrue(DistrictFeature.state() == DistrictFeature.State.OFF, "forceForTest 结束后复原");
        } finally {
            DistrictTestEnv.removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void actionsReportDistrictDisabledWhenOff(GameTestHelper helper) {
        helper.assertTrue(!DistrictServices.isRegistered(), "前提: 功能 OFF 时服务不绑定");
        JsonObject everything = payload("districtId", "abydos", "plotId", "abydos-01", "playerName", "Some_One",
                "permissionId", "place", "audience", "resident", "enabled", true, "scope", "member",
                "area", area(10, 10, 25, 25), "expectedBounds", area(10, 10, 25, 25), "expectedPrice", 1280,
                "open", true, "unitPrice", 5, "minSide", 8, "maxSide", 48, "reasonKind", "inactive",
                "reason", "不上线");
        try (DistrictWebTestSupport.Cast cast = new DistrictWebTestSupport.Cast(helper)) {
            ServerPlayer admin = cast.op("Op_Admin");
            List<String> wrong = new ArrayList<>();
            for (String action : DistrictWebUiActions.actionNames()) {
                WebUiServerDispatcher.WebUiAction handler = WebUiServerDispatcher.resolve(action);
                try {
                    handler.handle(admin, everything);
                    wrong.add(action + " succeeded");
                } catch (WebUiBusinessException rejected) {
                    if (!WebUiErrorCodes.DISTRICT_DISABLED.equals(rejected.errorCode()) || rejected.retrySameOpeningId()
                            || !rejected.params().isEmpty()) {
                        wrong.add(action + " -> " + rejected.errorCode() + " " + rejected.params());
                    }
                } catch (RuntimeException other) {
                    wrong.add(action + " threw " + other);
                }
            }
            helper.assertTrue(wrong.isEmpty() && DistrictWebUiActions.actionNames().size() == 26,
                    "26 条动作全部回 DISTRICT_DISABLED (不可同 id 重试、无 params), 不对的: " + wrong);
        }
        helper.succeed();
    }

    /**
     * 降级 (开着但 Flan 用不了, 或运行中熔断) 时平板只读 (P21): 20 条写动作一律回 DISTRICT_DISABLED (不可同 id 重试、
     * 无 params), 文案带上降级原因, 库一行都不写 —— 否则买地扣了钱却没有保护, 移出、删地块、冻结之后旧的领地权限还留着;
     * 六条读动作照常。状态复原后写动作照常。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void degradedMakesTabletWritesReadOnly(GameTestHelper helper) throws Exception {
        JsonObject everything = payload("districtId", "abydos", "plotId", "abydos-01", "playerName", "Some_One",
                "permissionId", "place", "audience", "resident", "enabled", true, "scope", "member",
                "area", area(10, 10, 25, 25), "expectedBounds", area(10, 10, 25, 25), "expectedPrice", 1280,
                "open", true, "unitPrice", 5, "minSide", 8, "maxSide", 48, "reasonKind", "inactive",
                "reason", "不上线");
        try (DistrictTestEnv env = DistrictTestEnv.open();
             DistrictWebTestSupport.Cast cast = new DistrictWebTestSupport.Cast(helper)) {
            env.abydos();
            env.plot("abydos", 10, 10, 25, 25);
            ServerPlayer admin = cast.op("Op_Admin");
            List<String> wrong = new ArrayList<>();
            try (AutoCloseable ignored = DistrictFeature.forceForTest(DistrictFeature.State.DEGRADED,
                    "Flan 没有安装")) {
                for (String action : DistrictWebUiActions.writeActionNames()) {
                    try {
                        WebUiServerDispatcher.resolve(action).handle(admin, everything);
                        wrong.add(action + " succeeded");
                    } catch (WebUiBusinessException rejected) {
                        if (!WebUiErrorCodes.DISTRICT_DISABLED.equals(rejected.errorCode())
                                || rejected.retrySameOpeningId() || !rejected.params().isEmpty()
                                || !String.valueOf(rejected.getMessage()).contains("Flan 没有安装")) {
                            wrong.add(action + " -> " + rejected.errorCode() + " " + rejected.getMessage());
                        }
                    }
                }
                helper.assertTrue(wrong.isEmpty() && DistrictWebUiActions.writeActionNames().size() == 20,
                        "降级时 20 条写动作全部回 DISTRICT_DISABLED 并带原因, 不对的: " + wrong);
                helper.assertTrue(!env.districtRecord("abydos").purchaseOpen(), "库一行都不写");
                JsonObject state = DistrictWebTestSupport.call(helper, "district.state", admin, new JsonObject());
                JsonObject detail = DistrictWebTestSupport.call(helper, "district.detail", admin,
                        payload("districtId", "abydos"));
                helper.assertTrue(state != null && detail != null, "读动作照常");
            }
            DistrictWebTestSupport.call(helper, "admin.district.setPurchaseOpen", admin,
                    payload("districtId", "abydos", "open", true));
            helper.assertTrue(env.districtRecord("abydos").purchaseOpen(), "状态复原后写动作照常");
        }
        helper.succeed();
    }

    private static List<String> panelIds(ServerPlayer player) {
        List<String> ids = new ArrayList<>();
        for (JsonElement element : panels(player)) {
            ids.add(element.getAsJsonObject().get("panelId").getAsString());
        }
        return ids;
    }

    private static JsonObject panel(ServerPlayer player, String id) {
        for (JsonElement element : panels(player)) {
            if (id.equals(element.getAsJsonObject().get("panelId").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private static JsonArray panels(ServerPlayer player) {
        String json = WebUiServerDispatcher.resolve("hub.panels").handle(player, new JsonObject());
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("panels");
    }
}
