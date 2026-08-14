package com.miningdim.job.chef;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * W3 职业一的 job.chef.state GameTest。
 *
 * K4 是本文件的主线: 面板数值必须<b>每次调用实时读 ChefConfig</b>。
 * {@link #chefStateReadsConfigLiveInsteadOfASnapshot} 在两次调用之间改 config, 回执不跟着变就挂 —— 抄一份
 * 静态副本的实现会让运营改完 toml 后面板永远停在进程启动那一刻的数值。
 *
 * 另锁矩阵形状: 效果是 18 行 x 5 档的矩阵而不是"一档一个值"的单列表, 且每行自带 unit ——
 * 各效果 magnitude 语义互不相同 (倍率 x100 / 千分比 / 1-based 等级 / 秒 / 个数), 发错量纲就是把玩家的
 * 数值观整个带偏 (120 会被显示成 "12.0%" 而不是 "x1.2")。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChefWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w3";

    private static final String STATE_ACTION = "job.chef.state";

    /** 五档品质的契约值 (写死在测试里: 面板照这张表画"这一档能带几个效果/会不会翻车")。 */
    private static final int[] QUALITY_MAX_EFFECTS = {1, 1, 2, 2, 3};
    private static final boolean[] QUALITY_NO_FAILURE = {false, false, false, true, true};
    private static final boolean[] QUALITY_COMBAT_UNLOCKED = {false, false, true, true, true};

    // ============================================================
    // 1. 形状与数值
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chefStateReportsQualityRowsAndEffectMatrix(GameTestHelper helper) {
        ChefConfig.ensureLoadedForTest();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject state = handle(helper, player);

        helper.assertTrue(state.get("level").getAsInt() == 1, "新号厨师 1 级");
        helper.assertTrue(state.get("qualityCapTier").getAsInt() == ChefQuality.LOW.tier()
                        && state.get("qualityCapTier").getAsInt() == 0,
                "L1 厨师只能做低级菜 (档位上限 tier 0), 实得 " + state.get("qualityCapTier").getAsInt());

        JsonArray qualities = state.getAsJsonArray("qualities");
        helper.assertTrue(qualities.size() == ChefQuality.values().length && qualities.size() == 5,
                "品质恒 5 档, 实得 " + qualities.size());
        for (int i = 0; i < ChefQuality.values().length; i++) {
            ChefQuality quality = ChefQuality.values()[i];
            JsonObject row = qualities.get(i).getAsJsonObject();
            helper.assertTrue(quality.id().equals(row.get("qualityId").getAsString())
                            && row.get("tier").getAsInt() == i,
                    "第 " + i + " 档必须是 " + quality.id() + " 且 tier=" + i);
            helper.assertTrue(quality.prefixKey().equals(row.get("nameKey").getAsString())
                            && row.get("nameKey").getAsString().startsWith("chef.quality.prefix."),
                    quality.id() + " 发品质前缀翻译键而不是中文");
            helper.assertTrue(row.get("maxEffects").getAsInt() == QUALITY_MAX_EFFECTS[i],
                    quality.id() + " 一道菜带 " + QUALITY_MAX_EFFECTS[i] + " 个效果");
            helper.assertTrue(row.get("noFailure").getAsBoolean() == QUALITY_NO_FAILURE[i],
                    quality.id() + " 的零翻车位错了 (超凡/闪耀才是 true)");
            helper.assertTrue(row.get("combatUnlocked").getAsBoolean() == QUALITY_COMBAT_UNLOCKED[i],
                    quality.id() + " 的战斗向解锁位错了 (第六章红线: 仅高/超凡/闪耀)");
            helper.assertTrue(row.get("rawXp").getAsInt() == ChefConfig.rawXp(quality),
                    quality.id() + " 的单菜原始经验取 config 实时值");
        }

        JsonArray effects = state.getAsJsonArray("effects");
        helper.assertTrue(effects.size() == ChefEffectType.values().length && effects.size() == 18,
                "效果恒 18 行, 实得 " + effects.size());
        for (int i = 0; i < ChefEffectType.values().length; i++) {
            ChefEffectType type = ChefEffectType.values()[i];
            JsonObject row = effects.get(i).getAsJsonObject();
            helper.assertTrue(type.id().equals(row.get("effectId").getAsString()),
                    "第 " + i + " 行必须是 " + type.id() + ", 实得 " + row.get("effectId").getAsString());
            helper.assertTrue(("chef.effect." + type.id()).equals(row.get("labelKey").getAsString()),
                    type.id() + " 的 labelKey 必须与 tooltip 同一批 lang 键");
            helper.assertTrue(row.get("combat").getAsBoolean() == type.isCombat()
                            && row.get("negative").getAsBoolean() == type.isNegative()
                            && row.get("windowed").getAsBoolean() == type.isWindowed(),
                    type.id() + " 的三个分类位必须原样下发 (前端靠它分组与标注)");
            helper.assertTrue(row.getAsJsonArray("magnitudes").size() == 5
                            && row.getAsJsonArray("durationSeconds").size() == 5,
                    type.id() + " 的两个数组恒 5 项 (下标 = 品质 tier)");
        }

        // 抽查四种量纲各一行: 发错 unit 前端就会把 120 显示成 12.0%。
        assertUnit(helper, effects, ChefEffectType.AMPLIFY, "mul_x100");
        assertUnit(helper, effects, ChefEffectType.NOURISH_HEAL, "permille");
        assertUnit(helper, effects, ChefEffectType.REFRESH, "level");
        assertUnit(helper, effects, ChefEffectType.NIGHT_SIGHT, "seconds");
        assertUnit(helper, effects, ChefEffectType.PURIFY, "count");
        // 多盐/失败品是固定语义, magnitude 不参与结算, 量纲必须明说是 none 而不是伪装成 flat 的 0。
        assertUnit(helper, effects, ChefEffectType.OVERSALT, "none");

        // 增香: 逐档取 ChefConfig 的公开逐级助手 (与被测实现不是同一行代码)。
        JsonArray amplify = row(helper, effects, ChefEffectType.AMPLIFY).getAsJsonArray("magnitudes");
        for (ChefQuality quality : ChefQuality.values()) {
            helper.assertTrue(amplify.get(quality.tier()).getAsInt() == ChefConfig.amplifyMul(quality),
                    "增香 " + quality.id() + " 档的倍率必须等于 config 的 " + ChefConfig.amplifyMul(quality));
        }
        // 回甘清 debuff 个数: 只有高/超凡/闪耀非零, 闪耀 99 是"全部"的哨兵值 (0 是真值不是缺数据)。
        JsonArray purify = row(helper, effects, ChefEffectType.PURIFY).getAsJsonArray("magnitudes");
        helper.assertTrue(purify.get(0).getAsInt() == 0 && purify.get(1).getAsInt() == 0
                        && purify.get(2).getAsInt() == 3 && purify.get(3).getAsInt() == 4
                        && purify.get(4).getAsInt() == 99,
                "回甘逐档应为 [0,0,3,4,99], 实得 " + purify);

        // 时长: 进食一次性结算的效果发 0; 四个战斗向窗口效果 5 档同值 (ChefConfig 的既有形态, 不按档伪造差异)。
        JsonArray amplifyDurations = row(helper, effects, ChefEffectType.AMPLIFY).getAsJsonArray("durationSeconds");
        for (int tier = 0; tier < 5; tier++) {
            helper.assertTrue(amplifyDurations.get(tier).getAsInt() == 0,
                    "增香没有独立持续时间, 该栏恒 0");
        }
        JsonArray shieldDurations = row(helper, effects, ChefEffectType.SHIELD).getAsJsonArray("durationSeconds");
        for (int tier = 0; tier < 5; tier++) {
            helper.assertTrue(shieldDurations.get(tier).getAsInt() == ChefConfig.SHIELD_WINDOW_SECONDS.get(),
                    "披甲窗口长度与品质无关, 5 档同发 " + ChefConfig.SHIELD_WINDOW_SECONDS.get());
        }
        // 夜照两栏同值是它的语义 (magnitude 本身就是时长秒), 不是重复发送。
        JsonObject night = row(helper, effects, ChefEffectType.NIGHT_SIGHT);
        for (ChefQuality quality : ChefQuality.values()) {
            int expected = ChefConfig.nightSeconds(quality);
            helper.assertTrue(night.getAsJsonArray("magnitudes").get(quality.tier()).getAsInt() == expected
                            && night.getAsJsonArray("durationSeconds").get(quality.tier()).getAsInt() == expected,
                    "夜照 " + quality.id() + " 档两栏同为 " + expected + " 秒");
        }

        helper.assertTrue(state.get("seasoningCostCredit").getAsInt() == ChefConfig.TABLE_USE_COST_CREDIT.get(),
                "调味台花费取 config 实时值");
        helper.succeed();
    }

    /** 等级上限是算出来的: L9 起才能做闪耀菜。写死成常量或读错档位本条即挂。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chefQualityCapFollowsPlayerLevel(GameTestHelper helper) {
        ChefConfig.ensureLoadedForTest();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        setChefLevel(player, 5);
        helper.assertTrue(handle(helper, player).get("qualityCapTier").getAsInt() == ChefQuality.HIGH.tier(),
                "L5-6 厨师封顶在高级 (tier 2)");
        setChefLevel(player, 9);
        JsonObject radiant = handle(helper, player);
        helper.assertTrue(radiant.get("level").getAsInt() == 9
                        && radiant.get("qualityCapTier").getAsInt() == ChefQuality.RADIANT.tier(),
                "L9-10 厨师才解锁闪耀 (tier 4), 实得 " + radiant.get("qualityCapTier").getAsInt());
        helper.succeed();
    }

    // ============================================================
    // 2. K4: 实时读 config
    // ============================================================

    /**
     * 运营改一次数值, 下一次调用就必须跟着变。
     *
     * 两个探针分别打在两条不同的取数路径上: seasoningCostCredit 是顶层单值, 增香低级档是逐效果逐品质矩阵里的
     * 一格 —— 只缓存其中一条的实现也会被抓出来。改动在 finally 里原样放回, 不污染同批其它用例。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chefStateReadsConfigLiveInsteadOfASnapshot(GameTestHelper helper) {
        ChefConfig.ensureLoadedForTest();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        int originalCost = ChefConfig.TABLE_USE_COST_CREDIT.get();
        int originalAmplifyLow = ChefConfig.AMPLIFY_LOW.get();
        int probeCost = originalCost + 32;
        int probeAmplify = originalAmplifyLow + 77;
        try {
            JsonObject before = handle(helper, player);
            helper.assertTrue(before.get("seasoningCostCredit").getAsInt() == originalCost
                            && amplifyLow(before) == originalAmplifyLow,
                    "前置校验: 改动前回执就该是 config 当前值");

            ChefConfig.TABLE_USE_COST_CREDIT.set(probeCost);
            ChefConfig.AMPLIFY_LOW.set(probeAmplify);

            JsonObject after = handle(helper, player);
            helper.assertTrue(after.get("seasoningCostCredit").getAsInt() == probeCost,
                    "改了 config 之后 seasoningCostCredit 必须变成 " + probeCost
                            + " (抄静态副本的实现会停在 " + originalCost + "), 实得 "
                            + after.get("seasoningCostCredit").getAsInt());
            helper.assertTrue(amplifyLow(after) == probeAmplify,
                    "改了 config 之后增香低级档必须变成 " + probeAmplify + ", 实得 " + amplifyLow(after));
        } finally {
            ChefConfig.TABLE_USE_COST_CREDIT.set(originalCost);
            ChefConfig.AMPLIFY_LOW.set(originalAmplifyLow);
        }

        JsonObject restored = handle(helper, player);
        helper.assertTrue(restored.get("seasoningCostCredit").getAsInt() == originalCost
                        && amplifyLow(restored) == originalAmplifyLow,
                "改回去之后回执也必须跟着回去 (证明两次变化都来自实时读, 不是一次性初始化)");
        helper.succeed();
    }

    // ============================================================
    // 3. 翻译键与注册名
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chefStateIsRegisteredUnderTheContractName(GameTestHelper helper) {
        ensureChefActionRegistered();
        helper.assertTrue(WebUiServerDispatcher.resolve(STATE_ACTION) != null,
                STATE_ACTION + " 必须由 ChefWebUiActions.registerAll 注册进派发器");
        helper.assertTrue(WebUiServerDispatcher.resolve("job.chef.effects") == null
                        && WebUiServerDispatcher.resolve("chef.state") == null,
                "厨师页只有 job.chef.state 一条 action, 不得另注册别名");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 幂等注册: 派发器注册表是进程级静态, register 用 putIfAbsent 守卫, 重复注册直接抛。 */
    private static void ensureChefActionRegistered() {
        if (WebUiServerDispatcher.resolve(STATE_ACTION) == null) {
            ChefWebUiActions.registerAll();
        }
    }

    private static JsonObject handle(GameTestHelper helper, ServerPlayer sender) {
        ensureChefActionRegistered();
        WebUiAction handler = WebUiServerDispatcher.resolve(STATE_ACTION);
        if (handler == null) {
            helper.fail("action " + STATE_ACTION + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return JsonParser.parseString(handler.handle(sender, new JsonObject())).getAsJsonObject();
    }

    private static int amplifyLow(JsonObject state) {
        for (com.google.gson.JsonElement element : state.getAsJsonArray("effects")) {
            JsonObject row = element.getAsJsonObject();
            if (ChefEffectType.AMPLIFY.id().equals(row.get("effectId").getAsString())) {
                return row.getAsJsonArray("magnitudes").get(ChefQuality.LOW.tier()).getAsInt();
            }
        }
        throw new IllegalStateException("回执里没有增香这一行");
    }

    private static JsonObject row(GameTestHelper helper, JsonArray effects, ChefEffectType type) {
        for (int i = 0; i < effects.size(); i++) {
            JsonObject row = effects.get(i).getAsJsonObject();
            if (type.id().equals(row.get("effectId").getAsString())) {
                return row;
            }
        }
        helper.fail("回执缺效果行 " + type.id());
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static void assertUnit(GameTestHelper helper, JsonArray effects, ChefEffectType type, String unit) {
        String actual = row(helper, effects, type).get("unit").getAsString();
        helper.assertTrue(unit.equals(actual),
                type.id() + " 的量纲必须是 " + unit + ", 实得 " + actual);
    }

    private static void setChefLevel(ServerPlayer player, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.CHEF).setLevel(level);
    }
}
