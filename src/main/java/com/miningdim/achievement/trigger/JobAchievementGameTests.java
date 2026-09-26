package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.integration.ChampionPromoter;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobExperienceTracks;
import com.miningdim.job.JobId;
import com.miningdim.job.agent.AgentBountySavedData;
import com.miningdim.job.agent.AgentEvents;
import com.miningdim.job.agent.SealCategory;
import com.miningdim.job.agent.integration.AgentSealHandler;
import com.miningdim.job.brewer.BrewerConstants;
import com.miningdim.job.brewer.BrewerEvents;
import com.miningdim.job.brewer.WineQuality;
import com.miningdim.job.brewer.WineType;
import com.miningdim.job.brewer.station.BrewRecipes;
import com.miningdim.job.brewer.station.BrewingStationBlockEntity;
import com.miningdim.job.brewer.station.BrewingStationRegistry;
import com.miningdim.job.chef.ChefBlocks;
import com.miningdim.job.chef.ChefEvents;
import com.miningdim.job.chef.ChefQuality;
import com.miningdim.job.chef.ChefQualityNbt;
import com.miningdim.job.chef.SeasoningMenu;
import com.miningdim.job.chef.SeasoningTableBlock;
import com.miningdim.job.chef.SeasoningTableBlockEntity;
import com.miningdim.job.engineer.EngineerEvents;
import com.miningdim.job.engineer.ModEngineerBlocks;
import com.miningdim.job.engineer.NanoTier;
import com.miningdim.job.engineer.block.ProductionTableBlockEntity;
import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.MunitionsCaliber;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.MunitionsEvents;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotCastTiming;
import com.miningdim.job.tarot.TarotConfig;
import com.miningdim.job.tarot.TarotEvents;
import com.miningdim.job.tarot.TarotPlayHandler;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import com.miningdim.progression.ExperienceAward;
import com.miningdim.progression.ExperienceGrant;
import com.miningdim.progression.ExperienceServices;
import com.miningdim.progression.IExperienceService;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.PlayerTrigger;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 成就 P2 职业部分的 GameTest (Achievement_System_DesignSpec 9.3、9.10, batch {@code achievement_p2_jobs})。
 *
 * <ol>
 *   <li>七个职业触发器的 JSON 往返、写错的条件整条拒收、各条件在边界两侧的判定; 触发器与 farmer_harvests 统计项都已
 *       登记, 统计项两种语言都有名称。</li>
 *   <li>每个监听接口都驱动真实的生产代码 (经验路由、调味台、酿酒台、塔罗出牌、特勤封印、生产台、军火台): 恰好广播一次,
 *       广播那一刻生产方的状态 (经验、成品、槽位) 已经落定; 成就侧一个抛异常的监听器不打断生产方, 排在它后面的
 *       监听器照常收到。</li>
 *   <li>17 条成就都经真实的接口路径授予: 能驱动生产代码的直接驱动; 结果带随机性的 (闪耀料理、九种酒、闪耀酒) 调
 *       广播方的 fire 方法。</li>
 * </ol>
 *
 * <p>测试注册进各广播方的监听器随进程存在 (广播方刻意没有注销入口), 所以一律按本用例的玩家过滤, 用例结束后即成惰性。
 * 放在触发器同包里, 为的是直接断言各实例包内可见的 matches 与 {@link JobHooks#guarded}。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class JobAchievementGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "achievement_p2_jobs";
    private static final BlockPos STATION = new BlockPos(1, 1, 1);

    private JobAchievementGameTests() {
    }

    // ---- 1. 触发器与统计项 ----

    /** 每个职业触发器写满全部条件字段后往返两次, JSON 逐字段相同, 字段名与 6.2 一致; 触发器与统计项都已登记。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void jobTriggersRoundTripAndAreRegistered(GameTestHelper helper) throws IOException {
        DeserializationContext context = context(helper);

        JsonObject level = roundTrip(helper, context, JobTriggers.JOB_LEVEL,
                new JobLevelTrigger.TriggerInstance(ContextAwarePredicate.ANY, 7, JobId.ENGINEER, 1));
        helper.assertTrue(level.get("level").getAsInt() == 7 && "engineer".equals(level.get("job").getAsString())
                && !level.has("jobs_count"), "job_level 应写出 level 与稳定职业 id, 缺省的 jobs_count 不写出, 实为 " + level);
        JsonObject allMax = roundTrip(helper, context, JobTriggers.JOB_LEVEL,
                JobLevelTrigger.TriggerInstance.jobsAtLeast(10, 8));
        helper.assertTrue(allMax.get("jobs_count").getAsInt() == 8 && !allMax.has("job"),
                "全职精通应写成 level=10, jobs_count=8, 实为 " + allMax);
        JobLevelTrigger.TriggerInstance alias = JobTriggers.JOB_LEVEL.createInstance(
                JsonParser.parseString("{\"level\":3,\"job\":\"armorer\"}").getAsJsonObject(), context);
        JsonObject aliasJson = alias.serializeToJson(SerializationContext.INSTANCE);
        helper.assertTrue("engineer".equals(aliasJson.get("job").getAsString()),
                "旧别名 armorer 应解析成铸甲师, 写出时用稳定 id engineer, 实为 " + aliasJson);

        JsonObject dish = roundTrip(helper, context, JobTriggers.CHEF_DISH,
                new ChefDishTrigger.TriggerInstance(ContextAwarePredicate.ANY, ChefQuality.HIGH, ChefQuality.RADIANT));
        helper.assertTrue("high".equals(dish.get("min_quality").getAsString())
                && "radiant".equals(dish.get("target").getAsString()), "chef_dish 的两个字段, 实为 " + dish);

        JsonObject brew = roundTrip(helper, context, JobTriggers.BREW_COMPLETE,
                new BrewCompleteTrigger.TriggerInstance(ContextAwarePredicate.ANY, WineType.MAOTAI,
                        WineQuality.BRILLIANT));
        helper.assertTrue("maotai".equals(brew.get("wine_type").getAsString())
                && "brilliant".equals(brew.get("min_quality").getAsString()), "brew_complete 的两个字段, 实为 " + brew);

        JsonObject tarot = roundTrip(helper, context, JobTriggers.TAROT_PLAY,
                new TarotPlayTrigger.TriggerInstance(ContextAwarePredicate.ANY, TarotQuality.UR, 16));
        helper.assertTrue("ur".equals(tarot.get("min_quality").getAsString()) && tarot.get("card_id").getAsInt() == 16,
                "tarot_play 的两个字段, 实为 " + tarot);

        JsonObject seal = roundTrip(helper, context, JobTriggers.AGENT_SEAL,
                new AgentSealTrigger.TriggerInstance(ContextAwarePredicate.ANY, SealCategory.MECHANIC,
                        EnumSet.of(AffixDef.GIGANTISM, AffixDef.BURNING), 7));
        helper.assertTrue("mechanic".equals(seal.get("category").getAsString())
                        && seal.getAsJsonArray("affixes").size() == 2 && seal.get("min_star").getAsInt() == 7,
                "agent_seal 的三个字段, 实为 " + seal);

        JsonObject plate = roundTrip(helper, context, JobTriggers.NANO_PLATE_PRODUCED,
                new NanoPlateProducedTrigger.TriggerInstance(ContextAwarePredicate.ANY, NanoTier.SUPERIOR));
        helper.assertTrue("superior".equals(plate.get("min_tier").getAsString()), "nano_plate_produced, 实为 " + plate);

        roundTrip(helper, context, JobTriggers.MUNITIONS_BATCH,
                new PlayerTrigger.TriggerInstance(JobTriggers.MUNITIONS_BATCH.getId(), ContextAwarePredicate.ANY));

        JsonObject bare = roundTrip(helper, context, JobTriggers.AGENT_SEAL, AgentSealTrigger.TriggerInstance.any());
        helper.assertTrue(bare.keySet().equals(Set.of("player")),
                "无条件的 agent_seal 除原版的 player 外不应写出任何字段, 实为 " + bare);

        List<ResourceLocation> ids = JobTriggers.all().stream().map(CriterionTrigger::getId).toList();
        helper.assertTrue(ids.equals(List.of(AchievementIds.id("job_level"), AchievementIds.id("chef_dish"),
                        AchievementIds.id("brew_complete"), AchievementIds.id("tarot_play"),
                        AchievementIds.id("agent_seal"), AchievementIds.id("nano_plate_produced"),
                        AchievementIds.id("munitions_batch"))),
                "职业触发器应恰好是 6.2 的七个 P2 职业行, 实为 " + ids);
        for (CriterionTrigger<?> trigger : JobTriggers.all()) {
            helper.assertTrue(CriteriaTriggers.getCriterion(trigger.getId()) == trigger,
                    trigger.getId() + " 必须已登记进原版触发器表 (FMLCommonSetup 注册)");
        }

        ResourceLocation stat = AchievementIds.id("farmer_harvests");
        helper.assertTrue(BuiltInRegistries.CUSTOM_STAT.get(stat) == JobStats.FARMER_HARVESTS.get()
                        && Stats.CUSTOM.contains(JobStats.FARMER_HARVESTS.get()),
                "farmer_harvests 应注册在原版自定义统计注册表里, 并在通用初始化时建好 Stat 对象");
        for (String language : List.of("zh_cn", "en_us")) {
            JsonObject lang = readLang(language);
            String key = "stat.miningdim.farmer_harvests";
            helper.assertTrue(lang.has(key) && !lang.get(key).getAsString().isBlank()
                    && lang.get(key).getAsString().indexOf('§') < 0, language + " 缺少统计项名称 " + key);
        }
        helper.succeed();
    }

    /** 写错的条件整条拒收 (进度加载失败), 不能退化成"不限"而把成就白送。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void malformedJobConditionsAreRejected(GameTestHelper helper) {
        DeserializationContext context = context(helper);
        assertRejected(helper, context, JobTriggers.JOB_LEVEL, "{}");
        assertRejected(helper, context, JobTriggers.JOB_LEVEL, "{\"level\":0}");
        assertRejected(helper, context, JobTriggers.JOB_LEVEL, "{\"level\":11}");
        assertRejected(helper, context, JobTriggers.JOB_LEVEL, "{\"level\":2,\"job\":\"fisher\"}");
        assertRejected(helper, context, JobTriggers.JOB_LEVEL, "{\"level\":10,\"jobs_count\":0}");
        assertRejected(helper, context, JobTriggers.JOB_LEVEL, "{\"level\":10,\"jobs_count\":9}");
        assertRejected(helper, context, JobTriggers.JOB_LEVEL, "{\"level\":10,\"job\":\"miner\",\"jobs_count\":2}");
        assertRejected(helper, context, JobTriggers.CHEF_DISH, "{\"min_quality\":\"legendary\"}");
        assertRejected(helper, context, JobTriggers.CHEF_DISH, "{\"target\":\"RADIANT\"}");
        assertRejected(helper, context, JobTriggers.BREW_COMPLETE, "{\"wine_type\":\"sake\"}");
        assertRejected(helper, context, JobTriggers.BREW_COMPLETE, "{\"min_quality\":\"radiant\"}");
        assertRejected(helper, context, JobTriggers.TAROT_PLAY, "{\"card_id\":22}");
        assertRejected(helper, context, JobTriggers.TAROT_PLAY, "{\"card_id\":-1}");
        assertRejected(helper, context, JobTriggers.TAROT_PLAY, "{\"min_quality\":\"lr\"}");
        assertRejected(helper, context, JobTriggers.AGENT_SEAL, "{\"category\":\"active\"}");
        assertRejected(helper, context, JobTriggers.AGENT_SEAL, "{\"affixes\":[]}");
        assertRejected(helper, context, JobTriggers.AGENT_SEAL, "{\"affixes\":[\"NO_SUCH_AFFIX\"]}");
        assertRejected(helper, context, JobTriggers.AGENT_SEAL, "{\"affixes\":\"BURNING\"}");
        assertRejected(helper, context, JobTriggers.AGENT_SEAL, "{\"min_star\":11}");
        assertRejected(helper, context, JobTriggers.NANO_PLATE_PRODUCED, "{\"min_tier\":\"epic\"}");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void jobConditionsMatchAtTheirBoundaries(GameTestHelper helper) {
        JobLevelTrigger.TriggerInstance anyFour = JobLevelTrigger.TriggerInstance.anyJob(4);
        helper.assertTrue(!anyFour.matches(levels(3, JobId.MINER, 3)) && anyFour.matches(levels(1, JobId.BREWER, 4)),
                "level=4 是任意一个职业至少 4 级");
        JobLevelTrigger.TriggerInstance engineerSeven =
                new JobLevelTrigger.TriggerInstance(ContextAwarePredicate.ANY, 7, JobId.ENGINEER, 1);
        helper.assertTrue(!engineerSeven.matches(levels(10, JobId.ENGINEER, 6))
                        && engineerSeven.matches(levels(1, JobId.ENGINEER, 7)),
                "job=engineer 只看铸甲师自己的等级, 别的职业再高也不算");
        JobLevelTrigger.TriggerInstance allMax = JobLevelTrigger.TriggerInstance.jobsAtLeast(10, 8);
        helper.assertTrue(!allMax.matches(levels(10, JobId.BREWER, 9)) && allMax.matches(levels(10, JobId.BREWER, 10)),
                "jobs_count=8 要求八个职业全部满级, 差一个 9 级也不成立");
        JobLevelTrigger.TriggerInstance twoAtFive = JobLevelTrigger.TriggerInstance.jobsAtLeast(5, 2);
        Map<JobId, Integer> twoJobs = levels(1, JobId.CHEF, 5);
        twoJobs.put(JobId.TAROT, 6);
        helper.assertTrue(!twoAtFive.matches(levels(1, JobId.CHEF, 9)) && twoAtFive.matches(twoJobs),
                "jobs_count=2 数的是达到 level 的职业个数");

        ChefDishTrigger.TriggerInstance radiant = ChefDishTrigger.TriggerInstance.atLeast(ChefQuality.RADIANT);
        helper.assertTrue(!radiant.matches(ChefQuality.EXTRAORDINARY, ChefQuality.RADIANT)
                && radiant.matches(ChefQuality.RADIANT, ChefQuality.LOW), "min_quality=radiant 只认闪耀成品");
        ChefDishTrigger.TriggerInstance highTarget =
                new ChefDishTrigger.TriggerInstance(ContextAwarePredicate.ANY, null, ChefQuality.HIGH);
        helper.assertTrue(!highTarget.matches(ChefQuality.RADIANT, ChefQuality.LOW)
                && highTarget.matches(ChefQuality.LOW, ChefQuality.HIGH), "target 比的是开工时选定的挑战目标");
        helper.assertTrue(ChefDishTrigger.TriggerInstance.any().matches(ChefQuality.LOW, ChefQuality.LOW),
                "无条件的 chef_dish 对任意一道菜都成立");

        BrewCompleteTrigger.TriggerInstance gin = BrewCompleteTrigger.TriggerInstance.ofType(WineType.GIN);
        helper.assertTrue(!gin.matches(WineType.VODKA, WineQuality.BRILLIANT)
                && gin.matches(WineType.GIN, WineQuality.LOW), "wine_type=gin 只认金酒");
        BrewCompleteTrigger.TriggerInstance superb = BrewCompleteTrigger.TriggerInstance.atLeast(WineQuality.SUPERB);
        helper.assertTrue(!superb.matches(WineType.RUM, WineQuality.HIGH)
                && superb.matches(WineType.RUM, WineQuality.SUPERB)
                && superb.matches(WineType.RUM, WineQuality.BRILLIANT), "min_quality=superb 是不低于超凡");

        TarotPlayTrigger.TriggerInstance ur =
                new TarotPlayTrigger.TriggerInstance(ContextAwarePredicate.ANY, TarotQuality.UR, null);
        helper.assertTrue(!ur.matches(3, TarotQuality.SSR) && ur.matches(3, TarotQuality.UR)
                && ur.matches(3, TarotQuality.SHINY), "min_quality=ur 是 UR 及以上, 闪耀最高");
        TarotPlayTrigger.TriggerInstance tower =
                new TarotPlayTrigger.TriggerInstance(ContextAwarePredicate.ANY, null, TarotArcana.TOWER.cardId());
        helper.assertTrue(!tower.matches(TarotArcana.DEVIL.cardId(), TarotQuality.R)
                && tower.matches(TarotArcana.TOWER.cardId(), TarotQuality.R), "card_id 只认这一张牌");

        AgentSealTrigger.TriggerInstance mechanicBurning = new AgentSealTrigger.TriggerInstance(
                ContextAwarePredicate.ANY, SealCategory.MECHANIC, EnumSet.of(AffixDef.BURNING), 6);
        helper.assertTrue(mechanicBurning.matches(6, AffixDef.BURNING, SealCategory.MECHANIC),
                "三个条件同时满足时成立");
        helper.assertTrue(!mechanicBurning.matches(6, AffixDef.BURNING, SealCategory.PASSIVE), "类别不符不成立");
        helper.assertTrue(!mechanicBurning.matches(6, AffixDef.GIGANTISM, SealCategory.MECHANIC), "词条不在列表里不成立");
        helper.assertTrue(!mechanicBurning.matches(5, AffixDef.BURNING, SealCategory.MECHANIC), "5 星不满足 min_star=6");

        NanoPlateProducedTrigger.TriggerInstance high =
                new NanoPlateProducedTrigger.TriggerInstance(ContextAwarePredicate.ANY, NanoTier.HIGH);
        helper.assertTrue(!high.matches(NanoTier.MEDIUM) && high.matches(NanoTier.HIGH)
                && high.matches(NanoTier.RADIANT), "min_tier=high 是高级板及以上");
        helper.succeed();
    }

    // ---- 2. 经验路由: 职业等级与农夫收获 ----

    /** 经验发放的监听在轨道落账之后才通知, 恰好一次, 带发放前后的等级; 路由拒收的发放不通知。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void experienceAwardSeamFiresOnceAfterTheTrackCommits(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            IExperienceService experience = ExperienceServices.experienceService();
            ResourceLocation track = JobExperienceTracks.track(JobId.MINER);
            List<long[]> seen = new ArrayList<>();
            ExperienceServices.registerAwardListener((awarded, award, levelBefore) -> {
                if (awarded == player) {
                    failInGuard(player, "experience_award");
                }
            });
            ExperienceServices.registerAwardListener((awarded, award, levelBefore) -> {
                if (awarded == player) {
                    seen.add(new long[]{experience.snapshot(awarded, track).totalXp(), award.snapshot().totalXp(),
                            levelBefore, award.snapshot().level()});
                }
            });

            // 当天第一笔 7000 原始经验: 2000 x1.0 + 2000 x0.4 + 3000 x0.2 = 3400 有效, 从 1 级升到 2 级 (需 3300)。
            ExperienceAward award = grant(player, JobId.MINER, 7_000L);
            helper.assertTrue(seen.size() == 1, "一笔发放应恰好通知一次, 实为 " + seen.size());
            long[] observed = seen.get(0);
            helper.assertTrue(observed[0] == observed[1] && observed[1] == award.snapshot().totalXp(),
                    "通知时轨道必须已经落账: 监听器里读到的总经验应等于发放结果, 实为 " + observed[0] + " / " + observed[1]);
            helper.assertTrue(observed[2] == 1L && observed[3] == 2L,
                    "应带上发放前 1 级、发放后 2 级, 实为 " + observed[2] + " -> " + observed[3]);
            helper.assertTrue(award.effectiveXp() > 3_300L && experience.snapshot(player, track).level() == 2,
                    "抛异常的成就监听器不得打断发放: 经验照常入账, 实得 " + award.effectiveXp());

            boolean rejected = false;
            try {
                experience.award(player, new ExperienceGrant(track, AchievementIds.id("test/unregistered_source"), 5L));
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected && seen.size() == 1, "路由拒收 (未登记来源) 的发放不入账, 也不通知");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    /**
     * 职业等级只在某条职业轨道升级时判定, 判定时读全部职业的等级: 静默改级 (如 /job set) 不当场触发, 下一次任意职业
     * 升级时一并算上。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void jobLevelAchievementsFollowAnyJobLevelUp(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            setLevel(player, JobId.MINER, 7);
            grant(player, JobId.CHEF, 10L);
            helper.assertTrue(level(player, JobId.CHEF) == 1, "precondition: 10 点经验不够厨师升级");
            assertDone(helper, player, "profession/level_2", false);

            // 同一天再 7000 原始: 从 10 有效起, 1990 + 800 + 600 + 0.8 = 3390.8 有效, 厨师合计 3400, 升到 2 级。
            grant(player, JobId.CHEF, 7_000L);
            helper.assertTrue(level(player, JobId.CHEF) == 2, "precondition: 厨师应升到 2 级, 实为 "
                    + level(player, JobId.CHEF));
            for (String path : List.of("profession/level_2", "profession/level_4", "profession/level_7")) {
                assertDone(helper, player, path, true);
            }
            assertDone(helper, player, "profession/max_level", false);
            assertDone(helper, player, "profession/all_max", false);
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    /** 行业专家是任意职业满级; 全职精通要八个职业全部满级, 差最后一个就不成立。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void allMaxNeedsEveryJobAtLevelTen(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            for (JobId job : JobId.values()) {
                setLevel(player, job, job == JobId.BREWER ? 8 : 10);
            }
            // 8 级起 (39400) 当天 340000 原始: 前四段 3800 有效 + (340000-12000) x0.02 = 10360, 合计 49760, 9 级。
            grant(player, JobId.BREWER, 340_000L);
            helper.assertTrue(level(player, JobId.BREWER) == 9, "precondition: 酿酒师应升到 9 级, 实为 "
                    + level(player, JobId.BREWER));
            assertDone(helper, player, "profession/max_level", true);
            assertDone(helper, player, "profession/all_max", false);

            // 当天已过末档, 700000 原始全走 x0.02 = 14000 有效, 合计 63760 >= 61900, 满级。
            grant(player, JobId.BREWER, 700_000L);
            helper.assertTrue(level(player, JobId.BREWER) == 10, "precondition: 酿酒师应升到 10 级");
            assertDone(helper, player, "profession/all_max", true);
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    /** farmer_harvests 只认农夫的收获与采摘两个来源, 每笔加 1; 阈值成就钉在边界两侧。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmerHarvestStatCountsOnlyHarvestAndPickSources(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            ResourceLocation farmerTrack = JobExperienceTracks.track(JobId.FARMER);
            ResourceLocation harvest = AchievementIds.id("farmer/harvest");
            ResourceLocation pick = AchievementIds.id("farmer/pick");
            IExperienceService experience = ExperienceServices.experienceService();

            experience.award(player, new ExperienceGrant(farmerTrack, harvest, 2L));
            helper.assertTrue(harvests(player) == 1, "一次收获加 1, 实为 " + harvests(player));
            assertDone(helper, player, "profession/farmer_first_harvest", true);

            grant(player, JobId.FARMER, 2L);
            grant(player, JobId.CHEF, 2L);
            helper.assertTrue(harvests(player) == 1, "农夫的兼容来源与别的职业的经验都不算收获, 实为 " + harvests(player));
            experience.award(player, new ExperienceGrant(farmerTrack, pick, 2L));
            helper.assertTrue(harvests(player) == 2, "采摘同样加 1, 实为 " + harvests(player));

            setHarvests(player, 498);
            experience.award(player, new ExperienceGrant(farmerTrack, harvest, 2L));
            assertDone(helper, player, "profession/farmer_harvest_500", false);
            experience.award(player, new ExperienceGrant(farmerTrack, pick, 2L));
            helper.assertTrue(harvests(player) == 500, "应恰好累加到 500");
            assertDone(helper, player, "profession/farmer_harvest_500", true);

            setHarvests(player, 4_998);
            experience.award(player, new ExperienceGrant(farmerTrack, harvest, 2L));
            assertDone(helper, player, "profession/farmer_harvest_5000", false);
            experience.award(player, new ExperienceGrant(farmerTrack, harvest, 2L));
            assertDone(helper, player, "profession/farmer_harvest_5000", true);
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    // ---- 3. 各职业的监听接口: 驱动真实的生产代码 ----

    /** 调味台出菜之后广播一次: 成品已进背包、厨师经验已入账; 抛异常的成就监听器不打断结算。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chefSeamFiresOnceAfterTheDishIsServed(GameTestHelper helper) {
        BlockPos absolute = helper.absolutePos(STATION);
        BlockState primary = ChefBlocks.SEASONING_TABLE_RADIANT.get().defaultBlockState()
                .setValue(SeasoningTableBlock.FACING, Direction.NORTH)
                .setValue(SeasoningTableBlock.SECONDARY, false);
        helper.getLevel().setBlock(absolute, primary, Block.UPDATE_CLIENTS);
        primary.getBlock().setPlacedBy(helper.getLevel(), absolute, primary, null, ItemStack.EMPTY);
        SeasoningTableBlockEntity table = blockEntity(helper, absolute, SeasoningTableBlockEntity.class);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            player.getInventory().clearContent();
            player.setPos(absolute.getX() + 0.5D, absolute.getY() + 0.5D, absolute.getZ() + 0.5D);
            SeasoningMenu menu = new SeasoningMenu(1, player.getInventory(), table);
            player.containerMenu = menu;
            EconomyServices.economyService().grant(player, Currency.CREDIT, 1_000_000L);
            long xpBefore = xp(player, JobId.CHEF);

            List<Object[]> seen = new ArrayList<>();
            ChefEvents.addDishListener((chef, quality, target) -> {
                if (chef == player) {
                    failInGuard(player, "chef_dish");
                }
            });
            ChefEvents.addDishListener((chef, quality, target) -> {
                if (chef == player) {
                    seen.add(new Object[]{quality, target, xp(chef, JobId.CHEF), stampedDishes(chef)});
                }
            });

            table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_INPUT, new ItemStack(Items.BREAD));
            helper.assertTrue(table.startCooking(player, ChefQuality.LOW.tier()), "低品质挑战开工");
            driveHeat(helper, table, menu, player);
            int guard = 0;
            while (menu.phase() == SeasoningMenu.PHASE_SEASON && guard++ < 2_000) {
                if (menu.cueActive()) {
                    helper.assertTrue(table.hitSeason(player, menu.targetIndex()), "命中当前调味时机点");
                } else {
                    table.serverTick();
                }
            }
            helper.assertTrue(menu.phase() == SeasoningMenu.PHASE_DONE && stampedDishes(player) == 1,
                    "抛异常的成就监听器不得打断出菜: 结算完成、背包里有一份盖章菜");
            helper.assertTrue(seen.size() == 1, "一道菜应恰好广播一次, 实为 " + seen.size());
            Object[] dish = seen.get(0);
            helper.assertTrue(dish[0] == ChefQuality.LOW && dish[1] == ChefQuality.LOW,
                    "应带上成品品质与挑战目标 (都是低品质), 实为 " + dish[0] + " / " + dish[1]);
            helper.assertTrue((long) dish[2] > xpBefore && (int) dish[3] == 1,
                    "广播时厨师经验已入账、成品已进背包, 实为 经验 " + dish[2] + " 盖章菜 " + dish[3]);
            assertDone(helper, player, "profession/chef_first_dish", true);
            assertDone(helper, player, "profession/chef_radiant", false);

            // 闪耀成品在 1 级厨师手里只有千分之几十的成功率, 结果不确定, 这一条经广播方的 fire 方法走同一条接口路径。
            ChefEvents.fireDish(player, ChefQuality.RADIANT, ChefQuality.RADIANT);
            assertDone(helper, player, "profession/chef_radiant", true);
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    /** 酿酒台只在线操作者那一支广播, 且在酒进输出槽、经验入账之后; 操作者离线时照常出酒但不广播。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brewerSeamFiresOnceForTheOnlineOperator(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            helper.setBlock(STATION, BrewingStationRegistry.STATION_BLOCK.get());
            BrewingStationBlockEntity station = blockEntity(helper, helper.absolutePos(STATION),
                    BrewingStationBlockEntity.class);
            long xpBefore = xp(player, JobId.BREWER);
            List<Object[]> seen = new ArrayList<>();
            AtomicBoolean armed = new AtomicBoolean();
            AtomicInteger armedBrews = new AtomicInteger();
            BrewerEvents.addBrewListener((brewer, type, quality) -> {
                if (brewer == player) {
                    failInGuard(player, "brew_complete");
                }
            });
            BrewerEvents.addBrewListener((brewer, type, quality) -> {
                if (brewer == player) {
                    seen.add(new Object[]{type, xp(brewer, JobId.BREWER),
                            station.inventory().getStackInSlot(BrewingStationBlockEntity.OUTPUT_SLOT).getCount()});
                }
                if (armed.get()) {
                    armedBrews.incrementAndGet();
                }
            });

            station.setOperator(player.getUUID());
            brew(helper, station, WineType.VODKA);
            helper.assertTrue(seen.size() == 1, "一轮酿造应恰好广播一次, 实为 " + seen.size());
            Object[] brewed = seen.get(0);
            helper.assertTrue(brewed[0] == WineType.VODKA && (long) brewed[1] > xpBefore
                            && (int) brewed[2] == BrewerConstants.BREW_OUTPUT_COUNT,
                    "广播时应是伏特加、酿酒经验已入账、酒已进输出槽, 实为 " + brewed[0] + " / " + brewed[1] + " / "
                            + brewed[2]);
            assertDone(helper, player, "profession/brewer_first_brew", true);

            // 操作者离线 (不在玩家列表里): 酒照常产出, 但没有可归属的玩家, 不广播。
            BlockPos offlinePos = new BlockPos(3, 1, 1);
            helper.setBlock(offlinePos, BrewingStationRegistry.STATION_BLOCK.get());
            BrewingStationBlockEntity offline = blockEntity(helper, helper.absolutePos(offlinePos),
                    BrewingStationBlockEntity.class);
            offline.setOperator(UUID.randomUUID());
            armed.set(true);
            brew(helper, offline, WineType.WHISKEY);
            armed.set(false);
            helper.assertTrue(armedBrews.get() == 0, "离线操作者的酿造不应广播, 实为 " + armedBrews.get() + " 次");

            // 九种酒与闪耀品质都带随机性或要九套料, 经广播方的 fire 方法走同一条接口路径。
            WineType[] types = WineType.values();
            for (int i = 0; i < types.length - 1; i++) {
                BrewerEvents.fireBrew(player, types[i], WineQuality.LOW);
            }
            assertDone(helper, player, "profession/brewer_nine_wines", false);
            BrewerEvents.fireBrew(player, types[types.length - 1], WineQuality.LOW);
            assertDone(helper, player, "profession/brewer_nine_wines", true);
            assertDone(helper, player, "profession/brewer_brilliant", false);
            BrewerEvents.fireBrew(player, WineType.MAOTAI, WineQuality.BRILLIANT);
            assertDone(helper, player, "profession/brewer_brilliant", true);
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    /** 塔罗牌提交时不广播, 揭牌结算、出牌经验入账之后才广播一次。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void tarotSeamFiresAfterTheCardResolves(GameTestHelper helper) {
        helper.assertTrue(!TarotConfig.TEST_MODE.get(), "precondition: 测试模式必须关, 否则出牌不给经验也不广播");
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // 本用例要跨数十 tick 等揭牌, mock 玩家必须站在自己的结构区域里, 否则会被同批次别的用例清场一并移除。
        BlockPos own = helper.absolutePos(new BlockPos(1, 2, 1));
        player.setPos(own.getX() + 0.5D, own.getY(), own.getZ() + 0.5D);
        player.getInventory().clearContent();
        long xpBefore = xp(player, JobId.TAROT);
        List<Object[]> seen = new ArrayList<>();
        TarotEvents.addPlayListener((played, cardId, quality) -> {
            if (played == player) {
                failInGuard(player, "tarot_play");
            }
        });
        TarotEvents.addPlayListener((played, cardId, quality) -> {
            if (played == player) {
                seen.add(new Object[]{cardId, quality, xp(played, JobId.TAROT)});
            }
        });

        int fool = TarotArcana.FOOL.cardId(); // 正位 R 是自我治疗, 结算安全。
        ItemStack card = TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), fool, TarotQuality.R, true,
                player.getUUID());
        player.setItemInHand(InteractionHand.MAIN_HAND, card);
        helper.assertTrue(TarotPlayHandler.tryPlay(helper.getLevel(), player, card, InteractionHand.MAIN_HAND),
                "自己的一级 R 卡应能打出");
        helper.assertTrue(seen.isEmpty(), "提交时还没揭牌, 不应广播");

        helper.runAfterDelay(TarotCastTiming.EFFECT_RESOLVE_TICKS + 20, () -> {
            helper.assertTrue(seen.size() == 1, "揭牌后应恰好广播一次, 实为 " + seen.size());
            Object[] played = seen.get(0);
            helper.assertTrue((int) played[0] == fool && played[1] == TarotQuality.R && (long) played[2] > xpBefore,
                    "应带上愚者 R, 且广播时出牌经验已入账, 实为 " + played[0] + " / " + played[1] + " / " + played[2]);
            assertDone(helper, player, "profession/first_tarot", true);
            removePlayer(helper, player);
            helper.succeed();
        });
    }

    /** 封印申请成功时广播一次 (词条已移除、入职标志已置位); 被拒的申请不广播。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentSealSeamFiresOnlyOnSuccess(GameTestHelper helper) {
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            setLevel(agent, JobId.AGENT, 5);
            Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
            Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
            affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
            ChampionPromoter.applyChampion(champion, 5, affixes);
            List<Object[]> seen = new ArrayList<>();
            AgentEvents.addSealListener((sealer, star, affix, category) -> {
                if (sealer == agent) {
                    failInGuard(agent, "agent_seal");
                }
            });
            AgentEvents.addSealListener((sealer, star, affix, category) -> {
                if (sealer == agent) {
                    seen.add(new Object[]{star, affix, category,
                            MiningChampions.get(champion).orElseThrow().has(AffixDef.BURNING),
                            AgentBountySavedData.get(sealer.server.overworld()).isActiveAgent(sealer.getUUID())});
                }
            });

            AgentSealHandler.Result sealed = AgentSealHandler.requestSeal(agent, champion, "BURNING");
            helper.assertTrue(sealed.ok(), "抛异常的成就监听器不得打断封印: 5 级干员封 5 星被动词条应成功, 实为 "
                    + sealed.reason());
            helper.assertTrue(seen.size() == 1, "一次成功的封印应恰好广播一次, 实为 " + seen.size());
            Object[] event = seen.get(0);
            helper.assertTrue((int) event[0] == 5 && event[1] == AffixDef.BURNING && event[2] == SealCategory.PASSIVE,
                    "应带上 5 星、BURNING、被动类别, 实为 " + event[0] + " / " + event[1] + " / " + event[2]);
            helper.assertTrue(!(boolean) event[3] && (boolean) event[4], "广播时词条已被移除、入职标志已置位");
            assertDone(helper, agent, "profession/agent_first_seal", true);

            AgentSealHandler.Result again = AgentSealHandler.requestSeal(agent, champion, "BURNING");
            helper.assertTrue(!again.ok() && seen.size() == 1, "被拒的封印申请不广播");
        } finally {
            removePlayer(helper, agent);
        }
        helper.succeed();
    }

    /** 生产台出板之后广播一次: 板已放进输出槽; 抛异常的成就监听器不打断出板。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void engineerSeamFiresOnceWhenPlatesAreProduced(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            helper.setBlock(STATION, ModEngineerBlocks.table(NanoTier.LOW).get());
            ProductionTableBlockEntity table = blockEntity(helper, helper.absolutePos(STATION),
                    ProductionTableBlockEntity.class);
            table.setOwner(player.getUUID());
            table.inventory().setStackInSlot(ProductionTableBlockEntity.SLOT_INPUT,
                    new ItemStack(Items.IRON_INGOT, NanoTier.LOW.oreCost()));
            List<Object[]> seen = new ArrayList<>();
            EngineerEvents.addPlateListener((engineer, tier, plates) -> {
                if (engineer == player) {
                    failInGuard(player, "nano_plate_produced");
                }
            });
            EngineerEvents.addPlateListener((engineer, tier, plates) -> {
                if (engineer == player) {
                    seen.add(new Object[]{tier, plates,
                            table.inventory().getStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT).getCount()});
                }
            });

            helper.assertTrue(table.trySelectTier(NanoTier.LOW, player), "1 级铸甲师用铁锭选低级板");
            int guard = 0;
            while (table.inventory().getStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT).isEmpty()
                    && guard++ < 20_000) {
                table.serverTick();
                if (table.calibration().cursorInGreen()) {
                    table.onCalibrationClick(player);
                }
            }
            int produced = table.inventory().getStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT).getCount();
            helper.assertTrue(produced > 0, "抛异常的成就监听器不得打断出板: 输出槽应有板");
            helper.assertTrue(seen.size() == 1, "一轮生产应恰好广播一次, 实为 " + seen.size());
            Object[] event = seen.get(0);
            helper.assertTrue(event[0] == NanoTier.LOW && (int) event[1] == produced && (int) event[2] == produced,
                    "广播时板已进输出槽, 档位与板数一致, 实为 " + event[0] + " / " + event[1] + " / " + event[2]);
            assertDone(helper, player, "profession/first_nano_plate", true);
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    /** 军火台两个经验落账点 (手动批次完成、被动挂机结算) 各广播一次, 在入缓冲与经验入账之后。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void munitionsSeamFiresAtBothXpCreditPoints(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            helper.setBlock(STATION, ModMunitionsBlocks.MUNITIONS_BENCH.get());
            MunitionsBenchBlockEntity bench = blockEntity(helper, helper.absolutePos(STATION),
                    MunitionsBenchBlockEntity.class);
            bench.setOwner(player.getUUID());
            chargeBench(bench);
            EconomyServices.economyService().grant(player, Currency.CREDIT, 1_000_000L);
            List<Object[]> seen = new ArrayList<>();
            MunitionsEvents.addBatchListener((owner, caliber, rounds) -> {
                if (owner == player) {
                    failInGuard(player, "munitions_batch");
                }
            });
            MunitionsEvents.addBatchListener((owner, caliber, rounds) -> {
                if (owner == player) {
                    seen.add(new Object[]{caliber, rounds, bench.bufferedRounds(), xp(owner, JobId.MUNITIONS)});
                }
            });
            helper.assertTrue(bench.trySelectCaliber(MunitionsCaliber.PISTOL, player), "1 级军火商选手枪弹");

            // 手动批次: 开工后把开工时刻回拨到远古, 下一次结算即判本批到期。
            long xpBefore = xp(player, JobId.MUNITIONS);
            stockParts(bench);
            helper.assertTrue(bench.tryStartCraft(player), "台主开工");
            backdate(helper, bench, "CraftingStartTick");
            bench.settleForOwner(player);
            helper.assertTrue(seen.size() == 1, "手动批次完成应恰好广播一次, 实为 " + seen.size());
            Object[] manual = seen.get(0);
            int manualRounds = (int) manual[1];
            helper.assertTrue(manual[0] == MunitionsCaliber.PISTOL && manualRounds > 0
                            && (int) manual[2] == manualRounds && bench.bufferedRounds() == manualRounds
                            && (long) manual[3] > xpBefore,
                    "广播时本批已入缓冲、经验已入账, 实为 " + manual[0] + " / " + manual[1] + " / " + manual[2] + " / "
                            + manual[3]);
            assertDone(helper, player, "profession/first_ammo", true);

            // 被动挂机结算: 再备一批料, 把上次结算时刻回拨到远古, 一次追算补产。
            stockParts(bench);
            chargeBench(bench);
            backdate(helper, bench, "LastSettleTick");
            bench.settleForOwner(player);
            helper.assertTrue(seen.size() == 2, "被动结算落账应再广播一次, 实为 " + seen.size());
            Object[] passive = seen.get(1);
            helper.assertTrue((int) passive[1] > 0 && (int) passive[2] == manualRounds + (int) passive[1]
                            && bench.bufferedRounds() == (int) passive[2] && (long) passive[3] > (long) manual[3],
                    "被动结算广播时发数已入缓冲、经验已入账, 实为 " + passive[1] + " / " + passive[2] + " / " + passive[3]);
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    // ---- 工具 ----

    /** 在成就侧的保护层里抛异常, 模拟一个出错的成就监听器; 保护层应就地吞下并记日志。 */
    private static void failInGuard(ServerPlayer player, String seam) {
        JobHooks.guarded("test_" + seam, player, () -> {
            throw new SimulatedListenerFailure(seam);
        });
    }

    /** 除 job 为 value 外, 其余职业都是 others 级。 */
    private static Map<JobId, Integer> levels(int others, JobId job, int value) {
        Map<JobId, Integer> levels = new EnumMap<>(JobId.class);
        for (JobId each : JobId.values()) {
            levels.put(each, others);
        }
        levels.put(job, value);
        return levels;
    }

    /** 经全服经验路由, 以该职业的兼容来源发一笔原始经验。 */
    private static ExperienceAward grant(ServerPlayer player, JobId job, long rawXp) {
        return ExperienceServices.experienceService().award(player,
                new ExperienceGrant(JobExperienceTracks.track(job), JobExperienceTracks.legacySource(job), rawXp));
    }

    /** 静默改级 (与 /job set 相同: 直接写职业进度, 不经经验路由)。 */
    private static void setLevel(ServerPlayer player, JobId job, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(job).setLevel(level);
    }

    private static int level(ServerPlayer player, JobId job) {
        return ExperienceServices.experienceService().snapshot(player, JobExperienceTracks.track(job)).level();
    }

    private static long xp(ServerPlayer player, JobId job) {
        return ExperienceServices.experienceService().snapshot(player, JobExperienceTracks.track(job)).totalXp();
    }

    private static int harvests(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(JobStats.FARMER_HARVESTS.get()));
    }

    private static void setHarvests(ServerPlayer player, int value) {
        player.getStats().setValue(player, Stats.CUSTOM.get(JobStats.FARMER_HARVESTS.get()), value);
    }

    private static int stampedDishes(ServerPlayer player) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (ChefQualityNbt.hasQuality(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 完整的控火操作 (三按三放), 结束时进入调味阶段; 与厨师模块自己的调味台用例同一套节奏。 */
    private static void driveHeat(GameTestHelper helper, SeasoningTableBlockEntity table, SeasoningMenu menu,
                                  ServerPlayer player) {
        int[][] steps = {{1, 70}, {0, 20}, {1, 20}, {0, 20}, {1, 10}, {0, 20}};
        for (int[] step : steps) {
            boolean accepted = step[0] == 1 ? table.pressHeat(player) : table.releaseHeat(player);
            helper.assertTrue(accepted, "控火操作应被受理");
            for (int tick = 0; tick < step[1]; tick++) {
                table.serverTick();
            }
        }
        helper.assertTrue(menu.phase() == SeasoningMenu.PHASE_SEASON, "控火结束应进入调味阶段, 实为 " + menu.phase());
    }

    /** 按配方铺满投料槽, 一直推进到酒进输出槽。 */
    private static void brew(GameTestHelper helper, BrewingStationBlockEntity station, WineType type) {
        int slot = 0;
        for (BrewRecipes.Ingredient ingredient : BrewRecipes.recipeFor(type)) {
            station.inventory().setStackInSlot(slot++, new ItemStack(ingredient.item(), ingredient.count()));
        }
        int guard = 0;
        while (station.inventory().getStackInSlot(BrewingStationBlockEntity.OUTPUT_SLOT).isEmpty()
                && guard++ <= BrewerConstants.BREW_DURATION_TICKS) {
            station.serverTick();
        }
        ItemStack output = station.inventory().getStackInSlot(BrewingStationBlockEntity.OUTPUT_SLOT);
        helper.assertTrue(!output.isEmpty() && type.itemRegistryName().equals(
                        ForgeRegistries.ITEMS.getKey(output.getItem()).getPath()),
                "酿满 " + BrewerConstants.BREW_DURATION_TICKS + " tick 后输出槽应是 " + type.itemRegistryName()
                        + ", 实为 " + output);
    }

    /** 四件套各备一批的量。 */
    private static void stockParts(MunitionsBenchBlockEntity bench) {
        bench.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER,
                new ItemStack(ModMunitionsItems.PRIMER.get(), MunitionsConfig.RECIPE_PRIMER_COST.get()));
        bench.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_CASING,
                new ItemStack(ModMunitionsItems.CASING.get(), MunitionsConfig.RECIPE_CASING_COST.get()));
        bench.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_BULLET_HEAD,
                new ItemStack(ModMunitionsItems.BULLET_HEAD.get(), MunitionsConfig.RECIPE_BULLET_HEAD_COST.get()));
        bench.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PROPELLANT,
                new ItemStack(ModMunitionsItems.PROPELLANT.get(), MunitionsConfig.RECIPE_PROPELLANT_COST.get()));
    }

    private static void chargeBench(MunitionsBenchBlockEntity bench) {
        bench.getCapability(ForgeCapabilities.ENERGY).ifPresent(storage -> storage.receiveEnergy(Integer.MAX_VALUE,
                false));
    }

    /**
     * 经 NBT 往返把军火台的一个时间戳回拨到远古 (与军火商模块自己的用例同一手段: 单测里拨不动世界时钟), 并标记首帧已锚定,
     * 使下一次结算按回拨后的流逝量判定。
     */
    private static void backdate(GameTestHelper helper, MunitionsBenchBlockEntity bench, String tickKey) {
        CompoundTag tag = bench.saveWithoutMetadata();
        tag.putLong(tickKey, helper.getLevel().getGameTime() - 10_000_000L);
        tag.putBoolean("SettleInitialized", true);
        bench.load(tag);
    }

    private static <T extends BlockEntity> T blockEntity(GameTestHelper helper, BlockPos absolute, Class<T> type) {
        BlockEntity raw = helper.getLevel().getBlockEntity(absolute);
        if (!type.isInstance(raw)) {
            throw new IllegalStateException(type.getSimpleName() + " not present at " + absolute + ", got " + raw);
        }
        return type.cast(raw);
    }

    private static void assertDone(GameTestHelper helper, ServerPlayer player, String path, boolean done) {
        Advancement advancement = player.getServer().getAdvancements().getAdvancement(AchievementIds.id(path));
        helper.assertTrue(advancement != null, "进度未加载: " + path);
        boolean actual = player.getAdvancements().getOrStartProgress(advancement).isDone();
        helper.assertTrue(actual == done, path + (done ? " 此时应已获得" : " 此时不应获得"));
    }

    private static void removePlayer(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().remove(player);
    }

    private static DeserializationContext context(GameTestHelper helper) {
        return new DeserializationContext(AchievementIds.id("test/job_trigger_round_trip"),
                helper.getLevel().getServer().getLootData());
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

    private static JsonObject readLang(String language) throws IOException {
        String path = "assets/" + MiningConstants.MODID + "/lang/" + language + ".json";
        InputStream stream = JobAchievementGameTests.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("运行时 classpath 找不到资源: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return GsonHelper.parse(reader);
        }
    }

    /** 模拟成就监听器出错; 专用类型, 不会误吞断言失败。 */
    private static final class SimulatedListenerFailure extends RuntimeException {
        SimulatedListenerFailure(String seam) {
            super("simulated " + seam + " listener failure");
        }
    }
}
