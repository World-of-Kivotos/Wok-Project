package com.miningdim.job.engineer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.testutil.ConfigBaseline;
import com.miningdim.core.MiningConstants;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * W4b 铸甲师组的 job.engineer.state GameTest。
 *
 * 四条主线:
 *  1. 六档表的数值逐档锁死 (3.2 矿耗 / 4.1 耗时 / 5.1 修复曲线 / 7.4 单档经验), 且修复量的量纲必须分清
 *     "绝对耐久点" 与 "最大耐久千分比" —— 发错量纲就是把 30% 显示成 30 点耐久;
 *  2. 档位解锁与护甲特效解锁都跟着玩家等级走, 且特效解锁等级是从档表算出来的 (不是写死的 5);
 *  3. 反应堆共享 CD 发的是剩余 tick, 不是 epoch;
 *  4. 全部数值实时读 EngineerConfig。
 *
 * 职业名 (文案键): 玩家可见名是"铸甲师", engineer 只是旧存档兼容 id。回执里一个中文都没有, 只有
 * {@code job.miningdim.engineer} 这类键 —— 本文件锁死这一点 (服务端发中文 = 专用服解不出 lang, 只会更糟)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class EngineerWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w4b_engineer";

    /**
     * 会临时改写全局 config 的用例单独成批。Forge GameTest 批与批之间串行、<b>批内并行</b>,
     * 与读同一份 config 的用例同批就是一个必然偶发的竞态: 读的那条会在探针值还没被 finally 放回时
     * 取到探针值 (已实测撞出过一次: 低级板经验期望 15 实得 56 = 15 + 探针偏移 41)。
     *
     * 另一个坑与批次无关但同源: Forge 的 serverconfig 是 autosave 且<b>异步落盘</b>, 而 GameTest 服务器跑完
     * 立刻退出 —— 探针写的值可能已经落进 run/world/serverconfig/*.toml, 而 finally 恢复的那次写还没刷出去
     * 进程就结束了。于是之后每次运行都从被污染的值起步, 表现为某条断言硬编码期望值的用例"无缘无故"开始挂
     * (实测: 低级板经验期望 15 恒得 56 = 15 + 探针偏移 41)。真遇到时把那个 toml 里对应键改回默认即可;
     * run/ 已在 .gitignore 内, 不会污染仓库。
     */
    private static final String BATCH_CONFIG = "webui_w4b_engineer_config";


    private static final String STATE_ACTION = "job.engineer.state";

    /** 六档解锁等级 (7.2 等级解锁表)。写死在测试里: 它是档表的对外契约, 与实现一起改就等于没测。 */
    private static final int[] TIER_UNLOCK_LEVELS = {1, 3, 5, 7, 9, 10};
    /** 六档单板矿耗 (3.2: 4 铁 / 5 金 / 3 钻 / 1 下界合金 / 1 / 2)。 */
    private static final int[] TIER_ORE_COSTS = {4, 5, 3, 1, 1, 2};
    /** 六档单次产出板数 (极品 1 锭 -> 2 板是唯一例外)。 */
    private static final int[] TIER_OUTPUT_COUNTS = {1, 1, 1, 2, 1, 1};
    /** 六档生成耗时 tick (4.1)。 */
    private static final int[] TIER_PRODUCE_TICKS = {100, 120, 160, 200, 240, 300};
    /** 六档单档原始经验 (7.4; 闪耀暂沿用超凡 200, PENDING 12.7)。 */
    private static final int[] TIER_RAW_XP = {15, 30, 60, 110, 200, 200};
    /** 六档修复量 (5.1): 低/中/高是绝对耐久点, 极品/超凡/闪耀是最大耐久的千分比 (1000 = 修满)。 */
    private static final int[] TIER_REPAIR_VALUES = {100, 250, 600, 300, 650, 1000};
    private static final String[] TIER_REPAIR_UNITS =
            {"durability", "durability", "durability", "permille", "permille", "permille"};

    /** 第一个能掷特效的档是高级板 (L5); 四个特效同时在这一级解锁。 */
    private static final int EFFECT_UNLOCK_LEVEL = 5;

    private EngineerWebUiGameTests() {
    }

    /** 批前钩子: 绑定 EngineerConfig 默认值 (dev 下其 SERVER spec 未经 Forge 加载, 不绑定则 get() 抛)。 */
    @BeforeBatch(batch = BATCH)
    public static void beforeEngineerWebUiBatch(ServerLevel level) {
        EngineerConfig.ensureLoadedForTest();
        // 跨轮基线归位: 本批次会改下列配置项, 先抹掉上一轮可能残留的探针值 (见 ConfigBaseline)。
        ConfigBaseline.resetToDefaults(
                EngineerConfig.RAW_XP_LOW,
                EngineerConfig.TOTEM_SHARED_CD_TICKS,
                EngineerConfig.REPAIR_FIXED_LOW);
    }

    // ============================================================
    // 1. 六档表
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void engineerStateReportsSixTierRows(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject state = state(helper, player);

        helper.assertTrue(state.get("level").getAsInt() == 1, "新号铸甲师 1 级");
        helper.assertTrue("job.miningdim.engineer".equals(state.get("jobNameKey").getAsString()),
                "职业名发的是翻译键 (中文'铸甲师'由前端解), 实得 " + state.get("jobNameKey"));

        JsonArray tiers = state.getAsJsonArray("tiers");
        helper.assertTrue(tiers.size() == NanoTier.values().length && tiers.size() == 6,
                "纳米板恒 6 档, 实得 " + tiers.size());

        for (int i = 0; i < NanoTier.values().length; i++) {
            NanoTier tier = NanoTier.values()[i];
            String id = tier.name().toLowerCase();
            JsonObject row = tiers.get(i).getAsJsonObject();
            helper.assertTrue(id.equals(row.get("tierId").getAsString()) && row.get("index").getAsInt() == i,
                    "第 " + i + " 档必须是 " + id + " 且 index=" + i);
            helper.assertTrue(("tier.miningdim.nano." + id).equals(row.get("labelKey").getAsString()),
                    id + " 的 labelKey 必须与生产台 GUI / 套件 tooltip 同一批键");
            helper.assertTrue(("miningdim:nano_plate_" + id).equals(row.get("plateItemId").getAsString()),
                    id + " 的套件注册名必须是 miningdim:nano_plate_" + id
                            + ", 实得 " + row.get("plateItemId"));
            helper.assertTrue(row.get("unlockLevel").getAsInt() == TIER_UNLOCK_LEVELS[i],
                    id + " 的解锁等级是 L" + TIER_UNLOCK_LEVELS[i] + ", 实得 " + row.get("unlockLevel"));
            helper.assertTrue(row.get("oreCost").getAsInt() == TIER_ORE_COSTS[i],
                    id + " 的单板矿耗是 " + TIER_ORE_COSTS[i] + ", 实得 " + row.get("oreCost"));
            helper.assertTrue(row.get("outputCount").getAsInt() == TIER_OUTPUT_COUNTS[i],
                    id + " 的单次产板数是 " + TIER_OUTPUT_COUNTS[i] + ", 实得 " + row.get("outputCount"));
            helper.assertTrue(row.get("produceTicks").getAsInt() == TIER_PRODUCE_TICKS[i],
                    id + " 的生成耗时是 " + TIER_PRODUCE_TICKS[i] + " tick, 实得 " + row.get("produceTicks"));
            helper.assertTrue(row.get("rawXp").getAsInt() == TIER_RAW_XP[i],
                    id + " 的单档原始经验是 " + TIER_RAW_XP[i] + ", 实得 " + row.get("rawXp"));
            // 量纲与数值必须成对: 300 是"千分之三百"还是"三百点耐久", 差一个字面板就骗人。
            helper.assertTrue(TIER_REPAIR_UNITS[i].equals(row.get("repairUnit").getAsString()),
                    id + " 的修复量纲是 " + TIER_REPAIR_UNITS[i] + ", 实得 " + row.get("repairUnit"));
            helper.assertTrue(row.get("repairValue").getAsInt() == TIER_REPAIR_VALUES[i],
                    id + " 的修复量是 " + TIER_REPAIR_VALUES[i] + ", 实得 " + row.get("repairValue"));
            helper.assertTrue(row.get("canRollEffect").getAsBoolean() == tier.canRollEffect(),
                    id + " 的可掷特效位必须与档表一致");
        }

        // 闪耀是唯一"必出特效 + 概率产出"的档, 那两个只对它有意义的数只在它这一行发。
        JsonObject radiant = tiers.get(NanoTier.RADIANT.index()).getAsJsonObject();
        helper.assertTrue(radiant.get("guaranteedEffect").getAsBoolean()
                        && radiant.get("successChance").getAsDouble() == 0.5D
                        && radiant.get("failRefundScrap").getAsInt() == 1,
                "闪耀档必出特效 + 50% 成功率 + 失败返还 1 碎片, 实得 " + radiant);
        JsonObject low = tiers.get(NanoTier.LOW.index()).getAsJsonObject();
        helper.assertFalse(low.get("guaranteedEffect").getAsBoolean(), "低级板不掷特效");
        helper.assertFalse(low.has("successChance"),
                "非闪耀档不许发概率产出字段 (它对定产档没有意义), 实得 " + low);
        helper.succeed();
    }

    // ============================================================
    // 2. 等级门: 档位解锁 + 护甲特效解锁
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void engineerUnlocksFollowPlayerLevel(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        JsonObject atOne = state(helper, player);
        helper.assertTrue("low".equals(atOne.get("unlockedTierId").getAsString()),
                "L1 只到低级板, 实得 " + atOne.get("unlockedTierId"));
        helper.assertTrue(unlockedTierCount(atOne) == 1, "L1 只解锁 1 档, 实得 " + unlockedTierCount(atOne));

        setEngineerLevel(player, EFFECT_UNLOCK_LEVEL - 1);
        JsonObject beforeEffects = state(helper, player);
        helper.assertTrue("medium".equals(beforeEffects.get("unlockedTierId").getAsString()),
                "L4 封顶在中级板 (高级板要 L5), 实得 " + beforeEffects.get("unlockedTierId"));
        assertAllEffectsUnlocked(helper, beforeEffects, false);
        helper.assertTrue(beforeEffects.get("effectUnlockLevel").getAsInt() == EFFECT_UNLOCK_LEVEL,
                "特效解锁等级是最低可掷档 (高级板 L" + EFFECT_UNLOCK_LEVEL + ") 算出来的, 实得 "
                        + beforeEffects.get("effectUnlockLevel"));

        setEngineerLevel(player, EFFECT_UNLOCK_LEVEL);
        JsonObject atEffects = state(helper, player);
        helper.assertTrue("high".equals(atEffects.get("unlockedTierId").getAsString()),
                "L5 解锁高级板");
        helper.assertTrue(unlockedTierCount(atEffects) == 3, "L5 解锁低/中/高三档");
        assertAllEffectsUnlocked(helper, atEffects, true);

        setEngineerLevel(player, 10);
        JsonObject atTen = state(helper, player);
        helper.assertTrue("radiant".equals(atTen.get("unlockedTierId").getAsString())
                        && unlockedTierCount(atTen) == 6,
                "L10 六档全开且顶到闪耀, 实得 " + atTen.get("unlockedTierId"));
        helper.succeed();
    }

    /** 四个特效各自的实时数值 (6.2)。发错一条, 面板上的"救一次要冷却多久"就是假的。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void engineerArmorEffectsCarryTheirLiveNumbers(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject state = state(helper, player);

        JsonArray effects = state.getAsJsonArray("armorEffects");
        helper.assertTrue(effects.size() == NanoEffect.values().length && effects.size() == 4,
                "护甲特效恒 4 个, 实得 " + effects.size());
        for (int i = 0; i < NanoEffect.values().length; i++) {
            NanoEffect effect = NanoEffect.values()[i];
            JsonObject row = effects.get(i).getAsJsonObject();
            helper.assertTrue(effect.id().equals(row.get("effectId").getAsString()),
                    "第 " + i + " 个特效必须是 " + effect.id() + ", 实得 " + row.get("effectId"));
            helper.assertTrue(("effect.miningdim.nano." + effect.id()).equals(row.get("labelKey").getAsString()),
                    effect.id() + " 发的是翻译键而不是中文");
        }

        // 图腾: 人级共享 CD 30min + 复活到 50% 最大血量 (80 血服铁律: 一律按 % 最大血量建模, 不套原版 20 血)。
        helper.assertTrue(stat(helper, effects, NanoEffect.TOTEM, "sharedCdTicks") == 36000.0D,
                "图腾共享 CD 是 36000 tick (30 分钟)");
        helper.assertTrue(stat(helper, effects, NanoEffect.TOTEM, "reviveHealthPct") == 0.5D,
                "图腾复活到最大血量的 50%");
        helper.assertTrue(unitOf(helper, effects, NanoEffect.TOTEM, "reviveHealthPct").equals("percent")
                        && unitOf(helper, effects, NanoEffect.TOTEM, "sharedCdTicks").equals("ticks"),
                "比例发 percent (0..1) / 时长发 ticks, 两者混用前端就会把 0.5 秒当成 50%");
        // 护盾: 每 60s 生成一次, 按件 5 次用尽, 单次全免疫 2s。
        helper.assertTrue(stat(helper, effects, NanoEffect.SHIELD, "maxCharges") == 5.0D
                        && stat(helper, effects, NanoEffect.SHIELD, "regenIntervalTicks") == 1200.0D
                        && stat(helper, effects, NanoEffect.SHIELD, "immunityTicks") == 40.0D,
                "护盾 5 次 / 1200 tick 一次 / 40 tick 免疫窗");
        // 重塑与机能修复: 回耐久是绝对点数, 回血是 % 最大血量。
        helper.assertTrue(stat(helper, effects, NanoEffect.RESHAPE, "durabilityPerTick") == 2.0D
                        && unitOf(helper, effects, NanoEffect.RESHAPE, "durabilityPerTick").equals("flat"),
                "重塑每周期回 2 点耐久 (绝对值, 不是比例)");
        helper.assertTrue(stat(helper, effects, NanoEffect.VITALITY, "healPctPerTick") == 0.02D,
                "机能修复每周期回 2% 最大血量");
        helper.succeed();
    }

    // ============================================================
    // 3. 反应堆共享 CD
    // ============================================================

    /**
     * CD 发剩余 tick 不发 epoch: 服务端手里只有 game tick, 换算成墙钟再让 MCEF 拿 Date.now() 去减,
     * 既吃时钟偏移又在 TPS 掉帧时失真。已过期的 CD 必须回 0 而不是负数。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void engineerReactorCooldownIsReportedAsRemainingTicks(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        long now = helper.getLevel().getGameTime();

        helper.assertTrue(state(helper, player).get("reactorCooldownRemainingTicks").getAsLong() == 0L,
                "没触发过反应堆时 CD 剩余 0");

        setReactorCdEndTick(player, now + 777L);
        helper.assertTrue(state(helper, player).get("reactorCooldownRemainingTicks").getAsLong() == 777L,
                "CD 截止在 777 tick 之后时应回 777, 实得 "
                        + state(helper, player).get("reactorCooldownRemainingTicks"));

        setReactorCdEndTick(player, now - 4200L);
        helper.assertTrue(state(helper, player).get("reactorCooldownRemainingTicks").getAsLong() == 0L,
                "早已过期的 CD 必须回 0 而不是负数 (直接做减法的实现会回 -4200)");

        helper.assertTrue(state(helper, player).get("reactorSharedCdTicks").getAsInt() == 36000,
                "CD 全长同发一份 (前端画进度条要分母)");
        helper.succeed();
    }

    // ============================================================
    // 4. 实时读 config
    // ============================================================

    /**
     * 运营改一次数值, 下一次调用就必须跟着变。三个探针分别打在档位表 / 特效数值 / 顶层单值三条取数路径上,
     * 只缓存其中一条的实现也会被抓出来。改动在 finally 里原样放回, 不污染同批其它用例。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH_CONFIG)
    public static void engineerStateReadsConfigLive(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        int originalRawXpLow = EngineerConfig.RAW_XP_LOW.get();
        int originalTotemCd = EngineerConfig.TOTEM_SHARED_CD_TICKS.get();
        int originalRepairLow = EngineerConfig.REPAIR_FIXED_LOW.get();
        int probeRawXp = originalRawXpLow + 41;
        int probeTotemCd = originalTotemCd + 1300;
        int probeRepair = originalRepairLow + 313;
        try {
            JsonObject before = state(helper, player);
            helper.assertTrue(tierValue(helper, before, NanoTier.LOW, "rawXp") == originalRawXpLow
                            && before.get("reactorSharedCdTicks").getAsInt() == originalTotemCd,
                    "前置校验: 改动前回执就该是 config 当前值");

            EngineerConfig.RAW_XP_LOW.set(probeRawXp);
            EngineerConfig.TOTEM_SHARED_CD_TICKS.set(probeTotemCd);
            EngineerConfig.REPAIR_FIXED_LOW.set(probeRepair);

            JsonObject after = state(helper, player);
            helper.assertTrue(tierValue(helper, after, NanoTier.LOW, "rawXp") == probeRawXp,
                    "低级板经验必须跟着 config 变成 " + probeRawXp + ", 实得 "
                            + tierValue(helper, after, NanoTier.LOW, "rawXp"));
            helper.assertTrue(tierValue(helper, after, NanoTier.LOW, "repairValue") == probeRepair,
                    "低级板修复量必须跟着 config 变成 " + probeRepair);
            helper.assertTrue(after.get("reactorSharedCdTicks").getAsInt() == probeTotemCd,
                    "反应堆 CD 全长必须跟着 config 变成 " + probeTotemCd);
            helper.assertTrue(stat(helper, after.getAsJsonArray("armorEffects"),
                            NanoEffect.TOTEM, "sharedCdTicks") == (double) probeTotemCd,
                    "图腾那一行的 CD 与顶层同源, 也必须跟着变");
        } finally {
            EngineerConfig.RAW_XP_LOW.set(originalRawXpLow);
            EngineerConfig.TOTEM_SHARED_CD_TICKS.set(originalTotemCd);
            EngineerConfig.REPAIR_FIXED_LOW.set(originalRepairLow);
        }

        JsonObject restored = state(helper, player);
        helper.assertTrue(tierValue(helper, restored, NanoTier.LOW, "rawXp") == originalRawXpLow
                        && restored.get("reactorSharedCdTicks").getAsInt() == originalTotemCd,
                "改回去之后回执也必须跟着回去 (证明两次变化都来自实时读, 不是一次性初始化)");
        helper.succeed();
    }

    // ============================================================
    // 5. 注册名 + J5 边界
    // ============================================================

    /**
     * 决策 J5: 纳米校准 QTE 不进 MCEF。故回执里不许出现任何游标/绿区/相位字段 —— 一旦下发,
     * 前端迟早照着画一个手感被网络延迟毁掉的判定条。校准的<b>结果面</b> (品质阈值/额外产板概率) 照发。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void engineerStateExposesNoCalibrationCursor(GameTestHelper helper) {
        ensureRegistered();
        helper.assertTrue(WebUiServerDispatcher.resolve(STATE_ACTION) != null,
                STATE_ACTION + " 必须由 EngineerWebUiActions.registerAll 注册进派发器");
        helper.assertTrue(WebUiServerDispatcher.resolve("job.armorer.state") == null
                        && WebUiServerDispatcher.resolve("engineer.state") == null,
                "铸甲师页只有 job.engineer.state 一条 action, 不得另注册别名");

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        String json = require(helper, STATE_ACTION).handle(player, new JsonObject());
        for (String forbidden : new String[]{"cursor", "greenWidth", "greenStart", "barWidth", "phase"}) {
            helper.assertFalse(json.contains(forbidden),
                    "J5: QTE 游标类字段不许进回执, 却发现了 " + forbidden);
        }

        JsonObject state = JsonParser.parseString(json).getAsJsonObject();
        helper.assertTrue(state.get("qualityBonusThreshold").getAsInt() == 4
                        && state.get("qualityBonusPlateChance").getAsDouble() == 0.5D,
                "校准的结果面 (4 次命中起 50% 概率多出 1 板) 属数值预览, 必须照发");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 注册守卫。<b>刻意不在测试侧补注册</b>: 补上了, "EngineerSystem.register 忘了调 EngineerWebUiActions.registerAll"
     * 这一类装配缺陷就永远测不出来 —— 把生产侧那一行删掉, 本文件全绿, 而真服上前端调 job.engineer.state action 只会拿到
     * 派发器的 "unknown Web UI action" 失败回执, 整个面板全黑。
     *
     * 没注册就是 EngineerSystem.register 的接线掉了, 直接炸。
     */
    private static void ensureRegistered() {
        if (WebUiServerDispatcher.resolve(STATE_ACTION) == null) {
            throw new IllegalStateException(
                    "job.engineer.state action 未注册: EngineerSystem.register 没有调用 EngineerWebUiActions.registerAll");
        }
    }

    private static WebUiAction require(GameTestHelper helper, String action) {
        ensureRegistered();
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }

    private static JsonObject state(GameTestHelper helper, ServerPlayer sender) {
        return JsonParser.parseString(require(helper, STATE_ACTION).handle(sender, new JsonObject()))
                .getAsJsonObject();
    }

    private static int unlockedTierCount(JsonObject state) {
        int count = 0;
        for (JsonElement element : state.getAsJsonArray("tiers")) {
            if (element.getAsJsonObject().get("unlocked").getAsBoolean()) {
                count++;
            }
        }
        return count;
    }

    private static void assertAllEffectsUnlocked(GameTestHelper helper, JsonObject state, boolean expected) {
        for (JsonElement element : state.getAsJsonArray("armorEffects")) {
            JsonObject row = element.getAsJsonObject();
            helper.assertTrue(row.get("unlocked").getAsBoolean() == expected,
                    row.get("effectId").getAsString() + " 的解锁位应为 " + expected
                            + " (四个特效同时在最低可掷档解锁)");
        }
    }

    private static int tierValue(GameTestHelper helper, JsonObject state, NanoTier tier, String key) {
        for (JsonElement element : state.getAsJsonArray("tiers")) {
            JsonObject row = element.getAsJsonObject();
            if (tier.name().toLowerCase().equals(row.get("tierId").getAsString())) {
                return row.get(key).getAsInt();
            }
        }
        helper.fail("回执里没有 " + tier.name().toLowerCase() + " 这一档");
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static double stat(GameTestHelper helper, JsonArray effects, NanoEffect effect, String key) {
        return statRow(helper, effects, effect, key).get("value").getAsDouble();
    }

    private static String unitOf(GameTestHelper helper, JsonArray effects, NanoEffect effect, String key) {
        return statRow(helper, effects, effect, key).get("unit").getAsString();
    }

    private static JsonObject statRow(GameTestHelper helper, JsonArray effects, NanoEffect effect, String key) {
        for (JsonElement element : effects) {
            JsonObject row = element.getAsJsonObject();
            if (!effect.id().equals(row.get("effectId").getAsString())) {
                continue;
            }
            for (JsonElement statElement : row.getAsJsonArray("stats")) {
                JsonObject stat = statElement.getAsJsonObject();
                if (key.equals(stat.get("key").getAsString())) {
                    return stat;
                }
            }
        }
        helper.fail("特效 " + effect.id() + " 的数值里没有 " + key);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static void setEngineerLevel(ServerPlayer player, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.ENGINEER).setLevel(level);
    }

    private static void setReactorCdEndTick(ServerPlayer player, long tick) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.ENGINEER).setNanoReactorCdEndTick(tick);
    }
}
