package com.miningdim.champion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.integration.ChampionPromoter;
import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.Map;

/**
 * W8 精英怪图鉴的 champion.codex / champion.inspect GameTest。
 *
 * 三条主线 (删掉被测那段逻辑必挂):
 *  1. codex 是四张枚举表的<b>逐字</b> dump —— 35 词条 x 5 档数值/成本/可用档 + 10 星主数据 + 3 难度分布,
 *     期望值全部现取自 {@link AffixDef}/{@link StarRank}/{@link ChampionSpawnPolicy} 本身, 任一行漏发/错序即挂;
 *  2. <b>量纲</b>: 逐条抽查 primaryUnit/secondaryUnit 的具体字符串。BLINK 的 9.0 是"9 秒周期"、FLAMMABLE_REGEN
 *     的 8.0 是"每秒 8 点血", 两个数量级几乎一样的数值必须带着不同的量纲下发, 否则前端只能瞎猜;
 *  3. <b>血量口径</b>: 6★+ 走自定义血池而不是 generic.max_health (后者被属性上限钳在 1024)。inspect 必须发出
 *     池子里的真值并用 healthSource 标明来源 —— 读错一套, 前端画出来的血条比例就是错的。
 *
 * 另锁: 查不到实体 / 不是精英怪一律业务拒绝, 不许返回一份全 0 的成功回执冒充"这只怪什么都没有"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w8";

    private static final String CODEX_ACTION = "champion.codex";
    private static final String INSPECT_ACTION = "champion.inspect";

    private static final double EPS = 1e-6D;

    /**
     * codex 回执的自设长度预算 (字符)。下行硬上限是 {@link FriendlyByteBuf#MAX_STRING_LENGTH} = 32767,
     * 这里刻意卡在它的六成左右: 图鉴迟早要加词条/加星级字段, 撞线前先在测试里失败, 而不是等真服上线后被
     * 派发器的 RESPONSE_TOO_LARGE 兜底替换成一条错误回执 (那时整页图鉴直接白屏)。
     */
    private static final int CODEX_JSON_BUDGET = 20_000;

    // ============================================================
    // 1. codex: 35 词条逐字 dump
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void codexDumpsEveryAffixWithTiersCostsAndAvailability(GameTestHelper helper) {
        JsonArray affixes = codex(helper).getAsJsonArray("affixes");
        helper.assertTrue(affixes.size() == AffixDef.values().length && affixes.size() == 35,
                "词条恒 35 条 (AffixDef 基数), 实得 " + affixes.size());

        for (int i = 0; i < AffixDef.values().length; i++) {
            AffixDef def = AffixDef.values()[i];
            JsonObject row = affixes.get(i).getAsJsonObject();
            helper.assertTrue(def.name().equals(row.get("affixId").getAsString()),
                    "第 " + i + " 条必须是 " + def.name() + " (顺序 = AffixDef 声明序), 实得 "
                            + row.get("affixId").getAsString());
            helper.assertTrue(def.displayNameKey().equals(row.get("nameKey").getAsString())
                            && row.get("nameKey").getAsString().startsWith("affix.champions."),
                    def.name() + " 必须发翻译键而不是中文名 (专用服务端不加载 lang)");
            helper.assertTrue(def.pool().name().equals(row.get("pool").getAsString()),
                    def.name() + " 的池必须是 " + def.pool().name());
            helper.assertTrue(row.get("baseCost").getAsInt() == def.baseCost()
                            && row.get("minStar").getAsInt() == def.minStar()
                            && row.get("isSkill").getAsBoolean() == def.isSkill(),
                    def.name() + " 的成本/最低星/技能位必须原样下发");
            helper.assertTrue(def.mutexFlag().name().equals(row.get("mutexFlag").getAsString()),
                    def.name() + " 的互斥族必须发枚举名 (无互斥发 NONE 而不是 null)");
            helper.assertTrue(def.minUsableQuality().name().equals(row.get("minQuality").getAsString()),
                    def.name() + " 的最低可用品质必须 = AffixDef.minUsableQuality");

            JsonArray values = row.getAsJsonArray("primaryValues");
            JsonArray costs = row.getAsJsonArray("costs");
            JsonArray available = row.getAsJsonArray("availableTiers");
            helper.assertTrue(values.size() == 5 && costs.size() == 5 && available.size() == 5,
                    def.name() + " 的三个数组恒 5 项 (下标 = 品质档)");
            for (AffixQuality quality : AffixQuality.values()) {
                int tier = quality.valueIndex();
                helper.assertTrue(Math.abs(values.get(tier).getAsDouble() - def.valueFor(quality)) < EPS,
                        def.name() + " 的 " + quality.name() + " 档数值必须 = " + def.valueFor(quality));
                helper.assertTrue(costs.get(tier).getAsInt() == def.costAt(quality),
                        def.name() + " 的 " + quality.name() + " 档成本必须 = ceil(baseCost x 品质系数) = "
                                + def.costAt(quality));
                helper.assertTrue(available.get(tier).getAsBoolean() == (def.valueFor(quality) != 0.0D),
                        def.name() + " 的 " + quality.name() + " 档可用位必须与该档是否为占位 0 一致");
            }
        }
        helper.succeed();
    }

    /**
     * 占位 0 必须被标成"该档不存在", 而不是被当成一个真的 0 值画进图鉴。
     *
     * 三种形态各测一条: 无占位 (复合装甲 5 档全可用) / 前导占位 (重型护甲最低高级) / <b>中段占位</b>
     * (自我修复的中级档在 spec 里写作 "—", 是整张表里唯一一个夹在有效档之间的洞)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void codexMarksPlaceholderTiersAsUnavailable(GameTestHelper helper) {
        JsonArray affixes = codex(helper).getAsJsonArray("affixes");

        JsonArray composite = affixRow(helper, affixes, AffixDef.COMPOSITE_ARMOR).getAsJsonArray("availableTiers");
        for (int tier = 0; tier < 5; tier++) {
            helper.assertTrue(composite.get(tier).getAsBoolean(), "复合装甲 5 档全部可用");
        }

        JsonObject heavy = affixRow(helper, affixes, AffixDef.HEAVY_ARMOR);
        JsonArray heavyTiers = heavy.getAsJsonArray("availableTiers");
        helper.assertTrue(!heavyTiers.get(0).getAsBoolean() && !heavyTiers.get(1).getAsBoolean()
                        && heavyTiers.get(2).getAsBoolean() && heavyTiers.get(3).getAsBoolean()
                        && heavyTiers.get(4).getAsBoolean(),
                "重型护甲前两档是占位 0 (最低高级), 可用位应为 [false,false,true,true,true], 实得 " + heavyTiers);
        helper.assertTrue(AffixQuality.RARE.name().equals(heavy.get("minQuality").getAsString()),
                "重型护甲最低可用品质是高级 (RARE), 实得 " + heavy.get("minQuality").getAsString());

        JsonArray repairTiers = affixRow(helper, affixes, AffixDef.SELF_REPAIR).getAsJsonArray("availableTiers");
        helper.assertTrue(repairTiers.get(0).getAsBoolean() && !repairTiers.get(1).getAsBoolean()
                        && repairTiers.get(2).getAsBoolean(),
                "自我修复的中级档是 spec 里那个 '—' (中段占位), 可用位必须为 false, 实得 " + repairTiers);
        helper.succeed();
    }

    /**
     * 量纲 (本组最容易翻车的一条)。
     *
     * 闪光的 9.0 与易燃再生的 8.0 数量级几乎相同, 语义却是"9 秒一次瞬移"与"每秒回 8 点血"; 刚毅护盾的 120
     * 是"单次伤害封顶 120 点"而且越小越硬。这些都只能靠 unit 区分, 发错一条整张图鉴就在说谎。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void codexTagsEveryAffixWithItsOwnDimension(GameTestHelper helper) {
        JsonArray affixes = codex(helper).getAsJsonArray("affixes");

        assertPrimaryUnit(helper, affixes, AffixDef.COMPOSITE_ARMOR, "fraction_damage_reduction");
        assertPrimaryUnit(helper, affixes, AffixDef.REGEN_TISSUE, "fraction_maxhp_per_second");
        assertPrimaryUnit(helper, affixes, AffixDef.FLAMMABLE_REGEN, "flat_hp_per_second");
        assertPrimaryUnit(helper, affixes, AffixDef.FORTITUDE_SHIELD, "flat_hp_damage_cap");
        assertPrimaryUnit(helper, affixes, AffixDef.CORROSIVE, "durability_points_per_hit");
        assertPrimaryUnit(helper, affixes, AffixDef.DOUBLE_STRIKE, "hit_count");
        assertPrimaryUnit(helper, affixes, AffixDef.CHAOS_STRIKE, "flag");
        assertPrimaryUnit(helper, affixes, AffixDef.BLINK, "seconds_cooldown");
        assertPrimaryUnit(helper, affixes, AffixDef.PHASE_WALK, "seconds_duration");
        assertPrimaryUnit(helper, affixes, AffixDef.DEATH_MARK, "multiplier");
        assertPrimaryUnit(helper, affixes, AffixDef.COUNTER_UNIT, "fraction_reflect");

        // 数值撞车但语义天差地别的两条: 单位必须不同, 否则前端只能按数值大小瞎排。
        JsonObject blink = affixRow(helper, affixes, AffixDef.BLINK);
        JsonObject regen = affixRow(helper, affixes, AffixDef.FLAMMABLE_REGEN);
        helper.assertTrue(Math.abs(blink.getAsJsonArray("primaryValues").get(0).getAsDouble() - 9.0D) < EPS
                        && Math.abs(regen.getAsJsonArray("primaryValues").get(0).getAsDouble() - 8.0D) < EPS,
                "前置校验: 闪光普通档 9.0 / 易燃再生普通档 8.0 (数量级相同)");
        helper.assertTrue(!blink.get("primaryUnit").getAsString().equals(regen.get("primaryUnit").getAsString()),
                "9 秒周期与每秒 8 点血必须带着不同量纲下发");

        // 每条词条都必须有量纲, 一条都不许留空 (漏登记一条就等于把那条的数值丢给前端猜)。
        for (JsonElement element : affixes) {
            JsonObject row = element.getAsJsonObject();
            helper.assertTrue(!row.get("primaryUnit").getAsString().isBlank(),
                    row.get("affixId").getAsString() + " 缺主数值量纲");
        }

        // 副数值恰好 5 条词条有, 且各自量纲独立 (寒霜的副数值是减速比率, 天雷的是落点个数)。
        int withSecondary = 0;
        for (JsonElement element : affixes) {
            JsonObject row = element.getAsJsonObject();
            AffixDef def = AffixDef.valueOf(row.get("affixId").getAsString());
            boolean hasSecondary = row.has("secondaryValues");
            helper.assertTrue(hasSecondary == def.hasSecondaryValues(),
                    def.name() + " 的副数值键必须与 AffixDef.hasSecondaryValues 一致");
            if (hasSecondary) {
                withSecondary++;
                helper.assertTrue(row.getAsJsonArray("secondaryValues").size() == 5
                                && !row.get("secondaryUnit").getAsString().isBlank(),
                        def.name() + " 的副数值必须是 5 项且自带量纲");
            }
        }
        helper.assertTrue(withSecondary == 5,
                "恰好 5 条词条带副数值 (巨大化/缩小化/寒霜/天雷/支援), 实得 " + withSecondary);

        assertSecondaryUnit(helper, affixes, AffixDef.GIGANTISM, "fraction_size_bonus");
        assertSecondaryUnit(helper, affixes, AffixDef.MINIATURIZATION, "fraction_size_penalty");
        assertSecondaryUnit(helper, affixes, AffixDef.FROST, "fraction_slow_per_stack");
        assertSecondaryUnit(helper, affixes, AffixDef.THUNDER, "strike_count");
        assertSecondaryUnit(helper, affixes, AffixDef.SUMMON_SUPPORT, "concurrent_count");

        JsonArray frostSecondary = affixRow(helper, affixes, AffixDef.FROST).getAsJsonArray("secondaryValues");
        for (AffixQuality quality : AffixQuality.values()) {
            helper.assertTrue(Math.abs(frostSecondary.get(quality.valueIndex()).getAsDouble()
                            - AffixDef.FROST.secondaryValueFor(quality)) < EPS,
                    "寒霜 " + quality.name() + " 档减速必须 = " + AffixDef.FROST.secondaryValueFor(quality));
        }
        helper.succeed();
    }

    // ============================================================
    // 2. codex: 10 星主数据表 + 品质表 + 难度分布
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void codexStarTableMatchesStarRankAndFlagsTheBloodPoolSwitch(GameTestHelper helper) {
        JsonObject result = codex(helper);
        helper.assertTrue(result.get("customBloodPoolMinStar").getAsInt() == StarRank.CUSTOM_BLOOD_POOL_MIN_STAR
                        && result.get("customBloodPoolMinStar").getAsInt() == 6,
                "血池换轨星级恒 6, 实得 " + result.get("customBloodPoolMinStar").getAsInt());

        JsonArray stars = result.getAsJsonArray("stars");
        helper.assertTrue(stars.size() == StarRank.values().length && stars.size() == 10,
                "星级表恒 10 行, 实得 " + stars.size());
        for (int i = 0; i < StarRank.values().length; i++) {
            StarRank rank = StarRank.values()[i];
            JsonObject row = stars.get(i).getAsJsonObject();
            helper.assertTrue(row.get("star").getAsInt() == rank.star() && row.get("star").getAsInt() == i + 1,
                    "第 " + i + " 行必须是 " + rank.star() + "★");
            helper.assertTrue(row.get("survivalBudget").getAsInt() == rank.survivalBudget()
                            && row.get("combatBudget").getAsInt() == rank.combatBudget()
                            && row.get("mobilityBudget").getAsInt() == rank.mobilityBudget()
                            && row.get("skillBudget").getAsInt() == rank.skillBudget(),
                    rank.star() + "★ 的四池预算必须逐列 = StarRank");
            helper.assertTrue(row.get("maxAffixes").getAsInt() == rank.maxAffixes()
                            && row.get("maxSkills").getAsInt() == rank.maxSkills(),
                    rank.star() + "★ 的词条上限/技能上限必须 = StarRank");
            helper.assertTrue(rank.maxQuality().name().equals(row.get("maxQuality").getAsString()),
                    rank.star() + "★ 的最高品质必须 = " + rank.maxQuality().name());
            helper.assertTrue(Math.abs(row.get("baseEffectiveHp").getAsDouble() - rank.baseEffectiveHp()) < EPS
                            && Math.abs(row.get("baseSingleHitPct").getAsDouble() - rank.baseSingleHitPct()) < EPS
                            && Math.abs(row.get("normalHitCapPct").getAsDouble() - rank.normalHitCapPct()) < EPS,
                    rank.star() + "★ 的基础有效血/单击基线/红线上限必须 = StarRank");
            helper.assertTrue(row.get("usesCustomBloodPool").getAsBoolean() == rank.usesCustomBloodPool()
                            && row.get("usesCustomBloodPool").getAsBoolean() == (rank.star() >= 6),
                    rank.star() + "★ 的血池位应为 " + (rank.star() >= 6));
            helper.assertTrue(row.get("barColorRgb").getAsInt() == rank.barColorRgb(),
                    rank.star() + "★ 的血条色必须 = " + rank.barColorRgb());
        }

        // 逐位核对两行真值 (防"实现与期望同错"): 5★ 基础有效血 765 仍在 1024 以内, 6★ 直接跳到 2700 破线。
        helper.assertTrue(Math.abs(stars.get(4).getAsJsonObject().get("baseEffectiveHp").getAsDouble() - 765.0D) < EPS
                        && !stars.get(4).getAsJsonObject().get("usesCustomBloodPool").getAsBoolean(),
                "5★ 基础有效血 765 且不走血池");
        helper.assertTrue(Math.abs(stars.get(5).getAsJsonObject().get("baseEffectiveHp").getAsDouble() - 2700.0D) < EPS
                        && stars.get(5).getAsJsonObject().get("usesCustomBloodPool").getAsBoolean(),
                "6★ 基础有效血 2700 且起走血池");
        // 红线 3 三档 (1-5★ 0.40 / 6-7★ 0.50 / 8-10★ 0.60): 抽三行确认档位没被压平成单值。
        helper.assertTrue(Math.abs(stars.get(0).getAsJsonObject().get("normalHitCapPct").getAsDouble() - 0.40D) < EPS
                        && Math.abs(stars.get(6).getAsJsonObject().get("normalHitCapPct").getAsDouble() - 0.50D) < EPS
                        && Math.abs(stars.get(9).getAsJsonObject().get("normalHitCapPct").getAsDouble() - 0.60D) < EPS,
                "红线 3 单击上限三档必须是 0.40 / 0.50 / 0.60");

        JsonArray qualities = result.getAsJsonArray("qualities");
        helper.assertTrue(qualities.size() == AffixQuality.values().length && qualities.size() == 5,
                "品质表恒 5 行, 实得 " + qualities.size());
        for (AffixQuality quality : AffixQuality.values()) {
            JsonObject row = qualities.get(quality.valueIndex()).getAsJsonObject();
            helper.assertTrue(quality.name().equals(row.get("qualityId").getAsString())
                            && row.get("tier").getAsInt() == quality.valueIndex()
                            && Math.abs(row.get("costMultiplier").getAsDouble() - quality.costMultiplier()) < EPS
                            && row.get("displayColorRgb").getAsInt() == quality.displayColor(),
                    quality.name() + " 的档位/成本系数/展示色必须 = AffixQuality");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void codexDistributionMatchesSpawnPolicy(GameTestHelper helper) {
        JsonObject result = codex(helper);
        helper.assertTrue("UNIFORM_INCLUSIVE".equals(result.get("starRollMode").getAsString()),
                "掷星是区间内均匀取整, 必须明说是 UNIFORM_INCLUSIVE (免得前端自造权重表)");

        JsonArray distribution = result.getAsJsonArray("distribution");
        helper.assertTrue(distribution.size() == Difficulty.values().length && distribution.size() == 3,
                "难度分布恒 3 行, 实得 " + distribution.size());
        for (int i = 0; i < Difficulty.values().length; i++) {
            Difficulty difficulty = Difficulty.values()[i];
            JsonObject row = distribution.get(i).getAsJsonObject();
            helper.assertTrue(difficulty.name().equals(row.get("difficulty").getAsString())
                            && difficulty.configName().equals(row.get("configName").getAsString()),
                    "第 " + i + " 行必须是 " + difficulty.name());
            helper.assertTrue(Math.abs(row.get("promoteChance").getAsDouble()
                            - ChampionSpawnPolicy.promoteChance(difficulty)) < EPS,
                    difficulty.name() + " 的升格率必须 = " + ChampionSpawnPolicy.promoteChance(difficulty));
            helper.assertTrue(row.get("minStar").getAsInt() == ChampionSpawnPolicy.minStar(difficulty)
                            && row.get("maxStar").getAsInt() == ChampionSpawnPolicy.maxStar(difficulty),
                    difficulty.name() + " 的星级区间必须 = ["
                            + ChampionSpawnPolicy.minStar(difficulty) + ", "
                            + ChampionSpawnPolicy.maxStar(difficulty) + "]");
        }
        // 逐位核对硬值 (与 ChampionSpawnPolicy 常量同错的风险由这三行兜住)。
        helper.assertTrue(Math.abs(distribution.get(0).getAsJsonObject().get("promoteChance").getAsDouble() - 0.06D) < EPS
                        && Math.abs(distribution.get(1).getAsJsonObject().get("promoteChance").getAsDouble() - 0.10D) < EPS
                        && Math.abs(distribution.get(2).getAsJsonObject().get("promoteChance").getAsDouble() - 0.15D) < EPS,
                "三档升格率必须是 6% / 10% / 15%");
        helper.assertTrue(distribution.get(2).getAsJsonObject().get("maxStar").getAsInt() == 10,
                "HARD 才刷得出 10★ 世界 BOSS");
        helper.succeed();
    }

    /**
     * 回执体积: 图鉴是全量 dump, 撞上 32767 的下行上限就会被派发器整条换成 RESPONSE_TOO_LARGE (整页白屏)。
     * 卡在自设预算上失败, 比等真服白屏早得多。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void codexStaysWellUnderTheDownstreamStringLimit(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        String json = handler(helper, CODEX_ACTION).handle(player, new JsonObject());

        helper.assertTrue(json.length() <= FriendlyByteBuf.MAX_STRING_LENGTH,
                "codex 回执 " + json.length() + " 字符, 已超下行硬上限 "
                        + FriendlyByteBuf.MAX_STRING_LENGTH);
        helper.assertTrue(json.length() <= CODEX_JSON_BUDGET,
                "codex 回执 " + json.length() + " 字符, 超过自设预算 " + CODEX_JSON_BUDGET
                        + " (离硬上限 " + FriendlyByteBuf.MAX_STRING_LENGTH + " 太近, 先精简字段)");
        // 下界同样要断言: 一个空 dump 也能轻松通过上界检查。
        helper.assertTrue(json.length() > 8_000,
                "codex 回执只有 " + json.length() + " 字符, 明显不是 35 词条 + 10 星级的完整 dump");
        helper.succeed();
    }

    // ============================================================
    // 3. inspect: 血量口径 (vanilla / 血池)
    // ============================================================

    /** 1-5★ 且有效血未破 1024: 权威血量就是 vanilla 那一套, healthSource 必须如实说。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void inspectReportsVanillaHealthForLowStarChampions(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.REGEN_TISSUE, AffixQuality.COMMON);
        try {
            ChampionPromoter.applyChampion(zombie, 5, affixes);
            double expectedEffectiveHp = ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_5, affixes);
            helper.assertTrue(expectedEffectiveHp < BloodPool.VANILLA_MAX_HEALTH_CLAMP,
                    "前置条件: 5★ 单词条的有效血 " + expectedEffectiveHp + " 必须仍在 1024 以内 (否则测不到 vanilla 分支)");
            helper.assertTrue(BloodPoolRegistry.get(zombie.getUUID()) == null,
                    "前置条件: 5★ 且有效血未破 1024 的冠军不建血池");

            JsonObject result = inspect(helper, player, zombie.getId());
            helper.assertTrue(result.get("star").getAsInt() == 5, "星级必须发 5");
            helper.assertTrue(!result.get("customBloodPool").getAsBoolean()
                            && "VANILLA_MAX_HEALTH".equals(result.get("healthSource").getAsString()),
                    "低星冠军的血量来自 generic.max_health, healthSource 必须是 VANILLA_MAX_HEALTH, 实得 "
                            + result.get("healthSource").getAsString());
            helper.assertTrue(Math.abs(result.get("maxHealth").getAsDouble() - zombie.getMaxHealth()) < 1e-3D
                            && Math.abs(result.get("health").getAsDouble() - zombie.getHealth()) < 1e-3D,
                    "vanilla 分支下 health/maxHealth 必须逐位 = 实体属性");
            helper.assertTrue(Math.abs(result.get("effectiveHp").getAsDouble() - expectedEffectiveHp) < 1e-3D,
                    "effectiveHp 必须 = 点数换算后的有效血 " + expectedEffectiveHp);
            helper.assertTrue(Math.abs(result.get("healthFraction").getAsDouble() - 1.0D) < 1e-3D,
                    "刚盖章的冠军是满血, 比例应为 1.0");

            JsonArray rolled = result.getAsJsonArray("affixes");
            helper.assertTrue(rolled.size() == 1, "该冠军只装配了 1 条词条, 实得 " + rolled.size());
            JsonObject row = rolled.get(0).getAsJsonObject();
            helper.assertTrue(AffixDef.REGEN_TISSUE.name().equals(row.get("affixId").getAsString())
                            && AffixQuality.COMMON.name().equals(row.get("quality").getAsString()),
                    "词条必须是 REGEN_TISSUE/COMMON, 实得 " + row.get("affixId").getAsString()
                            + "/" + row.get("quality").getAsString());
            helper.assertTrue(Math.abs(row.get("primaryValue").getAsDouble()
                            - AffixDef.REGEN_TISSUE.valueFor(AffixQuality.COMMON)) < EPS
                            && "fraction_maxhp_per_second".equals(row.get("primaryUnit").getAsString())
                            && row.get("cost").getAsInt() == AffixDef.REGEN_TISSUE.costAt(AffixQuality.COMMON),
                    "在场词条必须带上该品质档的真实数值/量纲/成本");
            helper.succeed();
        } finally {
            BloodPoolRegistry.remove(zombie.getUUID());
            zombie.discard();
        }
    }

    /**
     * 6★+ 走自定义血池: 权威血量是池子里的真值 (可远超 1024), vanilla 那份只是被属性上限钳住的渲染镜像。
     *
     * 扣一刀之后两套数字必然分叉 —— 实现若读的是 vanilla, health 会等于 vanillaHealth, 本条即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void inspectReportsBloodPoolHealthForSixStarAndAbove(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.COMPOSITE_ARMOR, AffixQuality.RARE);
        try {
            ChampionPromoter.applyChampion(zombie, 7, affixes);
            BloodPool pool = BloodPoolRegistry.get(zombie.getUUID());
            helper.assertTrue(pool != null, "前置条件: 7★ 冠军必须建了自定义血池");
            helper.assertTrue(pool.maxHp() > BloodPool.VANILLA_MAX_HEALTH_CLAMP,
                    "前置条件: 池子上限 " + pool.maxHp() + " 必须已破 1024 (否则测不到分叉)");

            JsonObject full = inspect(helper, player, zombie.getId());
            helper.assertTrue(full.get("customBloodPool").getAsBoolean()
                            && "BLOOD_POOL".equals(full.get("healthSource").getAsString()),
                    "6★+ 的 healthSource 必须是 BLOOD_POOL, 实得 " + full.get("healthSource").getAsString());
            helper.assertTrue(Math.abs(full.get("maxHealth").getAsDouble() - pool.maxHp()) < EPS,
                    "maxHealth 必须发池子上限 " + pool.maxHp() + ", 实得 " + full.get("maxHealth").getAsDouble());
            helper.assertTrue(full.get("maxHealth").getAsDouble() > full.get("vanillaMaxHealth").getAsDouble(),
                    "池子上限必须大于被属性钳住的 vanilla 上限 (前端拿后者算比例就是错的)");

            // 只扣血池, 不动 vanilla 血 (渲染镜像由 tick handler 另行同步): 两套数字就此分叉。
            double damage = 1_000.0D;
            pool.applyDamage(damage);
            JsonObject hurt = inspect(helper, player, zombie.getId());
            helper.assertTrue(Math.abs(hurt.get("health").getAsDouble() - (pool.maxHp() - damage)) < EPS,
                    "扣血后 health 必须是池子里的 " + (pool.maxHp() - damage)
                            + ", 实得 " + hurt.get("health").getAsDouble());
            helper.assertTrue(Math.abs(hurt.get("health").getAsDouble()
                            - hurt.get("vanillaHealth").getAsDouble()) > 1.0D,
                    "扣血后血池与 vanilla 血必然分叉; 两者相等说明实现读的是 vanilla 那一套");
            // 容差放到 1e-5: healthFraction 复用 ChampionBossBarText.progress (float 返回值), float 尾数只有
            // 24 位, 与 double 期望值天然有 ~1e-7 的量化差, 不是实现错。
            helper.assertTrue(Math.abs(hurt.get("healthFraction").getAsDouble() - pool.fraction()) < 1e-5D,
                    "血条比例必须按池子算, 实得 " + hurt.get("healthFraction").getAsDouble()
                            + " 期望 " + pool.fraction());
            helper.succeed();
        } finally {
            BloodPoolRegistry.remove(zombie.getUUID());
            zombie.discard();
        }
    }

    /** 查不到实体 / 不是精英怪: 必须业务拒绝并说清是哪一种, 不许返回一份全 0 的成功回执。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void inspectRejectsMissingEntityAndPlainMob(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        WebUiAction inspect = handler(helper, INSPECT_ACTION);

        JsonObject missing = new JsonObject();
        missing.addProperty("entityId", Integer.MAX_VALUE - 7);
        WebUiBusinessException notFound = expectRejection(helper, inspect, player, missing);
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(notFound.errorCode()),
                "错误码必须是 INVALID_REQUEST, 实得 " + notFound.errorCode());
        helper.assertTrue(ChampionWebUiActions.REASON_ENTITY_NOT_FOUND.equals(notFound.params().get("reason")),
                "找不到实体时 params.reason 必须是 ENTITY_NOT_FOUND, 实得 " + notFound.params());

        Zombie plain = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        try {
            helper.assertTrue(!MiningChampions.isChampion(plain), "前置条件: 刚刷的僵尸不是冠军");
            JsonObject payload = new JsonObject();
            payload.addProperty("entityId", plain.getId());
            WebUiBusinessException notChampion = expectRejection(helper, inspect, player, payload);
            helper.assertTrue(ChampionWebUiActions.REASON_NOT_A_CHAMPION.equals(notChampion.params().get("reason")),
                    "普通怪必须以 NOT_A_CHAMPION 拒绝 (而不是回一份 star=0 的假成功), 实得 " + notChampion.params());
            helper.assertTrue(Integer.toString(plain.getId()).equals(notChampion.params().get("value"))
                            && "entityId".equals(notChampion.params().get("field")),
                    "拒绝回执必须指明是 entityId 这个字段的哪个值被拒");
            helper.succeed();
        } finally {
            plain.discard();
        }
    }

    // ============================================================
    // 4. 注册名
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void championActionsAreRegisteredUnderContractNames(GameTestHelper helper) {
        ensureChampionActionsRegistered();
        helper.assertTrue(WebUiServerDispatcher.resolve(CODEX_ACTION) != null
                        && WebUiServerDispatcher.resolve(INSPECT_ACTION) != null,
                "champion.codex / champion.inspect 必须由 ChampionWebUiActions.registerAll 注册进派发器");
        helper.assertTrue(WebUiServerDispatcher.resolve("champion.affixes") == null
                        && WebUiServerDispatcher.resolve("champion.detail") == null,
                "图鉴只有这两条 action, 不得为同一份数据另注册别名");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 注册守卫。<b>刻意不在测试侧补注册</b>: 补上了, "ChampionSystem.register 忘了调 ChampionWebUiActions.registerAll"
     * 这一类装配缺陷就永远测不出来 —— 把生产侧那一行删掉, 本文件全绿, 而真服上前端调 champion.* action 只会拿到
     * 派发器的 "unknown Web UI action" 失败回执, 整个面板全黑。
     *
     * 没注册就是 ChampionSystem.register 的接线掉了, 直接炸。
     */
    private static void ensureChampionActionsRegistered() {
        if (WebUiServerDispatcher.resolve(CODEX_ACTION) == null) {
            throw new IllegalStateException(
                    "champion.* action 未注册: ChampionSystem.register 没有调用 ChampionWebUiActions.registerAll");
        }
    }

    private static WebUiAction handler(GameTestHelper helper, String action) {
        ensureChampionActionsRegistered();
        WebUiAction resolved = WebUiServerDispatcher.resolve(action);
        if (resolved == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return resolved;
    }

    /** codex 不收入参, 也不看 sender 身份, 故按契约名取处理器直接调 (仍走注册表, 名字打错即挂)。 */
    private static JsonObject codex(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        return JsonParser.parseString(handler(helper, CODEX_ACTION).handle(player, new JsonObject()))
                .getAsJsonObject();
    }

    private static JsonObject inspect(GameTestHelper helper, ServerPlayer sender, int entityId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("entityId", entityId);
        return JsonParser.parseString(handler(helper, INSPECT_ACTION).handle(sender, payload)).getAsJsonObject();
    }

    /** 调用必须以业务拒绝失败; 成功返回或抛别的异常都就地判失败。 */
    private static WebUiBusinessException expectRejection(GameTestHelper helper, WebUiAction action,
                                                          ServerPlayer sender, JsonObject payload) {
        try {
            String result = action.handle(sender, payload);
            helper.fail("本次调用应当被拒绝, 却返回了成功回执: " + result);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static JsonObject affixRow(GameTestHelper helper, JsonArray affixes, AffixDef def) {
        for (JsonElement element : affixes) {
            JsonObject row = element.getAsJsonObject();
            if (def.name().equals(row.get("affixId").getAsString())) {
                return row;
            }
        }
        helper.fail("回执缺词条行 " + def.name());
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static void assertPrimaryUnit(GameTestHelper helper, JsonArray affixes, AffixDef def, String unit) {
        String actual = affixRow(helper, affixes, def).get("primaryUnit").getAsString();
        helper.assertTrue(unit.equals(actual),
                def.name() + " 的主数值量纲必须是 " + unit + ", 实得 " + actual);
    }

    private static void assertSecondaryUnit(GameTestHelper helper, JsonArray affixes, AffixDef def, String unit) {
        String actual = affixRow(helper, affixes, def).get("secondaryUnit").getAsString();
        helper.assertTrue(unit.equals(actual),
                def.name() + " 的副数值量纲必须是 " + unit + ", 实得 " + actual);
    }
}
