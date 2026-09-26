package com.miningdim.title;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.store.MiningDb;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.testutil.TempStoreDb;
import com.miningdim.title.store.SqliteTitleRepository;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * G 面板"我的称号"页签的 title.* WebUI 契约 GameTest (Title_System_DesignSpec 第七章、13.8, batch {@code title_webui}),
 * 仿照 {@code QuestWebUiGameTests}: 一律经派发器里真实注册的 handler 驱动。
 *
 * <p>每个用例把称号门面换成 {@link TempStoreDb} 临时统一库上的实现 (三条自带的测试称号: 单色金档带说明、渐变传说档、
 * 单色铜档; 时钟与专属称号规则可拨动), 收尾时先让 mock 玩家下线再恢复原门面。强断言 (删被测核心逻辑必挂):
 * <ol>
 *   <li>title.list: 全部定义 (含未拥有的) 按展示顺序, 拥有 / 佩戴状态, 徽记按游戏内样子拍平 (单色档保留翻译键整段上色,
 *       渐变档逐字上色、首尾字恰为首尾色标、粗体), 获取说明, 七档色板, 以及本人的赞助与专属称号状态;</li>
 *   <li>title.equip: 佩戴、卸下; 未拥有 / 别人的专属称号 / 未知称号 / 入参非法逐条回稳定码且不改佩戴;</li>
 *   <li>title.customPreview: 没有资格回 CUSTOM_TITLE_NOT_SPONSOR; 合格时回渲染好的徽记与可复制给管理员的整条命令,
 *       不合格时逐条回规则名与参数; 预览不写库;</li>
 *   <li>title.customSet: 跟随 selfServiceEnabled —— 关闭 (默认口径) 时明确拒绝且不落库; 打开时提交生效, 冷却、锁定、
 *       校验不合格、没有资格逐条回稳定码;</li>
 *   <li>只有 title.list 能进 system.batch。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TitleWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "title_webui";

    private static final long T0 = 1_790_000_000_000L;
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long WEEK = Duration.ofDays(7).toMillis();

    private static final ResourceLocation LEGEND = new ResourceLocation(MiningConstants.MODID, "test/webui_legend");
    private static final ResourceLocation GOLD = new ResourceLocation(MiningConstants.MODID, "test/webui_gold");
    private static final ResourceLocation BRONZE = new ResourceLocation(MiningConstants.MODID, "test/webui_bronze");
    private static final String GOLD_KEY = "title.miningdim.test.webui_gold";
    private static final String GOLD_DESC_KEY = "title.miningdim.test.webui_gold.desc";

    private static final String STAR_SEA = "【星海】";

    private TitleWebUiGameTests() {
    }

    // ---- 1. title.list ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void listReturnsEveryDefinitionOwnershipPaletteAndCustomState(GameTestHelper helper) {
        TitleEnv env = new TitleEnv(helper, false);
        try {
            ServerPlayer player = env.player("title-web-list");
            UUID uuid = player.getUUID();
            env.titles.grant(player, GOLD, TitleSource.ADMIN, "test");
            env.titles.equip(player, GOLD);

            JsonObject result = handle(helper, "title.list", player, new JsonObject());
            helper.assertTrue(result.keySet().equals(Set.of("equipped", "palette", "titles", "custom")),
                    "title.list 只含 equipped/palette/titles/custom, 实为 " + result.keySet());
            helper.assertTrue(GOLD.toString().equals(result.get("equipped").getAsString()), "当前佩戴金档测试称号");

            JsonArray titles = result.getAsJsonArray("titles");
            List<String> order = new ArrayList<>();
            titles.forEach(element -> order.add(element.getAsJsonObject().get("titleId").getAsString()));
            helper.assertTrue(order.equals(List.of(LEGEND.toString(), GOLD.toString(), BRONZE.toString())),
                    "全部定义 (含未拥有的) 按 sort 降序, 实为 " + order);
            JsonObject gold = titles.get(1).getAsJsonObject();
            helper.assertTrue(gold.keySet().equals(Set.of("titleId", "rarity", "sort", "owned", "equipped", "badge",
                            "description")),
                    "称号行的键集不对, 实为 " + gold.keySet());
            helper.assertTrue("gold".equals(gold.get("rarity").getAsString()) && gold.get("owned").getAsBoolean()
                            && gold.get("equipped").getAsBoolean() && gold.get("sort").getAsInt() == 300,
                    "金档: 已拥有、正佩戴, 实为 " + gold);
            JsonArray goldBadge = gold.getAsJsonArray("badge");
            helper.assertTrue(goldBadge.size() == 3 && "[".equals(segment(goldBadge, 0).get("t").getAsString())
                            && GOLD_KEY.equals(segment(goldBadge, 1).get("k").getAsString())
                            && "]".equals(segment(goldBadge, 2).get("t").getAsString())
                            && "#FFCC33".equals(segment(goldBadge, 1).get("color").getAsString())
                            && !segment(goldBadge, 1).get("bold").getAsBoolean(),
                    "单色档徽记: [ + 翻译键 + ] 整段金色, 不加粗, 实为 " + goldBadge);
            helper.assertTrue(GOLD_DESC_KEY.equals(segment(gold.getAsJsonArray("description"), 0).get("k")
                    .getAsString()), "获取说明按翻译键下发");

            JsonObject legend = titles.get(0).getAsJsonObject();
            JsonArray legendBadge = legend.getAsJsonArray("badge");
            helper.assertTrue(!legend.get("owned").getAsBoolean() && !legend.get("equipped").getAsBoolean()
                            && legend.get("description").isJsonNull(),
                    "未拥有的称号照样列出; 没有说明时 description 显式为 null, 实为 " + legend);
            helper.assertTrue(legendBadge.size() == "[传说]".codePointCount(0, "[传说]".length())
                            && "#FF3D3D".equals(segment(legendBadge, 0).get("color").getAsString())
                            && "#FFD23F".equals(segment(legendBadge, legendBadge.size() - 1).get("color").getAsString())
                            && segment(legendBadge, 0).get("bold").getAsBoolean(),
                    "渐变档徽记逐字上色 (方括号一起参与), 首尾字恰为首尾色标, 粗体, 实为 " + legendBadge);

            JsonArray palette = result.getAsJsonArray("palette");
            helper.assertTrue(palette.size() == TierPalette.values().length, "七档色板");
            JsonObject bronze = palette.get(0).getAsJsonObject();
            JsonObject legendTier = palette.get(palette.size() - 1).getAsJsonObject();
            helper.assertTrue("bronze".equals(bronze.get("rarity").getAsString())
                            && bronze.getAsJsonArray("stops").size() == 1
                            && "#C8834A".equals(bronze.getAsJsonArray("stops").get(0).getAsString())
                            && !bronze.get("bold").getAsBoolean()
                            && "legend".equals(legendTier.get("rarity").getAsString())
                            && legendTier.getAsJsonArray("stops").size() == 3 && legendTier.get("bold").getAsBoolean(),
                    "色板取自 TierPalette, 实为 " + palette);

            JsonObject custom = result.getAsJsonObject("custom");
            helper.assertTrue(custom.keySet().equals(Set.of("titleId", "selfServiceEnabled", "sponsor", "record",
                            "owned", "equipped", "nextEditAt")),
                    "custom 块的键集不对, 实为 " + custom.keySet());
            helper.assertTrue(CustomTitle.idOf(uuid).toString().equals(custom.get("titleId").getAsString())
                            && custom.get("sponsor").isJsonNull() && custom.get("record").isJsonNull()
                            && !custom.get("owned").getAsBoolean() && !custom.get("selfServiceEnabled").getAsBoolean(),
                    "非赞助玩家: 没有资格、没有记录, 自助提交按配置关闭, 实为 " + custom);

            env.titles.grantSponsor(uuid, 30, "op");
            env.titles.adminSetCustomTitle(uuid, draft(STAR_SEA, true, "#6FF2FF", "#D59CFF"), "op");
            JsonObject sponsored = handle(helper, "title.list", player, new JsonObject()).getAsJsonObject("custom");
            JsonObject sponsor = sponsored.getAsJsonObject("sponsor");
            JsonObject record = sponsored.getAsJsonObject("record");
            helper.assertTrue(!sponsor.get("permanent").getAsBoolean() && sponsor.get("active").getAsBoolean()
                            && sponsor.get("expiresAt").getAsLong() == T0 + 30 * DAY,
                    "赞助资格: 30 天、有效、到期时间, 实为 " + sponsor);
            helper.assertTrue(STAR_SEA.equals(record.get("text").getAsString()) && record.get("bold").getAsBoolean()
                            && !record.get("locked").getAsBoolean()
                            && "#6FF2FF".equals(record.getAsJsonArray("colors").get(0).getAsString())
                            && record.getAsJsonArray("badge").size() == STAR_SEA.length()
                            && sponsored.get("owned").getAsBoolean(),
                    "专属称号记录: 原样文字、色标、粗体、逐字徽记 (不加方括号), 资格有效即拥有, 实为 " + sponsored);
        } finally {
            env.close();
        }
        helper.succeed();
    }

    // ---- 2. title.equip ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void equipWearsOwnedTitlesAndRejectsOthersWithStableCodes(GameTestHelper helper) {
        TitleEnv env = new TitleEnv(helper, false);
        try {
            ServerPlayer player = env.player("title-web-equip");
            ServerPlayer other = env.player("title-web-other");
            UUID uuid = player.getUUID();
            env.titles.grant(player, GOLD, TitleSource.ADMIN, "test");

            JsonObject worn = handle(helper, "title.equip", player, titlePayload(GOLD.toString()));
            helper.assertTrue(GOLD.toString().equals(worn.get("equipped").getAsString())
                    && GOLD.equals(env.titles.equipped(uuid).orElse(null)), "佩戴已拥有的称号, 回执与门面一致");

            JsonObject unequip = new JsonObject();
            unequip.add("titleId", JsonNull.INSTANCE);
            JsonObject bare = handle(helper, "title.equip", player, unequip);
            helper.assertTrue(bare.get("equipped").isJsonNull() && env.titles.equipped(uuid).isEmpty(),
                    "titleId=null 即卸下, 实为 " + bare);
            env.titles.equip(player, GOLD);

            Map<String, String> refusals = Map.of(
                    LEGEND.toString(), WebUiErrorCodes.TITLE_NOT_OWNED,
                    CustomTitle.idOf(other.getUUID()).toString(), WebUiErrorCodes.TITLE_NOT_OWNED,
                    "miningdim:test/no_such_title", WebUiErrorCodes.TITLE_UNKNOWN);
            for (Map.Entry<String, String> entry : refusals.entrySet()) {
                WebUiBusinessException rejected = rejection(helper, "title.equip", player, titlePayload(entry.getKey()));
                helper.assertTrue(entry.getValue().equals(rejected.errorCode())
                                && entry.getKey().equals(rejected.params().get("titleId")),
                        entry.getKey() + " 应回 " + entry.getValue() + ", 实为 " + rejected.errorCode());
            }
            JsonObject number = new JsonObject();
            number.addProperty("titleId", 7);
            for (JsonObject bad : List.of(new JsonObject(), titlePayload("Not An Id"), number)) {
                WebUiBusinessException rejected = rejection(helper, "title.equip", player, bad);
                helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(rejected.errorCode())
                                && "titleId".equals(rejected.params().get("field")),
                        "非法入参 " + bad + " 应回 INVALID_REQUEST, 实为 " + rejected.errorCode());
            }
            helper.assertTrue(GOLD.equals(env.titles.equipped(uuid).orElse(null)), "被拒的请求不改佩戴");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    // ---- 3. title.customPreview ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void customPreviewValidatesAuthoritativelyAndOffersTheAdminCommand(GameTestHelper helper) {
        TitleEnv env = new TitleEnv(helper, false);
        try {
            String name = "title-web-preview";
            ServerPlayer player = env.player(name);
            UUID uuid = player.getUUID();
            JsonObject starSea = draftPayload(STAR_SEA, true, "#6FF2FF", "#D59CFF");

            WebUiBusinessException notSponsor = rejection(helper, "title.customPreview", player, starSea);
            helper.assertTrue(WebUiErrorCodes.CUSTOM_TITLE_NOT_SPONSOR.equals(notSponsor.errorCode()),
                    "没有赞助资格时预览回 CUSTOM_TITLE_NOT_SPONSOR, 实为 " + notSponsor.errorCode());

            env.titles.grantSponsor(uuid, null, "op");
            JsonObject valid = handle(helper, "title.customPreview", player, starSea);
            helper.assertTrue(valid.keySet().equals(Set.of("selfServiceEnabled", "status", "violations", "badge", "spec",
                            "adminCommand", "nextEditAt")),
                    "预览回执的键集不对, 实为 " + valid.keySet());
            String spec = "#6FF2FF,#D59CFF true " + STAR_SEA;
            JsonArray badge = valid.getAsJsonArray("badge");
            helper.assertTrue("VALID".equals(valid.get("status").getAsString())
                            && valid.getAsJsonArray("violations").isEmpty() && spec.equals(valid.get("spec").getAsString())
                            && ("/mtitle custom admin set " + name + " " + spec).equals(
                            valid.get("adminCommand").getAsString())
                            && !valid.get("selfServiceEnabled").getAsBoolean(),
                    "合格的预览回规范化参数与发给管理员的整条命令, 实为 " + valid);
            helper.assertTrue(badge.size() == STAR_SEA.length()
                            && "#6FF2FF".equals(segment(badge, 0).get("color").getAsString())
                            && "#D59CFF".equals(segment(badge, badge.size() - 1).get("color").getAsString())
                            && segment(badge, 0).get("bold").getAsBoolean() && "【".equals(segment(badge, 0).get("t")
                            .getAsString()),
                    "专属称号徽记原样逐字渐变, 不加方括号, 实为 " + badge);

            JsonObject invalid = handle(helper, "title.customPreview", player, draftPayload("", false, "#101010"));
            List<String> rules = new ArrayList<>();
            invalid.getAsJsonArray("violations").forEach(element -> rules.add(element.getAsJsonObject().get("rule")
                    .getAsString()));
            helper.assertTrue("INVALID".equals(invalid.get("status").getAsString())
                            && rules.containsAll(List.of("EMPTY", "COLOR_TOO_DARK")) && invalid.get("badge").isJsonNull()
                            && invalid.get("adminCommand").isJsonNull(),
                    "不合格的预览逐条回规则名, 不给徽记与命令, 实为 " + invalid);
            JsonObject dark = firstWithRule(invalid.getAsJsonArray("violations"), "COLOR_TOO_DARK");
            helper.assertTrue("#101010".equals(dark.getAsJsonArray("args").get(0).getAsString()),
                    "不合格项带参数 (太暗的颜色原文), 实为 " + dark);

            JsonObject malformed = draftPayload(STAR_SEA, true, "#FFFFFF");
            malformed.addProperty("colors", "#FFFFFF");
            WebUiBusinessException wrongShape = rejection(helper, "title.customPreview", player, malformed);
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(wrongShape.errorCode())
                            && "colors".equals(wrongShape.params().get("field")),
                    "colors 不是数组时回 INVALID_REQUEST, 实为 " + wrongShape.errorCode());
            helper.assertTrue(env.titles.customTitleInfo(uuid).custom() == null, "预览不写库");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    // ---- 4. title.customSet ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void customSetFollowsTheSelfServiceSwitch(GameTestHelper helper) {
        TitleEnv env = new TitleEnv(helper, false);
        try {
            ServerPlayer player = env.player("title-web-set");
            UUID uuid = player.getUUID();
            JsonObject starSea = draftPayload(STAR_SEA, false, "#FFD23F");

            WebUiBusinessException notSponsor = rejection(helper, "title.customSet", player, starSea);
            helper.assertTrue(WebUiErrorCodes.CUSTOM_TITLE_NOT_SPONSOR.equals(notSponsor.errorCode()),
                    "没有资格时提交回 CUSTOM_TITLE_NOT_SPONSOR, 实为 " + notSponsor.errorCode());
            env.titles.grantSponsor(uuid, null, "op");
            WebUiBusinessException staffOnly = rejection(helper, "title.customSet", player, starSea);
            helper.assertTrue(WebUiErrorCodes.CUSTOM_TITLE_SELF_SERVICE_DISABLED.equals(staffOnly.errorCode())
                            && env.titles.customTitleInfo(uuid).custom() == null,
                    "自助提交关闭 (默认) 时明确拒绝且不落库, 实为 " + staffOnly.errorCode());

            env.selfService(true);
            JsonObject applied = handle(helper, "title.customSet", player, starSea);
            helper.assertTrue(applied.keySet().equals(Set.of("status", "titleId", "badge", "nextEditAt")),
                    "提交回执的键集不对, 实为 " + applied.keySet());
            helper.assertTrue("APPLIED".equals(applied.get("status").getAsString())
                            && CustomTitle.idOf(uuid).toString().equals(applied.get("titleId").getAsString())
                            && applied.get("nextEditAt").getAsLong() == T0 + WEEK
                            && env.titles.customTitleInfo(uuid).custom() != null,
                    "自助提交打开时提交立即生效, 冷却一周, 实为 " + applied);

            WebUiBusinessException cooling = rejection(helper, "title.customSet", player, starSea);
            helper.assertTrue(WebUiErrorCodes.CUSTOM_TITLE_ON_COOLDOWN.equals(cooling.errorCode())
                            && Long.toString(T0 + WEEK).equals(cooling.params().get("nextEditAt")),
                    "冷却中回 CUSTOM_TITLE_ON_COOLDOWN 并带下次可修改时间, 实为 " + cooling.params());

            env.titles.clearCustomTitleCooldown(uuid, "op");
            WebUiBusinessException invalid = rejection(helper, "title.customSet", player,
                    draftPayload(" 星海 ", false, "#101010"));
            helper.assertTrue(WebUiErrorCodes.CUSTOM_TITLE_INVALID.equals(invalid.errorCode())
                            && "2".equals(invalid.params().get("count"))
                            && "SPACE_EDGE,COLOR_TOO_DARK".equals(invalid.params().get("rules")),
                    "校验不合格回 CUSTOM_TITLE_INVALID 并带条数与规则名, 实为 " + invalid.params());

            env.titles.setCustomTitleLocked(uuid, true, "op");
            WebUiBusinessException locked = rejection(helper, "title.customSet", player, starSea);
            helper.assertTrue(WebUiErrorCodes.CUSTOM_TITLE_LOCKED.equals(locked.errorCode()),
                    "锁定时回 CUSTOM_TITLE_LOCKED, 实为 " + locked.errorCode());
            helper.assertTrue("#FFD23F".equals(env.titles.customTitleInfo(uuid).custom().style().colorsText()),
                    "被拒的提交不改动已保存的专属称号");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    // ---- 5. 批量白名单 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void onlyTitleListIsBatchable(GameTestHelper helper) {
        TitleEnv env = new TitleEnv(helper, true);
        try {
            ServerPlayer player = env.player("title-web-batch");
            env.titles.grantSponsor(player.getUUID(), null, "op");
            JsonArray calls = new JsonArray();
            calls.add(call("title.list", new JsonObject()));
            calls.add(call("title.equip", titlePayload(GOLD.toString())));
            calls.add(call("title.customPreview", draftPayload(STAR_SEA, false, "#FFD23F")));
            calls.add(call("title.customSet", draftPayload(STAR_SEA, false, "#FFD23F")));
            JsonObject batch = new JsonObject();
            batch.add("calls", calls);
            JsonArray results = handle(helper, "system.batch", player, batch).getAsJsonArray("results");
            helper.assertTrue(results.get(0).getAsJsonObject().get("ok").getAsBoolean(),
                    "title.list 是只读的, 可以进批, 实为 " + results.get(0));
            for (int index = 1; index < results.size(); index++) {
                JsonObject entry = results.get(index).getAsJsonObject();
                helper.assertTrue(!entry.get("ok").getAsBoolean() && WebUiErrorCodes.ACTION_NOT_BATCHABLE.equals(
                                entry.getAsJsonObject("error").get("errorCode").getAsString()),
                        entry.get("action").getAsString() + " 不许进批, 实为 " + entry);
            }
            helper.assertTrue(env.titles.customTitleInfo(player.getUUID()).custom() == null,
                    "被拒的批内提交没有落库");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    // ---- 工具 ----

    /**
     * 临时环境: 临时统一库上的称号门面 (三条测试称号, 时钟停在 T0, 专属称号规则取设计文档默认值, 自助提交开关可拨)
     * 注入 {@link TitleServices}。{@link #close()} 先让 mock 玩家下线, 再恢复原门面、关库删目录。
     */
    private static final class TitleEnv {

        final TitleService titles;
        private final GameTestHelper helper;
        private final MinecraftServer server;
        private final Path dir;
        private final Connection connection;
        private final ITitleService previous;
        private final AtomicReference<CustomTitleRules> rules;
        private final List<ServerPlayer> players = new ArrayList<>();

        TitleEnv(GameTestHelper helper, boolean selfService) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.previous = TitleServices.isRegistered() ? TitleServices.titleService() : null;
            this.rules = new AtomicReference<>(rules(selfService));
            this.dir = TempStoreDb.createTempDir();
            this.connection = TempStoreDb.openUnified(dir.resolve("titles-webui.db"));
            AtomicLong clock = new AtomicLong(T0);
            this.titles = new TitleService(new SqliteTitleRepository(connection), definitions(), server, clock::get,
                    rules::get);
            TitleServices.registerTitleService(titles);
        }

        ServerPlayer player(String name) {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                    new GameProfile(UUID.randomUUID(), name));
            players.add(player);
            return player;
        }

        void selfService(boolean enabled) {
            rules.set(rules(enabled));
        }

        void close() {
            try {
                for (ServerPlayer player : players) {
                    server.getPlayerList().remove(player);
                }
            } finally {
                if (previous == null) {
                    TitleServices.reset();
                } else {
                    TitleServices.registerTitleService(previous);
                }
                MiningDb.close(connection);
                TempStoreDb.deleteQuietly(dir);
            }
        }

        private static CustomTitleRules rules(boolean selfService) {
            return new CustomTitleRules(10, 8, CustomTitleRules.symbols("[]【】「」★☆·"),
                    List.of("管理", "服主", "官方", "客服", "admin", "owner"), 0.18D, WEEK, selfService);
        }

        private static TitleDefinitions definitions() {
            TitleDefinitions definitions = new TitleDefinitions();
            definitions.install(Map.of(
                    LEGEND, new TitleDefinition(LEGEND, Component.literal("传说"), TierPalette.LEGEND, List.of(), null,
                            null, TierPalette.LEGEND.defaultSort()),
                    GOLD, new TitleDefinition(GOLD, Component.translatable(GOLD_KEY), TierPalette.GOLD, List.of(), null,
                            Component.translatable(GOLD_DESC_KEY), TierPalette.GOLD.defaultSort()),
                    BRONZE, new TitleDefinition(BRONZE, Component.literal("铜"), TierPalette.BRONZE, List.of(), null,
                            null, TierPalette.BRONZE.defaultSort())));
            return definitions;
        }
    }

    private static CustomTitleDraft draft(String text, boolean bold, String... colors) {
        return new CustomTitleDraft(text, List.of(colors), bold);
    }

    private static JsonObject draftPayload(String text, boolean bold, String... colors) {
        JsonObject payload = new JsonObject();
        payload.addProperty("text", text);
        payload.addProperty("bold", bold);
        JsonArray array = new JsonArray();
        for (String color : colors) {
            array.add(color);
        }
        payload.add("colors", array);
        return payload;
    }

    private static JsonObject titlePayload(String titleId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("titleId", titleId);
        return payload;
    }

    private static JsonObject call(String action, JsonObject payload) {
        JsonObject call = new JsonObject();
        call.addProperty("action", action);
        call.add("payload", payload);
        return call;
    }

    private static JsonObject segment(JsonArray segments, int index) {
        return segments.get(index).getAsJsonObject();
    }

    private static JsonObject firstWithRule(JsonArray violations, String rule) {
        for (JsonElement element : violations) {
            if (rule.equals(element.getAsJsonObject().get("rule").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("没有规则 " + rule + " 的不合格项: " + violations);
    }

    private static JsonObject handle(GameTestHelper helper, String action, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(handler(helper, action).handle(sender, payload)).getAsJsonObject();
    }

    private static WebUiBusinessException rejection(GameTestHelper helper, String action, ServerPlayer sender,
                                                    JsonObject payload) {
        try {
            handler(helper, action).handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + action + " " + payload);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static WebUiAction handler(GameTestHelper helper, String action) {
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器 (TitleSystem.register 漏了 registerAll?)");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }
}
