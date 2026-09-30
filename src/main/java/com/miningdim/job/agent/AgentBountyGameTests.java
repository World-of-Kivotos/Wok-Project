package com.miningdim.job.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.champion.AffixPool;
import com.miningdim.champion.StarRank;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 特勤悬赏系统 GameTest (SpecialAgent_Job_DesignSpec 7.2 / 10.5 / 12.1, 2026-09-30 拍板的四项前提)。
 *
 * 分三层:
 *  1. 纯规则: 奖励表落在拍板区间、掷取的星级窗口与词条类合法性、悬赏板的翻期/补掷/接取/推进、NBT 往返;
 *  2. 服务编排 ({@link AgentBountyService}): 接取即入职、完成即发奖 (信用点/经验/青辉石)、日上限截断转待补发、
 *     世界 BOSS 讨伐令的等级与入职门、没碰过悬赏的玩家不建板;
 *  3. WebUI: {@code job.agent.bounty.accept} 的入参校验与回执。
 * 经真实精英死亡事件推进悬赏的端到端用例在 integration 包 ({@code AgentBountyIntegrationGameTests})。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AgentBountyGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "agent_bounty";

    private static final long DAY = 20_000L;
    private static final long WEEK = AgentClock.isoWeekStampOf(DAY);

    // ============================================================
    // 1. 纯规则
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rewardTableDefaultsSpanTheDecidedConservativeBand(GameTestHelper helper) {
        BountyRewardTable t = BountyRewardTable.DEFAULTS;
        helper.assertTrue(t.dailyCredit(1) == 2_000L && t.dailyCredit(9) == 6_000L,
                "日常信用点区间 2,000~6,000 (保守档), 实得 " + t.dailyCredit(1) + "~" + t.dailyCredit(9));
        helper.assertTrue(t.weeklyCredit(1) == 15_000L && t.weeklyCredit(9) == 30_000L,
                "周常信用点区间 15,000~30,000, 实得 " + t.weeklyCredit(1) + "~" + t.weeklyCredit(9));
        helper.assertTrue(t.weeklyAzure(1) == 8L && t.weeklyAzure(9) == 15L,
                "周常青辉石区间 8~15, 实得 " + t.weeklyAzure(1) + "~" + t.weeklyAzure(9));
        helper.assertTrue(t.dailyXp(1) == 400L && t.dailyXp(9) == 1_500L
                        && t.weeklyXp(1) == 2_500L && t.weeklyXp(9) == 6_000L,
                "XP 沿用 8.1: 日常 400~1,500 / 周常 2,500~6,000");
        helper.assertTrue(t.dailyCredit(10) == t.dailyCredit(9) && t.weeklyAzure(10) == t.weeklyAzure(9),
                "10★ 只出自世界 BOSS, 日常/周常插值在 9★ 封顶");
        for (int star = 2; star <= 9; star++) {
            helper.assertTrue(t.dailyCredit(star) >= t.dailyCredit(star - 1)
                            && t.weeklyCredit(star) >= t.weeklyCredit(star - 1),
                    "奖励随目标星级单调不减, star=" + star);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void generatorKeepsTargetsInsideTheLevelWindow(GameTestHelper helper) {
        RandomSource rng = RandomSource.create(42L);
        for (int level = 1; level <= 10; level++) {
            for (BountyDefinition.Period period : List.of(BountyDefinition.Period.DAILY,
                    BountyDefinition.Period.WEEKLY)) {
                List<BountyDefinition> drawn = BountyGenerator.draw(period, level, 40, "t" + level, 0, List.of(),
                        BountyRewardTable.DEFAULTS, rng);
                Set<String> ids = new HashSet<>();
                for (BountyDefinition def : drawn) {
                    helper.assertTrue(ids.add(def.id()), "同一批 id 不得重复: " + def.id());
                    helper.assertTrue(def.minStar() >= BountyGenerator.bottomStar(period, level)
                                    && def.minStar() <= BountyGenerator.topStar(level),
                            "L" + level + " " + period + " 目标星级越出窗口: " + def.minStar());
                    helper.assertTrue(def.minStar() <= AgentSkillTable.maxBountyStar(level),
                            "目标星级不得高于可接上限 (第四章: 可接 ≤L★)");
                    helper.assertTrue(def.minStar() <= BountyRewardTable.TOP_STAR,
                            "日常/周常不得掷 10★ (只出自世界 BOSS)");
                    helper.assertTrue(period == BountyDefinition.Period.WEEKLY || def.azureReward() == 0L,
                            "日常不发青辉石");
                    helper.assertTrue(def.targetType() != BountyDefinition.TargetType.KILL_WORLD_BOSS,
                            "世界 BOSS 不进日常/周常槽");
                    if (def.targetType() == BountyDefinition.TargetType.KILL_WITH_AFFIX_CATEGORY) {
                        helper.assertTrue(BountyGenerator.poolPresenceRate(def.targetPool(), def.minStar())
                                        >= BountyGenerator.MIN_POOL_PRESENCE,
                                "词条类只掷出现率达标的组合, 实得 " + def.targetPool() + "@" + def.minStar() + "★");
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void generatorProducesBothStarAndAffixBounties(GameTestHelper helper) {
        RandomSource rng = RandomSource.create(7L);
        List<BountyDefinition> drawn = BountyGenerator.draw(BountyDefinition.Period.DAILY, 7, 200, "mix", 0,
                List.of(), BountyRewardTable.DEFAULTS, rng);
        long affix = drawn.stream()
                .filter(d -> d.targetType() == BountyDefinition.TargetType.KILL_WITH_AFFIX_CATEGORY).count();
        helper.assertTrue(affix > 0 && affix < drawn.size(),
                "L7 的日常里星级类与词条类都应出现, 实得词条类 " + affix + "/" + drawn.size());
        helper.assertTrue(!BountyGenerator.eligiblePools(StarRank.MAX_STAR - 1).isEmpty(),
                "9★ 至少有一个池出现率达标, 否则词条类悬赏在高星永远掷不出");
        for (int star = StarRank.MIN_STAR; star <= StarRank.MAX_STAR; star++) {
            for (AffixPool pool : AffixPool.values()) {
                double rate = BountyGenerator.poolPresenceRate(pool, star);
                helper.assertTrue(rate >= 0.0D && rate <= 1.0D, "出现率是概率: " + pool + "@" + star + "=" + rate);
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void affixAndWorldBossTargetsMatchOnlyTheirKills(GameTestHelper helper) {
        BountyDefinition affix = new BountyDefinition("a", BountyDefinition.Period.DAILY,
                BountyDefinition.TargetType.KILL_WITH_AFFIX_CATEGORY, 4, AffixPool.MOBILITY, 1, 100L, 10L, 0L);
        helper.assertTrue(affix.countsToward(new BountyKill(5, Set.of(AffixPool.MOBILITY, AffixPool.SURVIVAL),
                false, true)), "带机动池词条的 5★ 计入 ≥4★ 机动类悬赏");
        helper.assertTrue(!affix.countsToward(new BountyKill(5, Set.of(AffixPool.SURVIVAL), false, true)),
                "不带机动池词条不计");
        helper.assertTrue(!affix.countsToward(new BountyKill(3, Set.of(AffixPool.MOBILITY), false, true)),
                "星级不够不计");
        helper.assertTrue(!affix.countsToward(new BountyKill(5, Set.of(AffixPool.MOBILITY), false, false)),
                "未达入池门槛不计");

        BountyDefinition order = BountyGenerator.worldBossOrder(BountyRewardTable.DEFAULTS);
        helper.assertTrue(order.countsToward(new BountyKill(9, Set.of(), true, true)), "世界 BOSS 计入讨伐令");
        helper.assertTrue(!order.countsToward(new BountyKill(9, Set.of(), false, true)),
                "自然刷出的 9★ 不是世界 BOSS");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boardRollsOverAndTopsUpWithoutRerolling(GameTestHelper helper) {
        BountyBoard board = new BountyBoard();
        RandomSource rng = RandomSource.create(11L);
        helper.assertTrue(board.refresh(DAY, WEEK, 1, BountyRewardTable.DEFAULTS, rng), "首次取板要掷悬赏");
        helper.assertTrue(board.daily().size() == 1 + BountyGenerator.DAILY_EXTRA_OFFERS && board.weekly().isEmpty(),
                "L1: 日常 1 槽 + 余量, 周常未解锁");
        helper.assertTrue(!board.refresh(DAY, WEEK, 1, BountyRewardTable.DEFAULTS, rng), "同期同级再取不变");

        List<String> before = board.daily().stream().map(p -> p.definition().id()).toList();
        board.refresh(DAY, WEEK, 4, BountyRewardTable.DEFAULTS, rng);
        helper.assertTrue(board.daily().size() == 2 + BountyGenerator.DAILY_EXTRA_OFFERS,
                "升到 L4 当天补掷到 2 槽 + 余量, 实得 " + board.daily().size());
        helper.assertTrue(board.daily().stream().map(p -> p.definition().id()).toList().subList(0, before.size())
                        .equals(before), "补掷只追加, 原有悬赏原样保留 (升级不等于免费重摇)");
        helper.assertTrue(board.weekly().size() == 1 + BountyGenerator.WEEKLY_EXTRA_OFFERS, "L4 周常解锁即掷");

        List<String> weeklyIds = board.weekly().stream().map(p -> p.definition().id()).toList();
        board.refresh(DAY + 1L, WEEK, 4, BountyRewardTable.DEFAULTS, rng);
        helper.assertTrue(board.daily().stream().noneMatch(p -> before.contains(p.definition().id())),
                "翻日整张日常重掷");
        helper.assertTrue(board.weekly().stream().map(p -> p.definition().id()).toList().equals(weeklyIds),
                "同一 ISO 周内翻日不动周常");
        board.refresh(DAY + 1L, WEEK + 1L, 4, BountyRewardTable.DEFAULTS, rng);
        helper.assertTrue(board.weekly().stream().noneMatch(p -> weeklyIds.contains(p.definition().id())),
                "翻周整张周常重掷");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boardAcceptEnforcesSlotsLevelAndStarGates(GameTestHelper helper) {
        RandomSource rng = RandomSource.create(5L);
        BountyBoard board = new BountyBoard();
        board.refresh(DAY, WEEK, 1, BountyRewardTable.DEFAULTS, rng);
        String first = board.daily().get(0).definition().id();
        String second = board.daily().get(1).definition().id();
        helper.assertTrue(board.accept(BountyDefinition.Period.DAILY, first, 1) == BountyBoard.AcceptOutcome.OK,
                "L1 可接一张日常");
        helper.assertTrue(board.accept(BountyDefinition.Period.DAILY, first, 1)
                == BountyBoard.AcceptOutcome.ALREADY_ACCEPTED, "同一张不能接两次");
        helper.assertTrue(board.accept(BountyDefinition.Period.DAILY, second, 1) == BountyBoard.AcceptOutcome.NO_SLOT,
                "L1 日常槽只有 1 个");
        helper.assertTrue(board.accept(BountyDefinition.Period.DAILY, "nope", 1) == BountyBoard.AcceptOutcome.NOT_FOUND,
                "不在本期板上的 id 拒绝");

        BountyBoard l4 = new BountyBoard();
        l4.refresh(DAY, WEEK, 4, BountyRewardTable.DEFAULTS, rng);
        String weekly = l4.weekly().get(0).definition().id();
        helper.assertTrue(l4.accept(BountyDefinition.Period.WEEKLY, weekly, 3) == BountyBoard.AcceptOutcome.LOCKED,
                "等级被调回 L3 后周常锁定");
        helper.assertTrue(l4.accept(BountyDefinition.Period.WEEKLY, weekly, 4) == BountyBoard.AcceptOutcome.OK,
                "L4 可接周常");

        BountyBoard l9 = new BountyBoard();
        l9.refresh(DAY, WEEK, 9, BountyRewardTable.DEFAULTS, rng);
        String high = l9.daily().get(0).definition().id();
        helper.assertTrue(l9.accept(BountyDefinition.Period.DAILY, high, 1) == BountyBoard.AcceptOutcome.STAR_TOO_HIGH,
                "等级被调低后, 高于可接上限的悬赏接不了");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boardRecordsKillsAndReleasesEachCompletionOnce(GameTestHelper helper) {
        BountyBoard board = new BountyBoard();
        board.refresh(DAY, WEEK, 4, BountyRewardTable.DEFAULTS, RandomSource.create(9L));
        BountyProgress daily = board.daily().get(0);
        BountyProgress idle = board.daily().get(1);
        board.accept(BountyDefinition.Period.DAILY, daily.definition().id(), 4);
        BountyKill kill = matchingKill(daily.definition());

        helper.assertTrue(board.wouldAdvance(kill), "悬赏雷达: 已接未完成的悬赏认得出目标");
        List<BountyDefinition> completed = List.of();
        for (int i = 0; i < daily.definition().requiredCount(); i++) {
            completed = board.recordKill(kill);
        }
        helper.assertTrue(completed.size() == 1 && completed.get(0).id().equals(daily.definition().id()),
                "最后一只击杀恰好放出这一张的完成, 实得 " + completed);
        helper.assertTrue(daily.claimed(), "完成即标记已领取 (自动发奖, 无领取按钮)");
        helper.assertTrue(board.recordKill(kill).isEmpty(), "同一张不会再放出第二次");
        helper.assertTrue(idle.killCount() == 0, "未接的悬赏不吃进度");
        helper.assertTrue(!board.wouldAdvance(kill), "完成后雷达不再为它亮 (其余悬赏都没接)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boardAndSavedDataSurviveNbtRoundTrip(GameTestHelper helper) {
        BountyBoard board = new BountyBoard();
        board.refresh(DAY, WEEK, 7, BountyRewardTable.DEFAULTS, RandomSource.create(3L));
        BountyProgress first = board.daily().get(0);
        board.accept(BountyDefinition.Period.DAILY, first.definition().id(), 7);
        board.recordKill(matchingKill(first.definition()));
        board.addPendingAzure(12L);
        board.recordWorldBossOrder();

        CompoundTag tag = board.toTag();
        // 混进一条读不回来的坏条目: 只丢这一张, 不拖垮整块板。
        CompoundTag bad = new CompoundTag();
        bad.put("def", new CompoundTag());
        tag.getList("daily", Tag.TAG_COMPOUND).add(bad);

        BountyBoard back = BountyBoard.fromTag(tag);
        helper.assertTrue(back.daily().size() == board.daily().size() && back.weekly().size() == board.weekly().size(),
                "张数往返一致 (坏条目被丢弃)");
        BountyProgress firstBack = back.daily().get(0);
        helper.assertTrue(firstBack.accepted() && firstBack.killCount() == first.killCount()
                        && firstBack.definition().id().equals(first.definition().id())
                        && firstBack.definition().targetPool() == first.definition().targetPool(),
                "接取态、进度与定义往返一致");
        helper.assertTrue(back.pendingAzure() == 12L && back.worldBossOrders() == 1
                        && back.dayStamp() == DAY && back.weekStamp() == WEEK,
                "待补发青辉石、讨伐令计数与周期戳往返一致");

        AgentBountySavedData data = new AgentBountySavedData();
        java.util.UUID id = java.util.UUID.randomUUID();
        data.board(id).refresh(DAY, WEEK, 2, BountyRewardTable.DEFAULTS, RandomSource.create(1L));
        AgentBountySavedData reloaded = AgentBountySavedData.load(data.save(new CompoundTag()));
        helper.assertTrue(reloaded.existingBoard(id) != null
                        && reloaded.existingBoard(id).daily().size() == data.existingBoard(id).daily().size(),
                "悬赏板随 SavedData 落盘");
        helper.assertTrue(reloaded.existingBoard(java.util.UUID.randomUUID()) == null, "没建过板的玩家读回来仍没有板");
        helper.succeed();
    }

    // ============================================================
    // 2. 服务编排
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void acceptingFirstBountyEnrollsTheAgent(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        AgentBountySavedData data = AgentBountySavedData.get(player.server.overworld());
        helper.assertTrue(!data.isActiveAgent(player.getUUID()), "前提: 新号未入职");

        BountyBoard board = AgentBountyService.board(player);
        AgentBountyService.AcceptResult first = AgentBountyService.accept(player, BountyDefinition.Period.DAILY,
                board.daily().get(0).definition().id());
        helper.assertTrue(first.outcome() == BountyBoard.AcceptOutcome.OK && first.newlyActiveAgent(),
                "L1 接第一张悬赏即入职 (7.0, 2026-09-30 拍板), 实得 " + first);
        helper.assertTrue(data.isActiveAgent(player.getUUID()), "入职标志已落 SavedData");

        AgentBountyService.AcceptResult second = AgentBountyService.accept(player, BountyDefinition.Period.DAILY,
                board.daily().get(1).definition().id());
        helper.assertTrue(second.outcome() == BountyBoard.AcceptOutcome.NO_SLOT && !second.newlyActiveAgent(),
                "槽满拒绝, 且不再报首次入职");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void completingDailyBountyPaysCreditAndXpOnce(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BountyBoard board = AgentBountyService.board(player);
        BountyDefinition def = board.daily().get(0).definition();
        AgentBountyService.accept(player, BountyDefinition.Period.DAILY, def.id());

        long creditBefore = EconomyServices.economyService().creditBalance(player);
        long xpBefore = JobServices.jobService().totalXp(player, JobId.AGENT);
        for (int i = 0; i < def.requiredCount(); i++) {
            AgentBountyService.onQualifiedKill(player, matchingKill(def));
        }
        // 新号当日毛收入 0, 奖励远低于 60,000 一档主闸 -> 衰减系数 1.0; 经验远低于每日 2,000 全额段。
        helper.assertTrue(EconomyServices.economyService().creditBalance(player) - creditBefore == def.creditReward(),
                "完成即发信用点, 期望 " + def.creditReward() + " 实得 "
                        + (EconomyServices.economyService().creditBalance(player) - creditBefore));
        helper.assertTrue(JobServices.jobService().totalXp(player, JobId.AGENT) - xpBefore == def.xpReward(),
                "完成即发干员经验, 期望 " + def.xpReward());

        long creditAfter = EconomyServices.economyService().creditBalance(player);
        AgentBountyService.onQualifiedKill(player, matchingKill(def));
        helper.assertTrue(EconomyServices.economyService().creditBalance(player) == creditAfter, "同一张不会发第二次");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void weeklyAzureIsGatedAndCarriedOverWhenDailyCapIsFull(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(player, 4);
        BountyBoard board = AgentBountyService.board(player);
        BountyDefinition def = board.weekly().get(0).definition();
        helper.assertTrue(def.azureReward() >= 8L, "前提: 周常带青辉石, 实得 " + def.azureReward());
        AgentBountyService.accept(player, BountyDefinition.Period.WEEKLY, def.id());

        // 先把每人每日青辉石硬上限 (与精英掉落共享) 用满, 模拟"当天刷了一堆高星掉落"。
        EconomyServices.economyService().grantAzureDaily(player, EconomyConstants.AZURE_DAILY_FAUCET_CAP,
                EconomyConstants.AZURE_DAILY_FAUCET_CAP);
        long azureBefore = EconomyServices.economyService().heartstoneBalance(player);
        for (int i = 0; i < def.requiredCount(); i++) {
            AgentBountyService.onQualifiedKill(player, matchingKill(def));
        }
        AgentBountySavedData data = AgentBountySavedData.get(player.server.overworld());
        helper.assertTrue(EconomyServices.economyService().heartstoneBalance(player) == azureBefore,
                "日上限已满: 当场一颗都进不了账");
        helper.assertTrue(data.existingBoard(player.getUUID()).pendingAzure() == def.azureReward(),
                "被日上限截掉的青辉石记为待补发, 不被吞掉, 实得 "
                        + data.existingBoard(player.getUUID()).pendingAzure());
        helper.assertTrue(data.weeklyAzureGranted(player.getUUID(), AgentClock.currentUtcWeekStamp())
                        == def.azureReward(), "周软上限按挣到的量记账 (补发不重复记)");

        AgentBountyService.flushPendingAzure(player);
        helper.assertTrue(data.existingBoard(player.getUUID()).pendingAzure() == def.azureReward(),
                "同一天再补发仍被日上限挡住, 待补发量不变");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void worldBossOrderNeedsLevelEightAndEnrollment(GameTestHelper helper) {
        ServerPlayer rookie = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(rookie, 7);
        AgentBountySavedData data = AgentBountySavedData.get(rookie.server.overworld());
        data.markActiveAgent(rookie.getUUID());
        helper.assertTrue(!AgentBountyService.onWorldBossKill(rookie), "L7 没有世界 BOSS 讨伐令");

        ServerPlayer outsider = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(outsider, 8);
        helper.assertTrue(!AgentBountyService.onWorldBossKill(outsider), "L8 但从未入职: 不发 (特勤专属奖励)");

        ServerPlayer veteran = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(veteran, 8);
        data.markActiveAgent(veteran.getUUID());
        long creditBefore = EconomyServices.economyService().creditBalance(veteran);
        long azureBefore = EconomyServices.economyService().heartstoneBalance(veteran);
        helper.assertTrue(AgentBountyService.onWorldBossKill(veteran), "L8 已入职干员结算讨伐令");
        BountyRewardTable t = BountyRewardTable.DEFAULTS;
        helper.assertTrue(EconomyServices.economyService().creditBalance(veteran) - creditBefore == t.worldBossCredit(),
                "讨伐令信用点 " + t.worldBossCredit());
        helper.assertTrue(EconomyServices.economyService().heartstoneBalance(veteran) - azureBefore
                == t.worldBossAzure(), "讨伐令青辉石 " + t.worldBossAzure());
        helper.assertTrue(data.existingBoard(veteran.getUUID()).worldBossOrders() == 1, "本周讨伐令计数 +1");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void killsDoNotCreateBoardsForBystanders(GameTestHelper helper) {
        ServerPlayer bystander = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        AgentBountyService.onQualifiedKill(bystander, new BountyKill(5, Set.of(AffixPool.COMBAT), false, true));
        helper.assertTrue(AgentBountySavedData.get(bystander.server.overworld()).existingBoard(bystander.getUUID())
                == null, "没打开过悬赏板的玩家打精英不建板");
        helper.succeed();
    }

    // ============================================================
    // 3. WebUI: job.agent.bounty.accept
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void acceptActionValidatesPayloadAndReturnsFreshBoard(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject bounty = AgentBountyWebUi.bountyJson(player);
        JsonArray daily = bounty.getAsJsonArray("daily");
        String id = daily.get(0).getAsJsonObject().get("bountyId").getAsString();

        JsonObject ok = accept(player, "DAILY", id);
        helper.assertTrue(ok.get("ok").getAsBoolean() && "OK".equals(ok.get("outcomeCode").getAsString()),
                "接取成功, 实得 " + ok);
        helper.assertTrue(ok.get("newlyActiveAgent").getAsBoolean() && ok.get("activeAgent").getAsBoolean(),
                "回执带出首次入职");
        JsonObject fresh = ok.getAsJsonObject("bounty");
        helper.assertTrue(fresh.get("dailyAccepted").getAsInt() == 1
                        && fresh.getAsJsonArray("daily").get(0).getAsJsonObject().get("accepted").getAsBoolean(),
                "回执带最新整段悬赏投影");

        JsonObject again = accept(player, "DAILY", daily.get(1).getAsJsonObject().get("bountyId").getAsString());
        helper.assertTrue(!again.get("ok").getAsBoolean() && "NO_SLOT".equals(again.get("outcomeCode").getAsString()),
                "业务拒绝走结果码而不是异常, 实得 " + again);

        helper.assertTrue(rejectsField(player, "EVENT", id, "period"), "EVENT 讨伐令不接受客户端接取");
        helper.assertTrue(rejectsField(player, "DAILY", "  ", "bountyId"), "空白 id 是入参非法");

        JsonObject entry = fresh.getAsJsonArray("daily").get(0).getAsJsonObject();
        helper.assertTrue(entry.has("targetPool") && entry.has("requiredCount") && entry.has("creditReward")
                        && entry.has("azureReward") && entry.has("killCount"),
                "悬赏条目字段齐全 (targetPool 非词条类是 JSON null 而不是缺键)");
        helper.assertTrue(fresh.get("dailyResetRemainingSeconds").getAsLong() > 0L
                        && fresh.get("dailyResetRemainingSeconds").getAsLong() <= 86_400L,
                "日常翻期倒计时落在一天之内");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 恰好满足某张悬赏的一次合格击杀。 */
    static BountyKill matchingKill(BountyDefinition def) {
        Set<AffixPool> pools = def.targetPool() == null ? Set.of() : Set.of(def.targetPool());
        return new BountyKill(def.minStar(), pools,
                def.targetType() == BountyDefinition.TargetType.KILL_WORLD_BOSS, true);
    }

    private static JsonObject accept(ServerPlayer player, String period, String id) {
        JsonObject payload = new JsonObject();
        payload.addProperty("period", period);
        payload.addProperty("bountyId", id);
        return JsonParser.parseString(AgentBountyWebUi.ACCEPT.handle(player, payload)).getAsJsonObject();
    }

    private static boolean rejectsField(ServerPlayer player, String period, String id, String field) {
        try {
            accept(player, period, id);
            return false;
        } catch (WebUiBusinessException rejected) {
            return WebUiErrorCodes.INVALID_REQUEST.equals(rejected.errorCode())
                    && field.equals(rejected.params().get("field"));
        }
    }

    private static void setAgentLevel(ServerPlayer player, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.AGENT).setLevel(level);
    }
}
