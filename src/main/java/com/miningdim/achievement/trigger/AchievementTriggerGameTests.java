package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.miningdim.champion.AffixDef;
import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.ore.OreType;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.PlayerTrigger;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;

/**
 * 成就自定义触发器 (Achievement_System_DesignSpec 6.2 P1 各行) 的 JSON 往返、非法条件拒收与条件判定。
 *
 * 放在触发器同包里, 为的是直接断言各实例包内可见的 matches: 判定写错 (比如把 min_star 当成等于、把 max_health_ratio
 * 的不等号写反) 时, 往返测试照样通过, 只有逐条喂边界值才测得出来。强断言, 每条都钉在边界两侧。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AchievementTriggerGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "achievement_foundation";

    private AchievementTriggerGameTests() {
    }

    /** 每个触发器写满全部条件字段后序列化、再经触发器反序列化、再序列化, 两次 JSON 必须逐字段相同, 字段名与 6.2 一致。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void triggerJsonRoundTrips(GameTestHelper helper) {
        DeserializationContext context = context(helper);

        JsonObject enter = roundTrip(helper, context, AchievementTriggers.ENTER_MINING,
                new EnterMiningTrigger.TriggerInstance(ContextAwarePredicate.ANY, Difficulty.HARD));
        helper.assertTrue("hard".equals(enter.get("difficulty").getAsString()), "enter_mining 的难度应写成 hard");

        JsonObject extraction = roundTrip(helper, context, AchievementTriggers.MINING_EXTRACTION,
                new MiningExtractionTrigger.TriggerInstance(ContextAwarePredicate.ANY, Difficulty.MEDIUM, 0.1D, 600L));
        helper.assertTrue(extraction.get("max_health_ratio").getAsDouble() == 0.1D
                        && extraction.get("threat_hit_within_ticks").getAsLong() == 600L
                        && "medium".equals(extraction.get("difficulty").getAsString()),
                "mining_extraction 的三个字段名与取值必须与 6.2 一致, 实为 " + extraction);

        JsonObject ore = roundTrip(helper, context, AchievementTriggers.MINE_ORE,
                new MineOreTrigger.TriggerInstance(ContextAwarePredicate.ANY, OreType.ANCIENT_DEBRIS, Difficulty.HARD));
        helper.assertTrue("ancient_debris".equals(ore.get("ore").getAsString()), "矿种应写成小写名 ancient_debris");

        JsonObject stat = roundTrip(helper, context, AchievementTriggers.STAT_AT_LEAST,
                StatAtLeastTrigger.TriggerInstance.of(AchievementStats.MINING_HARD_ACTIVE_TICKS.get(), 7_200_000));
        helper.assertTrue("miningdim:mining_hard_active_ticks".equals(stat.get("stat").getAsString())
                && stat.get("value").getAsInt() == 7_200_000, "stat_at_least 应写出统计项 id 与阈值, 实为 " + stat);

        JsonObject kill = roundTrip(helper, context, AchievementTriggers.CHAMPION_KILL,
                new ChampionKillTrigger.TriggerInstance(ContextAwarePredicate.ANY, 10, AffixDef.GIGANTISM, 0.05D,
                        true, 18_000L));
        helper.assertTrue(kill.get("min_star").getAsInt() == 10 && "GIGANTISM".equals(kill.get("affix").getAsString())
                        && kill.get("min_share").getAsDouble() == 0.05D && kill.get("solo").getAsBoolean()
                        && kill.get("max_fight_ticks").getAsLong() == 18_000L,
                "champion_kill 的五个字段必须全部写出, 实为 " + kill);

        JsonObject gun = roundTrip(helper, context, AchievementTriggers.GUN_KILL,
                GunKillTrigger.TriggerInstance.headshotBeyond(100.0D));
        helper.assertTrue(gun.get("min_horizontal_distance").getAsDouble() == 100.0D
                && gun.get("headshot").getAsBoolean(), "gun_kill 应写出距离与爆头, 实为 " + gun);

        JsonObject count = roundTrip(helper, context, AchievementTriggers.ACHIEVEMENT_COUNT,
                AchievementCountTrigger.TriggerInstance.atLeast(10));
        helper.assertTrue(count.get("count").getAsInt() == 10, "achievement_count 应写出 count=10");

        roundTrip(helper, context, AchievementTriggers.MARRIED,
                new PlayerTrigger.TriggerInstance(AchievementTriggers.MARRIED.getId(), ContextAwarePredicate.ANY));
        roundTrip(helper, context, AchievementTriggers.OPEN_SHARED_BACKPACK,
                new PlayerTrigger.TriggerInstance(AchievementTriggers.OPEN_SHARED_BACKPACK.getId(),
                        ContextAwarePredicate.ANY));

        // 可选字段缺省时一律不写出 (不写出一个"不限"的字面量), 反序列化后仍是"不限"。原版总会写出 player 谓词
        // (不限玩家时为 null), 那一项不归本模块管。
        JsonObject bare = roundTrip(helper, context, AchievementTriggers.MINING_EXTRACTION,
                MiningExtractionTrigger.TriggerInstance.any());
        helper.assertTrue(bare.keySet().equals(Set.of("player")) && bare.get("player").isJsonNull(),
                "无条件的 mining_extraction 除原版的 player 外不应写出任何字段, 实为 " + bare);

        List<ResourceLocation> ids = AchievementTriggers.all().stream().map(CriterionTrigger::getId).toList();
        helper.assertTrue(ids.containsAll(List.of(id("enter_mining"), id("mining_extraction"), id("mine_ore"),
                        id("stat_at_least"), id("champion_kill"), id("gun_kill"), id("achievement_count"),
                        id("married"), id("open_shared_backpack"))) && Set.copyOf(ids).size() == ids.size(),
                "应包含 6.2 的九个 P1 触发器且 id 不重复, 实为 " + ids);
        for (CriterionTrigger<?> trigger : AchievementTriggers.all()) {
            helper.assertTrue(CriteriaTriggers.getCriterion(trigger.getId()) == trigger,
                    trigger.getId() + " 必须已登记进原版触发器表 (FMLCommonSetup 注册)");
        }
        helper.succeed();
    }

    /** 写错的条件整条拒收 (进度加载失败), 不能退化成"不限"而把成就白送。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void malformedConditionsAreRejected(GameTestHelper helper) {
        DeserializationContext context = context(helper);
        assertRejected(helper, context, AchievementTriggers.ENTER_MINING, "{\"difficulty\":\"extreme\"}");
        assertRejected(helper, context, AchievementTriggers.MINING_EXTRACTION, "{\"max_health_ratio\":1.5}");
        assertRejected(helper, context, AchievementTriggers.MINING_EXTRACTION, "{\"threat_hit_within_ticks\":-1}");
        assertRejected(helper, context, AchievementTriggers.MINE_ORE, "{}");
        assertRejected(helper, context, AchievementTriggers.MINE_ORE, "{\"ore\":\"mithril\"}");
        assertRejected(helper, context, AchievementTriggers.STAT_AT_LEAST,
                "{\"stat\":\"miningdim:no_such_stat\",\"value\":1}");
        assertRejected(helper, context, AchievementTriggers.STAT_AT_LEAST,
                "{\"stat\":\"miningdim:mining_blocks_mined\",\"value\":0}");
        assertRejected(helper, context, AchievementTriggers.STAT_AT_LEAST,
                "{\"stat\":\"miningdim:mining_blocks_mined\"}");
        assertRejected(helper, context, AchievementTriggers.CHAMPION_KILL, "{\"min_star\":11}");
        assertRejected(helper, context, AchievementTriggers.CHAMPION_KILL, "{\"min_star\":0}");
        assertRejected(helper, context, AchievementTriggers.CHAMPION_KILL, "{\"affix\":\"NO_SUCH_AFFIX\"}");
        assertRejected(helper, context, AchievementTriggers.CHAMPION_KILL, "{\"min_share\":1.01}");
        assertRejected(helper, context, AchievementTriggers.GUN_KILL, "{\"min_horizontal_distance\":-1}");
        assertRejected(helper, context, AchievementTriggers.ACHIEVEMENT_COUNT, "{}");
        assertRejected(helper, context, AchievementTriggers.ACHIEVEMENT_COUNT, "{\"count\":0}");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningConditionsMatchAtTheirBoundaries(GameTestHelper helper) {
        EnterMiningTrigger.TriggerInstance anyEntry = EnterMiningTrigger.TriggerInstance.any();
        EnterMiningTrigger.TriggerInstance hardEntry =
                new EnterMiningTrigger.TriggerInstance(ContextAwarePredicate.ANY, Difficulty.HARD);
        helper.assertTrue(anyEntry.matches(Difficulty.EASY) && anyEntry.matches(Difficulty.HARD),
                "不限难度的 enter_mining 对任意难度都成立");
        helper.assertTrue(hardEntry.matches(Difficulty.HARD) && !hardEntry.matches(Difficulty.MEDIUM),
                "difficulty=hard 只认困难");

        MiningExtractionTrigger.TriggerInstance medium = MiningExtractionTrigger.TriggerInstance.in(Difficulty.MEDIUM);
        helper.assertTrue(medium.matches(Difficulty.MEDIUM, 1.0D, Long.MAX_VALUE)
                && !medium.matches(Difficulty.HARD, 1.0D, Long.MAX_VALUE), "difficulty=medium 只认中等");
        MiningExtractionTrigger.TriggerInstance escape =
                MiningExtractionTrigger.TriggerInstance.narrowEscape(0.10D, 600L);
        helper.assertTrue(escape.matches(Difficulty.EASY, 0.10D, 600L), "血量恰为 10%、恰在 600 tick 内被打过应成立");
        helper.assertTrue(!escape.matches(Difficulty.EASY, 0.11D, 0L), "血量 11% 不满足 max_health_ratio=0.10");
        helper.assertTrue(!escape.matches(Difficulty.EASY, 0.05D, 601L), "601 tick 前被打不满足 600 tick 内");
        helper.assertTrue(!escape.matches(Difficulty.HARD, 0.05D, Long.MAX_VALUE), "整趟没被打过不算死里逃生");

        MineOreTrigger.TriggerInstance iron = MineOreTrigger.TriggerInstance.of(OreType.IRON);
        MineOreTrigger.TriggerInstance hardTin =
                new MineOreTrigger.TriggerInstance(ContextAwarePredicate.ANY, OreType.TIN, Difficulty.HARD);
        helper.assertTrue(iron.matches(OreType.IRON, Difficulty.EASY) && !iron.matches(OreType.COAL, Difficulty.EASY),
                "mine_ore 只认指定矿种");
        helper.assertTrue(hardTin.matches(OreType.TIN, Difficulty.HARD)
                && !hardTin.matches(OreType.TIN, Difficulty.EASY), "带难度的 mine_ore 只认该难度");
        helper.assertTrue(OreType.values().length == 16, "矿脉图鉴的 16 种矿石应与 OreType 一一对应, 实为 "
                + OreType.values().length);

        StatsCounter stats = new StatsCounter();
        StatAtLeastTrigger.TriggerInstance thousand =
                StatAtLeastTrigger.TriggerInstance.of(AchievementStats.MINING_BLOCKS_MINED.get(), 1_000);
        // 基类 StatsCounter 的 setValue 不读玩家参数, 这里没有玩家可传。
        stats.setValue(null, Stats.CUSTOM.get(AchievementStats.MINING_BLOCKS_MINED.get()), 999);
        helper.assertTrue(!thousand.matches(stats), "999 < 1000 不应成立");
        stats.setValue(null, Stats.CUSTOM.get(AchievementStats.MINING_BLOCKS_MINED.get()), 1_000);
        helper.assertTrue(thousand.matches(stats), "恰为 1000 应成立 (至少 N)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void combatAndMetaConditionsMatchAtTheirBoundaries(GameTestHelper helper) {
        ChampionKillTrigger.TriggerInstance star6 = ChampionKillTrigger.TriggerInstance.minStar(6);
        helper.assertTrue(!star6.matches(kill(5, Set.of(), 1.0D, false, 100L))
                && star6.matches(kill(6, Set.of(), 0.01D, false, 100L))
                && star6.matches(kill(9, Set.of(), 0.01D, false, 100L)), "min_star=6 是 6 星及以上");

        ChampionKillTrigger.TriggerInstance giant = ChampionKillTrigger.TriggerInstance.withAffix(AffixDef.GIGANTISM);
        helper.assertTrue(!giant.matches(kill(3, Set.of(AffixDef.HEAVY_ARMOR), 1.0D, false, 100L))
                        && giant.matches(kill(1, Set.of(AffixDef.HEAVY_ARMOR, AffixDef.GIGANTISM), 0.1D, false, 100L)),
                "affix=GIGANTISM 只认死亡时持有巨大化的精英");

        ChampionKillTrigger.TriggerInstance solo9 = ChampionKillTrigger.TriggerInstance.soloWithin(9, 18_000L);
        helper.assertTrue(solo9.matches(kill(9, Set.of(), 1.0D, true, 18_000L)), "独自、恰好 15 分钟击倒 9 星应成立");
        helper.assertTrue(!solo9.matches(kill(9, Set.of(), 1.0D, true, 18_001L)), "超过 18000 tick 不成立");
        helper.assertTrue(!solo9.matches(kill(9, Set.of(), 1.0D, false, 100L)), "不是独自击杀不成立");
        helper.assertTrue(!solo9.matches(kill(8, Set.of(), 1.0D, true, 100L)), "8 星不成立");
        helper.assertTrue(!solo9.matches(kill(9, Set.of(), 1.0D, true, -1L)), "战斗时长不可知时 max_fight_ticks 不成立");

        ChampionKillTrigger.TriggerInstance worldBoss = new ChampionKillTrigger.TriggerInstance(
                ContextAwarePredicate.ANY, 10, null, 0.05D, false, null);
        helper.assertTrue(!worldBoss.matches(kill(10, Set.of(), 0.049D, false, 100L))
                && worldBoss.matches(kill(10, Set.of(), 0.05D, false, 100L)),
                "十星弑神的 min_share=0.05 是不少于 5%");

        GunKillTrigger.TriggerInstance longShot = GunKillTrigger.TriggerInstance.headshotBeyond(100.0D);
        helper.assertTrue(longShot.matches(100.0D, true), "恰为 100 格爆头应成立");
        helper.assertTrue(!longShot.matches(99.9D, true), "不足 100 格不成立");
        helper.assertTrue(!longShot.matches(150.0D, false), "没有爆头不成立");

        AchievementCountTrigger.TriggerInstance ten = AchievementCountTrigger.TriggerInstance.atLeast(10);
        helper.assertTrue(!ten.matches(9) && ten.matches(10) && ten.matches(11), "count=10 是至少 10 个");
        helper.succeed();
    }

    private static ChampionKill kill(int star, Set<AffixDef> affixes, double share, boolean solo, long fightTicks) {
        return new ChampionKill(star, affixes, share, solo, fightTicks);
    }

    private static DeserializationContext context(GameTestHelper helper) {
        return new DeserializationContext(id("test/trigger_round_trip"), helper.getLevel().getServer().getLootData());
    }

    private static <T extends CriterionTriggerInstance> JsonObject roundTrip(GameTestHelper helper,
                                                                            DeserializationContext context,
                                                                            CriterionTrigger<T> trigger,
                                                                            CriterionTriggerInstance instance) {
        helper.assertTrue(trigger.getId().equals(instance.getCriterion()),
                "实例的触发器 id 应为 " + trigger.getId() + ", 实为 " + instance.getCriterion());
        JsonObject first = instance.serializeToJson(SerializationContext.INSTANCE);
        T parsed = trigger.createInstance(first.deepCopy(), context);
        JsonObject second = parsed.serializeToJson(SerializationContext.INSTANCE);
        helper.assertTrue(first.equals(second), trigger.getId() + " 往返后 JSON 不一致: " + first + " -> " + second);
        return first;
    }

    private static void assertRejected(GameTestHelper helper, DeserializationContext context,
                                       CriterionTrigger<?> trigger, String conditions) {
        boolean rejected = false;
        try {
            trigger.createInstance(JsonParser.parseString(conditions).getAsJsonObject(), context);
        } catch (JsonParseException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, trigger.getId() + " 必须拒收 " + conditions);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(MiningConstants.MODID, path);
    }
}
