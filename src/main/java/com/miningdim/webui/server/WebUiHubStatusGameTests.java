package com.miningdim.webui.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.quest.QuestService;
import com.miningdim.quest.QuestServices;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Objects;
import java.util.Set;

/**
 * hub.panels 与 system.serverStatus 两个 action 的 GameTest (WebUI 接线 W1)。
 *
 * 与 {@link WebUiServerGameTests} 同 batch 同注册路径 (走真实 {@link WebUiServerSubsystem#register}, 不在测试里
 * 另造 handler), 故删掉被测的注册行或 handler 逻辑本类必挂。
 *
 * 强断言 (删被测核心逻辑必挂):
 *  1. hub.panels 恒发 11 条且顺序固定, 域与前端路由表逐条对齐 (quests 已接入, champion 必须叫 codex);
 *  2. admin 面板的 enabled 随真实 OP 状态翻转, 且锁上时带 lockCode=NOT_OP、开着时整键缺席;
 *  3. 默认启用任务系统时除 admin 外 10 条恒开且一律不带 lockCode (本批不做等级门/婚姻门);
 *  4. 面板只发 panelId/enabled/lockCode 三个键, route/label/iconItemId 一律不下发 (展示层真源在前端);
 *  5. QuestServices inactive 时 quests 独立锁为 QUEST_DISABLED, 不串改其它面板的锁;
 *  6. system.serverStatus 的五个字段取真实服务器数值, 且 tps 由 mspt 派生并被钳在 20 以内。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WebUiHubStatusGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui";

    /** 面板 id 的期望全集与顺序。写死在测试里而不是引用被测常量, 否则改错了域两边一起改还是绿的。 */
    private static final String[] EXPECTED_PANEL_IDS = {
            "home", "market", "shop", "jobs", "mining",
            "quests", "codex", "marriage", "case", "settings", "admin"};

    /** 单个面板允许出现的全部键 (lockCode 仅锁上时出现)。多一个键都是往服务端搬前端的活。 */
    private static final Set<String> ALLOWED_PANEL_KEYS = Set.of("panelId", "enabled", "lockCode");

    // ============================================================
    // 1. hub.panels: 面板域与顺序
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hubPanelsSendsFixedDomainInFixedOrder(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonArray panels = panels(player);

        helper.assertTrue(panels.size() == EXPECTED_PANEL_IDS.length,
                "hub.panels 恒发 " + EXPECTED_PANEL_IDS.length + " 条, 实得 " + panels.size());
        for (int i = 0; i < EXPECTED_PANEL_IDS.length; i++) {
            String actual = panels.get(i).getAsJsonObject().get("panelId").getAsString();
            helper.assertTrue(EXPECTED_PANEL_IDS[i].equals(actual),
                    "第 " + i + " 条面板 id 应为 " + EXPECTED_PANEL_IDS[i] + ", 实得 " + actual);
        }
        JsonObject quests = findPanel(panels, "quests");
        helper.assertTrue(quests != null && quests.get("enabled").getAsBoolean(),
                "默认启用任务系统时 hub.panels 必须包含可进入的 quests 行");
        helper.assertTrue(findPanel(panels, "champion") == null,
                "精英怪图鉴的稳定 id 是 codex 而不是 champion (对齐 ROUTE_CODEX)");
        helper.succeed();
    }

    // ============================================================
    // 2/3. hub.panels: admin 门随真实 OP 翻转, 其余 10 条在默认配置下恒开
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hubPanelsLocksAdminForNonOpOnly(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        PlayerList playerList = player.getServer().getPlayerList();

        JsonObject adminLocked = findPanel(panels(player), "admin");
        helper.assertTrue(adminLocked != null && !adminLocked.get("enabled").getAsBoolean(),
                "非 OP 玩家的 admin 面板必须 enabled=false");
        helper.assertTrue(adminLocked != null && adminLocked.has("lockCode")
                        && HubLockCodes.NOT_OP.equals(adminLocked.get("lockCode").getAsString()),
                "锁上的面板必须带稳定锁码 NOT_OP (前端靠它出中文, 不是靠服务端下发文案)");

        // 除 admin 外一条都不许带锁码: 本批不做职业等级门与婚姻门, 提前造锁码等于立一份没有判据的契约。
        for (JsonElement element : panels(player)) {
            JsonObject panel = element.getAsJsonObject();
            if ("admin".equals(panel.get("panelId").getAsString())) {
                continue;
            }
            helper.assertTrue(panel.get("enabled").getAsBoolean(),
                    panel.get("panelId").getAsString() + " 面板本批恒开");
            helper.assertTrue(!panel.has("lockCode"),
                    panel.get("panelId").getAsString() + " 面板开着时不得带 lockCode (缺席键而不是空串)");
        }

        playerList.op(player.getGameProfile());
        try {
            JsonObject adminOpen = findPanel(panels(player), "admin");
            helper.assertTrue(adminOpen != null && adminOpen.get("enabled").getAsBoolean(),
                    "OP 玩家的 admin 面板必须 enabled=true (判据是真实 PlayerList.isOp, 不是硬编码 false)");
            helper.assertTrue(adminOpen != null && !adminOpen.has("lockCode"),
                    "开着的 admin 面板不得带 lockCode");
        } finally {
            // 同一进程内后续用例共用这台服务器的 ops 名单, 不清掉会污染别人的权限断言。
            playerList.deop(player.getGameProfile());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hubPanelsLocksQuestsWhileQuestServicesIsInactive(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        QuestService previous = QuestServices.service();
        QuestServices.reset();
        try {
            JsonArray result = panels(player);
            JsonObject quests = Objects.requireNonNull(findPanel(result, "quests"),
                    "hub.panels 必须保留 quests 行而不是在停用时删掉入口");
            helper.assertTrue(!quests.get("enabled").getAsBoolean(),
                    "QuestServices inactive 时 quests 必须 enabled=false, 实得 " + quests);
            helper.assertTrue(quests.has("lockCode")
                            && HubLockCodes.QUEST_DISABLED.equals(quests.get("lockCode").getAsString()),
                    "锁上的 quests 必须带 QUEST_DISABLED, 实得 " + quests);

            JsonObject home = Objects.requireNonNull(findPanel(result, "home"));
            helper.assertTrue(home.get("enabled").getAsBoolean() && !home.has("lockCode"),
                    "任务锁必须独立, 不得连带锁住 home, 实得 " + home);
            JsonObject admin = Objects.requireNonNull(findPanel(result, "admin"));
            helper.assertTrue(!admin.get("enabled").getAsBoolean()
                            && HubLockCodes.NOT_OP.equals(admin.get("lockCode").getAsString()),
                    "任务锁不得覆盖非 OP 的 admin 独立锁码, 实得 " + admin);
        } finally {
            QuestServices.register(previous);
        }
        helper.succeed();
    }

    // ============================================================
    // 3b. hub.panels: 展示层字段一律不下发 (D2)
    // ============================================================

    /**
     * route / label / iconItemId 三项的真源在前端。服务端也存一份的代价是: 改文案 / 换图标 / 调路由从"纯前端
     * 发版"变成"两端同时发版才不指错路径", 而路线 A (远端托管 + 浏览器缓存) 下这种不同步检测不出来 ——
     * mock 种子把精英怪图鉴的 route 写成 '/champion' 而真实常量是 '/codex', 就是已实测的漂移。
     *
     * 服务端只权威"这个面板我现在能不能进", 因为它依赖的 OP / 等级 / 婚姻是服务端私有数据。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hubPanelsOmitsPresentationFields(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        for (JsonElement element : panels(player)) {
            JsonObject panel = element.getAsJsonObject();
            String panelId = panel.get("panelId").getAsString();
            helper.assertTrue(!panel.has("route") && !panel.has("label") && !panel.has("iconItemId"),
                    panelId + " 面板不得下发展示层字段 (route/label/iconItemId 的真源在前端), 实得 " + panel);
            for (String key : panel.keySet()) {
                helper.assertTrue(ALLOWED_PANEL_KEYS.contains(key),
                        panelId + " 面板出现了契约外的字段 " + key + ", 实得 " + panel);
            }
        }
        helper.succeed();
    }

    // ============================================================
    // 4. system.serverStatus
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void serverStatusReportsRealCountsAndDerivedTps(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        WebUiServerDispatcher.WebUiAction status = ensureRegistered(helper, "system.serverStatus");
        JsonObject result = JsonParser.parseString(status.handle(player, new JsonObject())).getAsJsonObject();

        // 在线人数取真实 PlayerList: mock 玩家已经 placeNewPlayer 过, 故至少有它自己这一个。
        helper.assertTrue(result.get("online").getAsInt() == player.getServer().getPlayerCount(),
                "online 必须等于服务器真实在线人数");
        helper.assertTrue(result.get("online").getAsInt() >= 1,
                "已登记的 mock 玩家必须被算进在线人数, 实得 " + result.get("online").getAsInt());
        helper.assertTrue(result.get("maxPlayers").getAsInt() == player.getServer().getMaxPlayers(),
                "maxPlayers 必须等于服务器真实容量");

        double mspt = result.get("mspt").getAsDouble();
        helper.assertTrue(mspt >= 0.0D, "mspt 是毫秒耗时, 不可能为负, 实得 " + mspt);
        // 必须与真实来源比对: 下面的 expectedTps 由回执自己的 mspt 派生, 是自指断言, 只能证明两者内部一致。
        // 若 mspt 本身错源 (把 getAverageTickTime 换成任意非负表达式), tps 仍会一致派生、仍落在 (0,20],
        // 整套测试照样全绿 —— 而这两个数正是运维判断服务器掉没掉刻的唯一依据。同一 tick 内该值不变, 无抖动。
        helper.assertTrue(Math.abs(mspt - player.server.getAverageTickTime()) < 1.0E-3D,
                "mspt 必须取自 MinecraftServer.getAverageTickTime(), 实得 " + mspt
                        + " 而服务器实为 " + player.server.getAverageTickTime());

        // tps 必须是 mspt 派生值且被钳在 20: 删掉 Math.min 那一层, 空转的 GameTest 服务器 (mspt 远小于 50)
        // 会算出几百上千的 tps; 删掉 mspt<=0 的分支则会在首 tick 前除出 Infinity。
        double tps = result.get("tps").getAsDouble();
        double expectedTps = mspt <= 0.0D ? 20.0D : Math.min(20.0D, 1000.0D / mspt);
        helper.assertTrue(Math.abs(tps - expectedTps) < 1.0E-3D,
                "tps 必须由 mspt 派生 (期望 " + expectedTps + "), 实得 " + tps);
        helper.assertTrue(tps > 0.0D && tps <= 20.0D, "tps 必须落在 (0,20], 实得 " + tps);

        helper.assertTrue(result.get("uptimeSeconds").getAsLong() == player.server.getTickCount() / 20,
                "uptimeSeconds 是已运行游戏刻折算的秒 (tickCount/20), 不是挂钟时长");

        // 公告字段已按 D5 砍掉: 全库没有"运营公告"这个业务概念, 恒回空串等于立一个永远为空的死约定。
        helper.assertTrue(!result.has("announcement"),
                "serverStatus 不得下发 announcement 字段");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    private static JsonArray panels(ServerPlayer player) {
        String json = HubWebUiActions.PANELS.handle(player, new JsonObject());
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("panels");
    }

    private static JsonObject findPanel(JsonArray panels, String panelId) {
        for (JsonElement element : panels) {
            JsonObject panel = element.getAsJsonObject();
            if (panelId.equals(panel.get("panelId").getAsString())) {
                return panel;
            }
        }
        return null;
    }

    /**
     * 幂等确保某个 system.* action 已注册并返回其处理器 (范式同 {@link WebUiServerGameTests} 的 ensureEchoRegistered)。
     * 进程级注册表跨测试方法持久, 故已注册就直接 resolve, 否则走真实 register 路径 —— 删掉 register 里那一行
     * 注册, 本方法的断言即挂。
     */
    private static WebUiServerDispatcher.WebUiAction ensureRegistered(GameTestHelper helper, String action) {
        WebUiServerDispatcher.WebUiAction existing = WebUiServerDispatcher.resolve(action);
        if (existing == null) {
            // modBus/forgeBus 在本 action 的注册路径上不被使用, 传 null 不触发任何事件订阅。
            new WebUiServerSubsystem().register((IEventBus) null, (IEventBus) null);
            existing = WebUiServerDispatcher.resolve(action);
        }
        helper.assertTrue(existing != null, action + " 必须由 WebUiServerSubsystem.register 注册");
        return existing;
    }
}
