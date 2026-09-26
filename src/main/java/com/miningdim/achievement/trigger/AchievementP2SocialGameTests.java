package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.miningdim.achievement.AchievementConfig;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.achievement.AchievementServices;
import com.miningdim.achievement.meta.AchievementCatalog;
import com.miningdim.achievement.meta.AchievementConsistency;
import com.miningdim.achievement.meta.AchievementMeta;
import com.miningdim.achievement.meta.ConsistencyReport;
import com.miningdim.achievement.reward.AchievementReward;
import com.miningdim.caseopening.CaseCatalog;
import com.miningdim.caseopening.CaseEconomyOperations;
import com.miningdim.caseopening.CaseOpeningService;
import com.miningdim.caseopening.CaseRarity;
import com.miningdim.caseopening.CaseRoller;
import com.miningdim.caseopening.CaseServices;
import com.miningdim.caseopening.CaseSkin;
import com.miningdim.caseopening.CaseWeights;
import com.miningdim.caseopening.EconomyCaseOperations;
import com.miningdim.caseopening.SettledOpening;
import com.miningdim.caseopening.store.CaseDaoSqlite;
import com.miningdim.caseopening.store.CaseDb;
import com.miningdim.caseopening.store.CaseOpeningRow;
import com.miningdim.caseopening.store.CaseOpeningStatus;
import com.miningdim.caseopening.store.SkinAssetRow;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyOperationDomain;
import com.miningdim.economy.EconomyOperationStatus;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.market.MarketEngine;
import com.miningdim.market.MarketServices;
import com.miningdim.market.MarketTrade;
import com.miningdim.market.store.MarketDaoSqlite;
import com.miningdim.market.store.MarketDb;
import com.miningdim.marriage.MarriageEngine;
import com.miningdim.marriage.MarriageEvents;
import com.miningdim.marriage.MarriageRegistry;
import com.miningdim.marriage.MarriageTeleport;
import com.miningdim.marriage.RingItem;
import com.miningdim.quest.QuestBoard;
import com.miningdim.quest.QuestChain;
import com.miningdim.quest.QuestClaim;
import com.miningdim.quest.QuestDefinition;
import com.miningdim.quest.QuestFacts;
import com.miningdim.quest.QuestPool;
import com.miningdim.quest.QuestService;
import com.miningdim.quest.QuestServices;
import com.miningdim.quest.QuestSource;
import com.miningdim.registry.ModItems;
import com.miningdim.store.MiningDb;
import com.miningdim.store.StoreTx;
import com.miningdim.testutil.MockGameTestPlayers;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.FrameType;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 成就系统 P2 经济与社交部分的 GameTest (Achievement_System_DesignSpec 6.1、6.2、6.6、9.4、9.5、9.6、9.9、9.10,
 * batch {@code achievement_p2_social})。
 *
 * <p>生产方的监听接口一律走真实调用链: 真实的经济服务与账本 (独立内存库)、真实的市场引擎与开箱服务 (建在同一条内存连接上,
 * 与生产环境"钱与资产同库同事务"一致)、真实的任务服务与婚姻引擎; 成就侧的监听器是 mod 构造期注册的那一批, 探针
 * ({@link Probe}) 另外挂在同一批接口上, 只在用例自己的同步代码里布防。期望值按设计文档独立写在测试里 (第九章的表、6.6
 * 的数值), 不从声明表反推。依赖 TaCZ 的成就在开发环境里不加载, 它们的条件改用挂在同一触发器上的临时进度核对, 走的仍是
 * 真实的监听器与触发器。强断言, 每条都钉在判据两侧:
 * <ol>
 *   <li>20 条 P2 成就按规格加载 (父节点、框体、公告、隐藏、图标、条件、称号、点数、TaCZ 加载条件、两种语言的文案);</li>
 *   <li>四个新触发器的 JSON 往返、写错的条件整条拒收、边界两侧的判定;</li>
 *   <li>提交后队列: 嵌套事务里登记的动作等最外层提交后才执行, 回滚即丢弃, 动作抛出不影响提交;</li>
 *   <li>六个监听接口各自在提交后恰好通知一次, 回滚或失败不通知, 监听器抛出不影响生产方;</li>
 *   <li>市场防刷: 低于 1,000、同 IP、夫妻之间都不计; 计数成交额按买家收入与 1,000,000 封顶; 离线卖家登录补查;</li>
 *   <li>开箱品质与"倾家荡产"只认正常开箱; 任务每日上限与全勤; 神射手; 千里赴约; 成就数量 25 / 40;</li>
 *   <li>上线追溯静默: 不公告、不发触发 Toast 的增量包 (登录前后两种时机), 照常生成待领取奖励, 重复执行不重复发。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AchievementP2SocialGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "achievement_p2_social";
    private static final String TACZ = "tacz";

    private static final String STAT = "miningdim:stat_at_least";
    private static final String MARKET = "miningdim:market_trade";
    private static final String CASE = "miningdim:case_open";
    private static final String QUEST = "miningdim:quest_complete";
    private static final String TELEPORT = "miningdim:spouse_teleport";
    private static final String COUNT = "miningdim:achievement_count";

    private static final ResourceLocation TEST_GUN = new ResourceLocation("tacz", "test_sniper");
    private static final CaseRoller.BoundedRandom LOWEST_ROLL = bound -> 0;
    private static final CaseRoller.BoundedRandom HIGHEST_ROLL = bound -> bound - 1;

    /** 第四章: 档位 -> 框体、是否公告、默认点数。 */
    private static final Map<String, TierSpec> TIERS = Map.of(
            "bronze", new TierSpec(FrameType.TASK, false, 10),
            "silver", new TierSpec(FrameType.TASK, false, 25),
            "gold", new TierSpec(FrameType.GOAL, true, 50),
            "platinum", new TierSpec(FrameType.GOAL, true, 100),
            "diamond", new TierSpec(FrameType.CHALLENGE, true, 200),
            "master", new TierSpec(FrameType.CHALLENGE, true, 400));

    /** 9.4 全部 11 条、9.5 的 7 条 P2 行与 9.6 的两条, 条件按 6.2 的字段名写。 */
    private static final List<Expected> EXPECTED = List.of(
            row("economy/first_paycheck", "bronze", "economy/root", "minecraft:gold_nugget", STAT,
                    "{\"stat\":\"miningdim:credits_earned\",\"value\":10000}"),
            row("economy/income_300k", "silver", "economy/first_paycheck", "minecraft:gold_ingot", STAT,
                    "{\"stat\":\"miningdim:credits_earned\",\"value\":300000}"),
            row("economy/income_1500k", "gold", "economy/income_300k", "minecraft:gold_block", STAT,
                    "{\"stat\":\"miningdim:credits_earned\",\"value\":1500000}"),
            row("economy/income_5m", "platinum", "economy/income_1500k", "minecraft:netherite_ingot", STAT,
                    "{\"stat\":\"miningdim:credits_earned\",\"value\":5000000}"),
            row("economy/first_trade", "bronze", "economy/root", "minecraft:barrel", MARKET,
                    "{\"role\":\"any\",\"count\":1}"),
            row("economy/market_100k", "silver", "economy/first_trade", "minecraft:emerald_block", MARKET,
                    "{\"role\":\"seller\",\"count\":1,\"volume\":100000}"),
            row("economy/market_1m", "gold", "economy/market_100k", "minecraft:diamond_block", MARKET,
                    "{\"role\":\"seller\",\"count\":1,\"volume\":1000000,\"partners\":3,\"partner_min\":10000}"),
            row("economy/tycoon", "master", "economy/market_1m", "minecraft:netherite_block", MARKET,
                    "{\"role\":\"seller\",\"count\":1,\"volume\":20000000,\"partners\":20,\"partner_min\":10000}")
                    .titled(),
            row("economy/first_case", "silver", "economy/root", "minecraft:ender_chest", CASE, "{}").tacz(),
            row("economy/lucky_case", "diamond", "economy/first_case", "minecraft:enchanted_golden_apple", CASE,
                    "{\"min_rarity\":\"gold\"}").hidden().titled().tacz(),
            row("economy/all_in", "bronze", "economy/first_case", "minecraft:bowl", CASE,
                    "{\"max_credit_after\":999}").hidden().titled().tacz(),
            row("social/quest_first", "bronze", "social/root", "minecraft:writable_book", STAT,
                    "{\"stat\":\"miningdim:quests_completed\",\"value\":1}"),
            row("social/special_quest", "bronze", "social/quest_first", "minecraft:map", QUEST,
                    "{\"source\":\"special\"}"),
            row("social/quest_10", "silver", "social/quest_first", "minecraft:paper", STAT,
                    "{\"stat\":\"miningdim:quests_completed\",\"value\":10}"),
            row("social/quest_200", "platinum", "social/quest_10", "minecraft:bookshelf", STAT,
                    "{\"stat\":\"miningdim:quests_completed\",\"value\":200}"),
            row("social/daily_clear_60", "diamond", "social/quest_10", "minecraft:clock", STAT,
                    "{\"stat\":\"miningdim:quest_daily_clears\",\"value\":60}").titled(),
            row("social/marksman_chain", "gold", "social/quest_first", "minecraft:spectral_arrow", QUEST,
                    "{\"chain\":\"marksman\",\"chain_finished\":true}").hidden().tacz(),
            row("social/long_distance", "bronze", "social/married", "minecraft:ender_pearl", TELEPORT,
                    "{\"min_distance\":1000.0}").hidden(),
            row("meta/count_25", "gold", "meta/count_10", "minecraft:diamond", COUNT, "{\"count\":25}"),
            row("meta/count_40", "platinum", "meta/count_25", "minecraft:nether_star", COUNT, "{\"count\":40}")
                    .titled());

    private AchievementP2SocialGameTests() {
    }

    // ---- 1. 进度按规格加载 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void p2SocialAdvancementsLoadAsSpecified(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        boolean tacz = ModList.get().isLoaded(TACZ);
        AchievementCatalog catalog = AchievementServices.catalog();
        ConsistencyReport report = AchievementConsistency.check(server.getAdvancements().getAllAdvancements(),
                catalog.metas(), AchievementConsistency::titleDefined);
        JsonObject zh = readLang("zh_cn");
        JsonObject en = readLang("en_us");
        int totalPoints = 0;
        for (Expected expected : EXPECTED) {
            ResourceLocation id = AchievementIds.id(expected.path);
            TierSpec spec = TIERS.get(expected.tier);
            AchievementMeta meta = catalog.meta(id).orElse(null);
            helper.assertTrue(meta != null && meta.tier() != null && meta.tier().id().equals(expected.tier)
                            && meta.hidden() == expected.hidden && meta.pointsOverride() == null
                            && catalog.points(id) == spec.points,
                    id + " 的元数据应为 " + expected.tier + " 档、默认 " + spec.points + " 点, 实为 " + meta);
            helper.assertTrue(Objects.equals(catalog.title(id).orElse(null), expected.titled ? id : null),
                    id + " 附带的称号应为 " + (expected.titled ? id : "无") + " (9.8)");
            if (expected.titled) {
                helper.assertTrue(AchievementConsistency.titleDefined(id), "称号定义缺失: " + id);
            }
            for (String key : List.of(AchievementIds.titleKey(id), AchievementIds.descriptionKey(id))) {
                helper.assertTrue(hasText(zh, key) && hasText(en, key), "zh_cn / en_us 都必须有 " + key);
            }
            totalPoints += spec.points;

            Advancement advancement = server.getAdvancements().getAdvancement(id);
            if (expected.tacz && !tacz) {
                helper.assertTrue(advancement == null && report.metaWithoutAdvancement().contains(id),
                        id + " 依赖 TaCZ, 没装 TaCZ 时不应加载, 只留元数据");
                continue;
            }
            helper.assertTrue(advancement != null, "服务端没有加载 " + id);
            DisplayInfo display = Objects.requireNonNull(advancement.getDisplay(), id + " 缺少显示信息");
            helper.assertTrue(Objects.equals(advancement.getParent() == null ? null : advancement.getParent().getId(),
                    AchievementIds.id(expected.parent)), id + " 的父进度应为 " + expected.parent);
            helper.assertTrue(display.getFrame() == spec.frame
                            && display.shouldAnnounceChat() == (spec.announce || expected.hidden)
                            && display.isHidden() == expected.hidden && display.shouldShowToast(),
                    id + " 的框体、公告、隐藏与 Toast 应由 " + expected.tier + " 档推导 (隐藏一律公告)");
            helper.assertTrue(expected.icon.equals(ForgeRegistries.ITEMS.getKey(display.getIcon().getItem())),
                    id + " 的图标应为 " + expected.icon);
            helper.assertTrue(advancement.getCriteria().size() == 1, id + " 应恰好有一个条件");
            Criterion criterion = advancement.getCriteria().values().iterator().next();
            CriterionTriggerInstance instance = Objects.requireNonNull(criterion.getTrigger());
            helper.assertTrue(instance.getCriterion().equals(new ResourceLocation(expected.trigger)),
                    id + " 的触发器应为 " + expected.trigger + ", 实为 " + instance.getCriterion());
            JsonObject conditions = instance.serializeToJson(SerializationContext.INSTANCE);
            conditions.remove("player");
            helper.assertTrue(conditions.equals(JsonParser.parseString(expected.conditions)),
                    id + " 的条件应为 " + expected.conditions + ", 实为 " + conditions);
        }
        // 铜 6 x10 + 银 4 x25 + 金 4 x50 + 白金 3 x100 + 钻石 2 x200 + 大师 1 x400 = 1460。
        helper.assertTrue(totalPoints == 1460, "P2 经济社交与两条 meta 成就的默认点数合计应为 1460, 实为 " + totalPoints);
        helper.assertTrue(report.isClean(), "加上 P2 之后一致性校验仍应无问题, 实报 " + report.problems());
        helper.succeed();
    }

    // ---- 2. 触发器 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void p2TriggersRoundTripRejectMistakesAndMatchAtBoundaries(GameTestHelper helper) {
        DeserializationContext context = new DeserializationContext(AchievementIds.id("test/p2_social_round_trip"),
                helper.getLevel().getServer().getLootData());
        JsonObject tycoon = roundTrip(helper, context, AchievementTriggers.MARKET_TRADE,
                MarketTradeTrigger.TriggerInstance.soldToPartners(20_000_000L, 20, 10_000L));
        helper.assertTrue("seller".equals(tycoon.get("role").getAsString())
                && tycoon.get("volume").getAsLong() == 20_000_000L && tycoon.get("partners").getAsInt() == 20
                && tycoon.get("partner_min").getAsLong() == 10_000L, "market_trade 应写出 6.2 的字段名, 实为 " + tycoon);
        roundTrip(helper, context, AchievementTriggers.MARKET_TRADE,
                MarketTradeTrigger.TriggerInstance.trades(MarketTradeTrigger.Role.BUYER, 3));
        JsonObject lucky = roundTrip(helper, context, AchievementTriggers.CASE_OPEN,
                new CaseOpenTrigger.TriggerInstance(ContextAwarePredicate.ANY, CaseRarity.PINK, 999L));
        helper.assertTrue("pink".equals(lucky.get("min_rarity").getAsString())
                && lucky.get("max_credit_after").getAsLong() == 999L, "case_open 字段应为小写品质与余额, 实为 " + lucky);
        JsonObject quest = roundTrip(helper, context, AchievementTriggers.QUEST_COMPLETE,
                new QuestCompleteTrigger.TriggerInstance(ContextAwarePredicate.ANY, QuestSource.HIDDEN, "marksman.4",
                        QuestPool.CHAIN_MARKSMAN, true));
        helper.assertTrue("hidden".equals(quest.get("source").getAsString())
                && "marksman.4".equals(quest.get("quest_id").getAsString())
                && QuestPool.CHAIN_MARKSMAN.equals(quest.get("chain").getAsString())
                && quest.get("chain_finished").getAsBoolean(), "quest_complete 应写出四个字段, 实为 " + quest);
        roundTrip(helper, context, AchievementTriggers.SPOUSE_TELEPORT,
                SpouseTeleportTrigger.TriggerInstance.beyond(1_000.0D));

        assertRejected(helper, context, AchievementTriggers.MARKET_TRADE, "{\"role\":\"landlord\"}");
        assertRejected(helper, context, AchievementTriggers.MARKET_TRADE, "{\"count\":0}");
        assertRejected(helper, context, AchievementTriggers.MARKET_TRADE, "{\"volume\":100000}");
        assertRejected(helper, context, AchievementTriggers.MARKET_TRADE, "{\"role\":\"buyer\",\"partners\":3}");
        assertRejected(helper, context, AchievementTriggers.MARKET_TRADE, "{\"role\":\"seller\",\"volume\":0}");
        assertRejected(helper, context, AchievementTriggers.MARKET_TRADE, "{\"role\":\"seller\",\"partner_min\":5}");
        assertRejected(helper, context, AchievementTriggers.CASE_OPEN, "{\"min_rarity\":\"green\"}");
        assertRejected(helper, context, AchievementTriggers.CASE_OPEN, "{\"max_credit_after\":-1}");
        assertRejected(helper, context, AchievementTriggers.QUEST_COMPLETE, "{\"source\":\"monthly\"}");
        assertRejected(helper, context, AchievementTriggers.QUEST_COMPLETE, "{\"quest_id\":\" \"}");
        assertRejected(helper, context, AchievementTriggers.QUEST_COMPLETE, "{\"chain\":\"\"}");
        assertRejected(helper, context, AchievementTriggers.SPOUSE_TELEPORT, "{\"min_distance\":-1}");

        MarketTradeTrigger.TriggerInstance anyTrade = MarketTradeTrigger.TriggerInstance.trades(
                MarketTradeTrigger.Role.ANY, 1);
        MarketTradeTrigger.TriggerInstance bigSeller = MarketTradeTrigger.TriggerInstance.soldToPartners(
                1_000_000L, 3, 10_000L);
        helper.assertTrue(!anyTrade.matches(new MarketStanding(0, 0, 0L, List.of()))
                        && anyTrade.matches(new MarketStanding(1, 0, 0L, List.of()))
                        && anyTrade.matches(new MarketStanding(0, 1, 0L, List.of(0L))),
                "role=any 的第一笔合格交易买入卖出都算");
        helper.assertTrue(!MarketTradeTrigger.TriggerInstance.trades(MarketTradeTrigger.Role.SELLER, 1)
                .matches(new MarketStanding(5, 0, 0L, List.of())), "role=seller 不认买入");
        helper.assertTrue(!bigSeller.matches(new MarketStanding(0, 3, 999_999L, List.of(400_000L, 400_000L, 199_999L)))
                        && !bigSeller.matches(new MarketStanding(0, 3, 1_000_000L, List.of(500_000L, 490_001L, 9_999L)))
                        && bigSeller.matches(new MarketStanding(0, 3, 1_000_000L, List.of(500_000L, 490_000L, 10_000L))),
                "生意兴隆: 计数成交额差 1 不达标; 第三位买家差 1 不算对手; 恰好达标才算");

        CaseOpenTrigger.TriggerInstance pink = CaseOpenTrigger.TriggerInstance.atLeast(CaseRarity.PINK);
        CaseOpenTrigger.TriggerInstance allIn = CaseOpenTrigger.TriggerInstance.creditAfterAtMost(999L);
        helper.assertTrue(!pink.matches(CaseRarity.PURPLE, true, 0L) && pink.matches(CaseRarity.PINK, false, 0L)
                && pink.matches(CaseRarity.GOLD, true, 0L), "min_rarity 按 blue<purple<pink<red<gold 的顺序判定");
        helper.assertTrue(allIn.matches(CaseRarity.BLUE, true, 999L) && !allIn.matches(CaseRarity.BLUE, true, 1_000L)
                && !allIn.matches(CaseRarity.BLUE, false, 0L), "max_credit_after 只认正常开箱, 999 算、1000 不算");

        QuestCompleteTrigger.TriggerInstance marksman = QuestCompleteTrigger.TriggerInstance.chainFinished(
                QuestPool.CHAIN_MARKSMAN);
        QuestCompleteTrigger.TriggerInstance special = QuestCompleteTrigger.TriggerInstance.fromSource(
                QuestSource.SPECIAL);
        helper.assertTrue(!marksman.matches(QuestSource.HIDDEN, "marksman.3", QuestPool.CHAIN_MARKSMAN, false)
                        && marksman.matches(QuestSource.HIDDEN, "marksman.4", QuestPool.CHAIN_MARKSMAN, true)
                        && !marksman.matches(QuestSource.HIDDEN, "other.4", "other", true),
                "chain_finished 要求走完指定的那条任务线");
        helper.assertTrue(special.matches(QuestSource.SPECIAL, "special.village.trade", null, false)
                && !special.matches(QuestSource.DAILY, "daily.mine.iron", null, false), "source 只认对应来源");
        SpouseTeleportTrigger.TriggerInstance far = SpouseTeleportTrigger.TriggerInstance.beyond(1_000.0D);
        helper.assertTrue(!far.matches(999.99D) && far.matches(1_000.0D), "min_distance 在 1000 格处取等");
        helper.succeed();
    }

    // ---- 3. 提交后队列 ----

    /** 嵌套事务里登记的动作等最外层提交后才执行 (那时已不在事务里), 回滚即丢弃, 抛出的动作不影响提交与其余动作。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void afterCommitActionsRunOnlyAfterTheOutermostCommit(GameTestHelper helper) {
        Connection conn = MiningDb.openInMemory();
        try {
            execute(conn, "CREATE TABLE probe (v INTEGER NOT NULL)");
            List<String> log = new ArrayList<>();
            StoreTx.afterCommit(conn, () -> log.add("immediate:" + autoCommit(conn)));
            helper.assertTrue(log.equals(List.of("immediate:true")), "不在事务中时应立即执行, 实为 " + log);

            StoreTx.run(conn, () -> {
                execute(conn, "INSERT INTO probe VALUES (1)");
                StoreTx.run(conn, () -> {
                    execute(conn, "INSERT INTO probe VALUES (2)");
                    StoreTx.afterCommit(conn, () -> log.add("inner:" + autoCommit(conn)));
                });
                helper.assertTrue(log.size() == 1, "内层事务返回时外层还没提交, 不应执行, 实为 " + log);
                StoreTx.afterCommit(conn, () -> {
                    throw new IllegalStateException("after-commit failure injected by the test");
                });
                StoreTx.afterCommit(conn, () -> log.add("outer:" + autoCommit(conn)));
            });
            helper.assertTrue(log.equals(List.of("immediate:true", "inner:true", "outer:true")),
                    "最外层提交后按登记顺序执行、执行时已不在事务里, 抛出的动作不挡后面的, 实为 " + log);
            helper.assertTrue(countRows(conn) == 2, "提交应落下两行");

            boolean rolledBack = false;
            try {
                StoreTx.run(conn, () -> {
                    execute(conn, "INSERT INTO probe VALUES (3)");
                    StoreTx.afterCommit(conn, () -> log.add("rolled back"));
                    throw new SimulatedFailure();
                });
            } catch (SimulatedFailure expected) {
                rolledBack = true;
            }
            StoreTx.run(conn, () -> execute(conn, "INSERT INTO probe VALUES (4)"));
            helper.assertTrue(rolledBack && countRows(conn) == 3 && log.size() == 3,
                    "回滚的事务里登记的动作必须作废, 也不能被下一个事务捎带执行, 实为 " + log);
        } finally {
            MiningDb.close(conn);
        }
        helper.succeed();
    }

    // ---- 4. 监听接口: 经济 faucet ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void faucetSeamCreditsEarnedAfterTheOuterCommitOnly(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService economy = new EconomyService(ledger, new AbuseGuard(), states());
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper, profile("p2s-faucet"));
        String key = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY;
        long tier = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER;
        try {
            Probe.arm(false, ledger.connection());
            long credited = economy.grantDaily(player, 4_000L, key, tier);
            helper.assertTrue(credited == 4_000L && Probe.events(Faucet.class).equals(
                            List.of(new Faucet(player.getUUID(), key, 4_000L))),
                    "事务外的一笔 faucet 入账应恰好通知一次, 实为 " + Probe.events(Faucet.class));
            helper.assertTrue(stat(player, AchievementStats.CREDITS_EARNED) == 4_000,
                    "credits_earned 应加上实际入账额 4000");

            economy.inTransaction(() -> {
                economy.grantDaily(player, 3_000L, key, tier);
                helper.assertTrue(Probe.events(Faucet.class).size() == 1, "外层事务未提交前不应通知");
                return null;
            });
            helper.assertTrue(Probe.events(Faucet.class).size() == 2 && stat(player, AchievementStats.CREDITS_EARNED)
                    == 7_000, "外层提交后才通知, credits_earned 到 7000");

            long balance = ledger.balance(player.getUUID(), Currency.CREDIT);
            boolean rolledBack = false;
            try {
                economy.inTransaction(() -> {
                    economy.grantDaily(player, 5_000L, key, tier);
                    throw new SimulatedFailure();
                });
            } catch (SimulatedFailure expected) {
                rolledBack = true;
            }
            helper.assertTrue(rolledBack && Probe.events(Faucet.class).size() == 2
                            && stat(player, AchievementStats.CREDITS_EARNED) == 7_000
                            && ledger.balance(player.getUUID(), Currency.CREDIT) == balance,
                    "回滚的入账不通知、不计 credits_earned");

            int counted = economy.recordMinedOreDrops(player, Blocks.DIAMOND_ORE, 3);
            List<Faucet> afterBatch = Probe.events(Faucet.class);
            helper.assertTrue(counted == 3 && afterBatch.size() == 5,
                    "一次连锁结算三颗钻石 (嵌套在批量事务里的三笔 grantDaily) 应通知三次, 实为 " + afterBatch);
            helper.assertTrue(Probe.firedInsideTransaction().stream().noneMatch(Boolean::booleanValue),
                    "每一次通知都必须发生在事务之外 (批量事务提交之后)");
            long batchCredit = afterBatch.subList(2, 5).stream().mapToLong(Faucet::credited).sum();
            helper.assertTrue(stat(player, AchievementStats.CREDITS_EARNED) == 7_000 + batchCredit,
                    "批量结算的实际入账也计入 credits_earned");

            Probe.arm(true, null);
            long before = ledger.balance(player.getUUID(), Currency.CREDIT);
            int earnedBefore = stat(player, AchievementStats.CREDITS_EARNED);
            long more = economy.grantDaily(player, 3_000L, key, tier);
            helper.assertTrue(more > 0L && ledger.balance(player.getUUID(), Currency.CREDIT) == before + more
                            && stat(player, AchievementStats.CREDITS_EARNED) == earnedBefore + more,
                    "监听器抛出不影响入账, 也不影响排在前面的成就监听器");
            assertDone(helper, player, "economy/first_paycheck", true);
            assertDone(helper, player, "economy/income_300k", false);
        } finally {
            Probe.disarm();
            logout(server, player);
            MiningDb.close(ledger.connection());
        }
        helper.succeed();
    }

    // ---- 5. 监听接口: 市场成交 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marketTradeSeamFiresOnceAfterTheBuyCommits(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService economy = new EconomyService(ledger, new AbuseGuard(), states());
        IEconomyService previous = swapEconomy(economy);
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        List<ServerPlayer> online = new ArrayList<>();
        try {
            MarketEngine engine = new MarketEngine(dao, server);
            ServerPlayer seller = login(helper, online, profile("p2s-seam-seller"), "10.21.0.1");
            ServerPlayer buyer = login(helper, online, profile("p2s-seam-buyer"), "10.21.0.2");
            economy.grant(seller, Currency.CREDIT, 1_000_000L);
            economy.grant(buyer, Currency.CREDIT, 1_000_000L);
            long listing = list(engine, seller, 2_500L, 3);

            Probe.arm(false, ledger.connection());
            engine.buy(buyer, listing, 1);
            helper.assertTrue(Probe.events(MarketTrade.class).equals(List.of(new MarketTrade(buyer, seller.getUUID(),
                            "minecraft:cobblestone", 1, 2_500L))),
                    "一笔成交应恰好通知一次并带上买家、卖家、标的、数量与成交额, 实为 " + Probe.events(MarketTrade.class));
            helper.assertTrue(Probe.firedInsideTransaction().equals(List.of(false)), "成交通知必须在事务提交之后");

            boolean rolledBack = false;
            try {
                economy.inTransaction(() -> {
                    engine.buy(buyer, listing, 1);
                    throw new SimulatedFailure();
                });
            } catch (SimulatedFailure expected) {
                rolledBack = true;
            }
            helper.assertTrue(rolledBack && Probe.events(MarketTrade.class).size() == 1
                            && dao.findListing(listing).count() == 2,
                    "外层事务回滚时这笔成交不存在, 不应通知");

            Probe.arm(true, null);
            MarketEngine.BuyResult bought = engine.buy(buyer, listing, 0);
            helper.assertTrue(bought.count() == 2 && "SOLD".equals(dao.findListing(listing).status())
                            && Probe.events(MarketTrade.class).size() == 1,
                    "监听器抛出不影响成交");
        } finally {
            Probe.disarm();
            online.forEach(player -> logout(server, player));
            MarketDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    // ---- 6. 市场防刷规则 ----

    /**
     * 6.6: 低于 1,000、同 IP、夫妻之间的成交不计; 合格交易按买家记账, 一位买家贡献给一位卖家的计数成交额不超过
     * min(累计, 1,000,000, 买家当时的 credits_earned); 市场收入不进 credits_earned; 离线卖家的成交照常记账, 登录时补查。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marketRulesCountOnlyGenuineTrades(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService economy = new EconomyService(ledger, new AbuseGuard(), states());
        IEconomyService previous = swapEconomy(economy);
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        List<ServerPlayer> online = new ArrayList<>();
        try {
            MarketEngine engine = new MarketEngine(dao, server);
            MarketPartnerRepository partners = AchievementServices.marketPartners();
            ServerPlayer seller = login(helper, online, profile("p2s-mkt-seller"), "10.22.0.1");
            ServerPlayer buyer = login(helper, online, profile("p2s-mkt-buyer"), "10.22.0.2");
            ServerPlayer twin = login(helper, online, profile("p2s-mkt-twin"), "10.22.0.1");
            ServerPlayer spouse = login(helper, online, profile("p2s-mkt-spouse"), "10.22.0.3");
            GameProfile absentProfile = profile("p2s-mkt-absent");
            ServerPlayer absent = login(helper, online, absentProfile, "10.22.0.4");
            for (ServerPlayer player : List.of(seller, buyer, twin, spouse, absent)) {
                economy.grant(player, Currency.CREDIT, 10_000_000L);
            }
            setMarriage(seller, 42L, spouse.getUUID());
            setMarriage(spouse, 42L, seller.getUUID());
            UUID sellerId = seller.getUUID();

            buy(engine, buyer, list(engine, seller, 999L, 1));
            helper.assertTrue(partners.standing(buyer.getUUID()).buyerTrades() == 0
                    && !done(buyer, "economy/first_trade"), "成交额 999 不是合格交易");
            buy(engine, twin, list(engine, seller, 5_000L, 1));
            helper.assertTrue(partners.standing(twin.getUUID()).buyerTrades() == 0, "与卖家同 IP 的买家不计");
            buy(engine, spouse, list(engine, seller, 5_000L, 1));
            helper.assertTrue(partners.standing(spouse.getUUID()).buyerTrades() == 0, "卖家的配偶买入不计");
            helper.assertTrue(partners.standing(sellerId).sellerTrades() == 0 && !done(seller, "economy/first_trade"),
                    "三笔不计的成交都不能让卖家拿到开张大吉");

            helper.assertTrue(stat(buyer, AchievementStats.CREDITS_EARNED) == 0, "前提: 买家还没有系统收入");
            buy(engine, buyer, list(engine, seller, 5_000L, 1));
            MarketStanding afterFirst = partners.standing(sellerId);
            helper.assertTrue(partners.standing(buyer.getUUID()).buyerTrades() == 1 && afterFirst.sellerTrades() == 1
                            && afterFirst.sellerVolume() == 0L,
                    "没有系统收入的买家: 合格交易照计笔数, 计数成交额为 0, 实为 " + afterFirst);
            assertDone(helper, buyer, "economy/first_trade", true);
            assertDone(helper, seller, "economy/first_trade", true);

            AchievementStats.award(buyer, AchievementStats.CREDITS_EARNED, 150_000);
            buy(engine, buyer, list(engine, seller, 120_000L, 1));
            helper.assertTrue(partners.standing(sellerId).sellerVolume() == 120_000L,
                    "买家收入 150,000 时这笔 120,000 全额计入");
            assertDone(helper, seller, "economy/market_100k", true);
            assertDone(helper, buyer, "economy/market_100k", false);
            buy(engine, buyer, list(engine, seller, 100_000L, 1));
            helper.assertTrue(partners.standing(sellerId).sellerVolume() == 150_000L,
                    "计数成交额不超过买家当时的 credits_earned (150,000), 实为 " + partners.standing(sellerId));

            AchievementStats.award(buyer, AchievementStats.CREDITS_EARNED, 4_850_000);
            buy(engine, buyer, list(engine, seller, 1_200_000L, 1));
            MarketStanding capped = partners.standing(sellerId);
            helper.assertTrue(capped.partnerVolumes().equals(List.of(1_000_000L)) && capped.sellerTrades() == 4,
                    "一位买家对一位卖家的计数成交额封顶 1,000,000, 实为 " + capped);
            assertDone(helper, seller, "economy/market_1m", false);
            helper.assertTrue(stat(seller, AchievementStats.CREDITS_EARNED) == 0, "市场收入不计入 credits_earned");

            long absentListing = list(engine, absent, 2_000L, 1);
            logout(server, absent);
            buy(engine, buyer, absentListing);
            helper.assertTrue(partners.standing(absentProfile.getId()).sellerTrades() == 1,
                    "卖家离线时合格交易照常记账");
            ServerPlayer back = login(helper, online, absentProfile, "10.22.0.4");
            assertDone(helper, back, "economy/first_trade", true);
        } finally {
            online.forEach(player -> logout(server, player));
            MarketDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    // ---- 7. 监听接口: 开箱结算 ----

    /** 正常开箱、登录恢复补扣款、启动期对账三条落锚路径各通知一次; 重放与回滚不通知; 监听器抛出不影响开箱。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void caseOpeningSeamFiresOncePerOpeningOnEverySettlePath(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService economy = new EconomyService(ledger, new AbuseGuard(), states());
        IEconomyService previous = swapEconomy(economy);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        List<ServerPlayer> online = new ArrayList<>();
        try {
            ServerPlayer opener = logged(helper, online, "p2s-case-open");
            ServerPlayer failing = logged(helper, online, "p2s-case-fail");
            ServerPlayer recovering = logged(helper, online, "p2s-case-recover");
            for (ServerPlayer player : List.of(opener, failing, recovering)) {
                economy.grant(player, Currency.CREDIT, 1_000_000L);
                economy.grant(player, Currency.AZURE, 100L);
            }
            Probe.arm(false, ledger.connection());

            UUID freshId = UUID.randomUUID();
            CaseOpeningService.OpenResult opened = caseService(dao, LOWEST_ROLL).open(opener, freshId,
                    CaseCatalog.CASE_ID);
            helper.assertTrue(Probe.events(SettledOpening.class).equals(List.of(new SettledOpening(freshId,
                            opener.getUUID(), opened.opening().rarity(), true))),
                    "正常开箱应恰好通知一次且标为 fresh, 实为 " + Probe.events(SettledOpening.class));
            caseService(dao, LOWEST_ROLL).open(opener, freshId, CaseCatalog.CASE_ID);
            helper.assertTrue(Probe.events(SettledOpening.class).size() == 1, "重放同一个开箱 id 不再通知");

            boolean failed = false;
            try {
                new CaseOpeningService(dao, completionFails(new EconomyCaseOperations()), new CaseRoller(LOWEST_ROLL),
                        () -> true, () -> true, () -> true, () -> 50_000L, () -> 10L, () -> CaseWeights.DEFAULT,
                        () -> 20).open(failing, UUID.randomUUID(), CaseCatalog.CASE_ID);
            } catch (RuntimeException expected) {
                failed = true;
            }
            helper.assertTrue(failed && Probe.events(SettledOpening.class).size() == 1,
                    "事务最后一步失败而整体回滚的开箱不通知");

            long now = System.currentTimeMillis();
            UUID recoveredId = committedButUnsettled(dao, recovering.getUUID(), now);
            helper.assertTrue(economy.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, recovering, recoveredId,
                    50_000L, 10L) == EconomyOperationStatus.CHARGED, "前提: 账本停在已扣款");
            helper.assertTrue(caseService(dao, LOWEST_ROLL).recoverFor(recovering) == 1, "登录恢复应补结算这一行");
            UUID offlineOwner = UUID.randomUUID();
            ledger.credit(offlineOwner, Currency.CREDIT, 100_000L);
            ledger.credit(offlineOwner, Currency.AZURE, 20L);
            UUID reconciledId = committedButUnsettled(dao, offlineOwner, now);
            helper.assertTrue(ledger.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, offlineOwner, reconciledId,
                    50_000L, 10L) == EconomyOperationStatus.CHARGED, "前提: 离线玩家的账本停在已扣款");
            helper.assertTrue(caseService(dao, LOWEST_ROLL).reconcileAtStartup() >= 1, "启动期对账应推进这一行");
            CaseRarity recoveredRarity = dao.findOpening(recoveredId).rarity();
            helper.assertTrue(Probe.events(SettledOpening.class).subList(1, 3).equals(List.of(
                            new SettledOpening(recoveredId, recovering.getUUID(), recoveredRarity, false),
                            new SettledOpening(reconciledId, offlineOwner, recoveredRarity, false))),
                    "两条恢复路径各通知一次且都不是 fresh, 实为 " + Probe.events(SettledOpening.class));
            caseService(dao, LOWEST_ROLL).recoverFor(recovering);
            caseService(dao, LOWEST_ROLL).reconcileAtStartup();
            helper.assertTrue(Probe.events(SettledOpening.class).size() == 3, "已落锚的开箱不会被再次通知");
            helper.assertTrue(Probe.firedInsideTransaction().stream().noneMatch(Boolean::booleanValue),
                    "每一次通知都在落锚事务提交之后");

            Probe.arm(true, null);
            UUID thrownId = UUID.randomUUID();
            caseService(dao, LOWEST_ROLL).open(opener, thrownId, CaseCatalog.CASE_ID);
            helper.assertTrue(dao.isOpeningSettled(thrownId) && Probe.events(SettledOpening.class).size() == 1,
                    "监听器抛出不影响开箱落锚");
        } finally {
            Probe.disarm();
            online.forEach(player -> logout(server, player));
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    // ---- 8. 开箱条件与追溯 ----

    /**
     * 开出的品质按 CaseRarity 的顺序判定; "倾家荡产"读正常开箱提交后的余额, 恢复流程补结算的不算; 上线追溯按已结算
     * 开箱里的最高品质 (不是最后一次; 开箱模块在 SQL 侧按品质去重) 补 case_open, 同样不满足"倾家荡产"。开箱成就依赖 TaCZ, 这里把条件挂成临时进度, 走真实的监听器与触发器。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void caseConditionsFollowTheSettledOpeningAndBackfill(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService economy = new EconomyService(ledger, new AbuseGuard(), states());
        IEconomyService previous = swapEconomy(economy);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        CaseOpeningService registered = CaseServices.isRegistered() ? CaseServices.service() : null;
        List<ServerPlayer> online = new ArrayList<>();
        try {
            ServerPlayer lucky = logged(helper, online, "p2s-case-lucky");
            economy.grant(lucky, Currency.CREDIT, 50_999L);
            economy.grant(lucky, Currency.AZURE, 10L);
            Advancement luckyAny = watch(lucky, AchievementTriggers.CASE_OPEN, CaseOpenTrigger.TriggerInstance.any(),
                    "case_any");
            Advancement luckyGold = watch(lucky, AchievementTriggers.CASE_OPEN,
                    CaseOpenTrigger.TriggerInstance.atLeast(CaseRarity.GOLD), "case_gold");
            Advancement luckyAllIn = watch(lucky, AchievementTriggers.CASE_OPEN,
                    CaseOpenTrigger.TriggerInstance.creditAfterAtMost(999L), "case_all_in");
            caseService(dao, HIGHEST_ROLL).open(lucky, UUID.randomUUID(), CaseCatalog.CASE_ID);
            helper.assertTrue(met(lucky, luckyAny) && met(lucky, luckyGold) && met(lucky, luckyAllIn),
                    "开出金色、开箱后余额 999: 任意开箱、欧皇、倾家荡产三条都应满足");

            ServerPlayer plain = logged(helper, online, "p2s-case-plain");
            economy.grant(plain, Currency.CREDIT, 51_000L);
            economy.grant(plain, Currency.AZURE, 10L);
            Advancement plainPink = watch(plain, AchievementTriggers.CASE_OPEN,
                    CaseOpenTrigger.TriggerInstance.atLeast(CaseRarity.PINK), "case_pink");
            Advancement plainAllIn = watch(plain, AchievementTriggers.CASE_OPEN,
                    CaseOpenTrigger.TriggerInstance.creditAfterAtMost(999L), "case_all_in");
            caseService(dao, LOWEST_ROLL).open(plain, UUID.randomUUID(), CaseCatalog.CASE_ID);
            helper.assertTrue(!met(plain, plainPink) && !met(plain, plainAllIn),
                    "开出蓝色不满足 min_rarity=pink; 开箱后余额 1000 不满足 max_credit_after=999");

            ServerPlayer recovered = logged(helper, online, "p2s-case-late");
            economy.grant(recovered, Currency.CREDIT, 50_500L);
            economy.grant(recovered, Currency.AZURE, 10L);
            UUID lateId = committedButUnsettled(dao, recovered.getUUID(), System.currentTimeMillis());
            economy.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, recovered, lateId, 50_000L, 10L);
            Advancement lateAny = watch(recovered, AchievementTriggers.CASE_OPEN,
                    CaseOpenTrigger.TriggerInstance.any(), "case_any");
            Advancement lateAllIn = watch(recovered, AchievementTriggers.CASE_OPEN,
                    CaseOpenTrigger.TriggerInstance.creditAfterAtMost(999L), "case_all_in");
            caseService(dao, LOWEST_ROLL).recoverFor(recovered);
            helper.assertTrue(met(recovered, lateAny) && !met(recovered, lateAllIn)
                            && economy.creditBalance(recovered) == 500L,
                    "恢复流程补结算的开箱: 算开过箱, 但余额 500 也不算倾家荡产");

            ServerPlayer veteran = logged(helper, online, "p2s-case-old");
            economy.grant(veteran, Currency.CREDIT, 150_100L);
            economy.grant(veteran, Currency.AZURE, 30L);
            // 先金后蓝再蓝: 追溯要的是最高品质而不是最后一次, 重复的品质在 SQL 侧去重。
            CaseRarity top = caseService(dao, HIGHEST_ROLL).open(veteran, UUID.randomUUID(), CaseCatalog.CASE_ID)
                    .opening().rarity();
            CaseRarity last = caseService(dao, LOWEST_ROLL).open(veteran, UUID.randomUUID(), CaseCatalog.CASE_ID)
                    .opening().rarity();
            caseService(dao, LOWEST_ROLL).open(veteran, UUID.randomUUID(), CaseCatalog.CASE_ID);
            helper.assertTrue(top == CaseRarity.GOLD && last == CaseRarity.BLUE, "前提: 先开出金色, 后两次蓝色");
            helper.assertTrue(dao.settledRarities(veteran.getUUID()).equals(EnumSet.of(CaseRarity.BLUE, CaseRarity.GOLD)),
                    "已结算开箱的品质按品质去重, 实为 " + dao.settledRarities(veteran.getUUID()));
            helper.assertTrue(caseService(dao, LOWEST_ROLL).bestSettledRarity(veteran.getUUID())
                            .equals(Optional.of(CaseRarity.GOLD))
                            && caseService(dao, LOWEST_ROLL).bestSettledRarity(UUID.randomUUID()).isEmpty(),
                    "最高品质取金色 (不是最后一次的蓝色); 没开过箱的玩家为空");
            Advancement oldGold = watch(veteran, AchievementTriggers.CASE_OPEN,
                    CaseOpenTrigger.TriggerInstance.atLeast(CaseRarity.GOLD), "case_gold");
            Advancement oldAllIn = watch(veteran, AchievementTriggers.CASE_OPEN,
                    CaseOpenTrigger.TriggerInstance.creditAfterAtMost(1_000_000L), "case_all_in");
            CaseServices.reset();
            CaseServices.register(caseService(dao, LOWEST_ROLL));
            AchievementBackfill.runSilently(veteran);
            helper.assertTrue(met(veteran, oldGold) && !met(veteran, oldAllIn),
                    "上线追溯按已结算的金色开箱补上欧皇, 但追溯出来的开箱不算倾家荡产");
        } finally {
            CaseServices.reset();
            if (registered != null) {
                CaseServices.register(registered);
            }
            online.forEach(player -> logout(server, player));
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    // ---- 9. 监听接口: 任务领奖 ----

    /** 领奖成功时在最后一步通知一次 (特殊任务走完真实的记录与领取); 未达标、已摘牌不通知; 监听器抛出不影响领奖。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questClaimSeamFiresOnceAndGrantsSpecialQuest(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService previous = swapEconomy(new EconomyService(ledger, new AbuseGuard(), states()));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper, profile("p2s-quest"));
        try {
            QuestService quests = new QuestService(QuestPool.builtin());
            QuestDefinition special = quests.pool().byId("special.village.trade");
            QuestBoard board = quests.boardOf(player);
            helper.assertTrue(board.addSpecial(special, 3), "前提: 特殊任务应挂上");
            Villager villager = Objects.requireNonNull(EntityType.VILLAGER.create(helper.getLevel()));

            Probe.arm(false, null);
            helper.assertTrue(quests.claim(player, special.id()).outcome() == QuestService.ClaimOutcome.NOT_COMPLETE
                    && Probe.events(QuestClaim.class).isEmpty(), "未达标的领取不通知");
            quests.record(new QuestFacts.VillagerTrade(player, villager));
            QuestService.ClaimResult claimed = quests.claim(player, special.id());
            List<QuestClaim> claims = Probe.events(QuestClaim.class);
            helper.assertTrue(claimed.outcome() == QuestService.ClaimOutcome.CLAIMED && claims.size() == 1,
                    "领奖成功应恰好通知一次, 实为 " + claims);
            QuestClaim claim = claims.get(0);
            helper.assertTrue(claim.player() == player && claim.definition() == special && claim.chainId() == null
                            && !claim.chainFinished() && claim.dailyStamp() == board.dailyStamp()
                            && !claim.dailiesAllClaimed(),
                    "通知应带上任务、任务线与日常周期, 实为 " + claim);
            helper.assertTrue(quests.claim(player, special.id()).outcome() == QuestService.ClaimOutcome.NOT_FOUND
                    && Probe.events(QuestClaim.class).size() == 1, "已摘牌的特殊任务不再通知");
            helper.assertTrue(stat(player, AchievementStats.QUESTS_COMPLETED) == 1, "领奖计入 quests_completed");
            assertDone(helper, player, "social/quest_first", true);
            assertDone(helper, player, "social/special_quest", true);

            Probe.arm(true, null);
            board.addSpecial(special, 3);
            quests.record(new QuestFacts.VillagerTrade(player, villager));
            helper.assertTrue(quests.claim(player, special.id()).outcome() == QuestService.ClaimOutcome.CLAIMED
                            && stat(player, AchievementStats.QUESTS_COMPLETED) == 2,
                    "监听器抛出不影响领奖, 排在前面的成就监听器照常计数");
        } finally {
            Probe.disarm();
            logout(server, player);
            restoreEconomy(previous);
            MiningDb.close(ledger.connection());
        }
        helper.succeed();
    }

    /** quests_completed 每天最多计 dailyQuestCap 份; quest_daily_clears 只认领完当天日常的那一次, 每个周期戳一次。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questCountsHonourTheDailyCapAndOneClearPerDay(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper, profile("p2s-quest-cap"));
        try {
            helper.assertTrue(AchievementConfig.DAILY_QUEST_CAP.getDefault() == 6
                    && AchievementConfig.DAILY_QUEST_CAP.get() == 6, "dailyQuestCap 默认 6");
            QuestPool pool = QuestPool.builtin();
            QuestDefinition daily = pool.byId("daily.mine.iron");
            QuestDefinition weekly = pool.byId("weekly.mine.iron");
            long today = DailyCounterRepository.today();
            for (int i = 0; i < 7; i++) {
                QuestClaimHooks.onQuestClaimed(new QuestClaim(player, daily, null, false, today, false));
            }
            helper.assertTrue(stat(player, AchievementStats.QUESTS_COMPLETED) == 6,
                    "同一天第 7 份领奖不再计数, 实为 " + stat(player, AchievementStats.QUESTS_COMPLETED));
            assertDone(helper, player, "social/quest_first", true);
            assertDone(helper, player, "social/quest_10", false);

            QuestClaimHooks.onQuestClaimed(new QuestClaim(player, weekly, null, false, today, true));
            helper.assertTrue(stat(player, AchievementStats.QUEST_DAILY_CLEARS) == 0, "领的不是每日任务就不算全勤");
            QuestClaimHooks.onQuestClaimed(new QuestClaim(player, daily, null, false, today, true));
            QuestClaimHooks.onQuestClaimed(new QuestClaim(player, daily, null, false, today, true));
            helper.assertTrue(stat(player, AchievementStats.QUEST_DAILY_CLEARS) == 1, "同一个周期戳只计一次全勤");
            QuestClaimHooks.onQuestClaimed(new QuestClaim(player, daily, null, false, today + 1L, true));
            helper.assertTrue(stat(player, AchievementStats.QUEST_DAILY_CLEARS) == 2
                            && stat(player, AchievementStats.QUESTS_COMPLETED) == 6,
                    "换一个周期戳再计一次全勤; 领奖次数仍受当天上限约束");
        } finally {
            logout(server, player);
        }
        helper.succeed();
    }

    /** "神射手": 走完四个阶段的那次领奖满足 chain_finished; 上线追溯经 chainFinished 补上, 没走完的玩家补不上。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marksmanChainCompletesLiveAndBackfillsSilently(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService previous = swapEconomy(new EconomyService(ledger, new AbuseGuard(), states()));
        List<ServerPlayer> online = new ArrayList<>();
        try {
            ServerPlayer sniper = logged(helper, online, "p2s-sniper");
            ServerPlayer rookie = logged(helper, online, "p2s-rookie");
            QuestService quests = new QuestService(QuestPool.builtin());
            QuestChain chain = quests.pool().chain(QuestPool.CHAIN_MARKSMAN);
            Advancement live = watch(sniper, AchievementTriggers.QUEST_COMPLETE,
                    QuestCompleteTrigger.TriggerInstance.chainFinished(QuestPool.CHAIN_MARKSMAN), "marksman_live");
            Zombie victim = Objects.requireNonNull(EntityType.ZOMBIE.create(helper.getLevel()));
            helper.assertTrue(quests.unlockChain(sniper, QuestPool.CHAIN_MARKSMAN), "前提: 任务线应解锁");
            for (int stage = 0; stage < chain.stageCount(); stage++) {
                QuestDefinition definition = chain.stages().get(stage);
                for (int i = 0; i < definition.objective().requiredCount(); i++) {
                    quests.record(new QuestFacts.GunKill(sniper, victim, TEST_GUN, QuestPool.GUN_TYPE_SNIPER, true,
                            100.0D, 30.0F));
                }
                helper.assertTrue(quests.claim(sniper, definition.id()).outcome()
                        == QuestService.ClaimOutcome.CLAIMED, "第 " + (stage + 1) + " 阶段应可领取");
                helper.assertTrue(met(sniper, live) == (stage == chain.stageCount() - 1),
                        "只有走完第四阶段的那次领奖才满足 chain_finished");
            }
            helper.assertTrue(QuestServices.service().chainFinished(server, sniper.getUUID(), QuestPool.CHAIN_MARKSMAN)
                            && !QuestServices.service().chainFinished(server, rookie.getUUID(), QuestPool.CHAIN_MARKSMAN),
                    "只读接口 chainFinished 应分辨走完与没解锁的玩家");

            Advancement sniperLater = watch(sniper, AchievementTriggers.QUEST_COMPLETE,
                    QuestCompleteTrigger.TriggerInstance.chainFinished(QuestPool.CHAIN_MARKSMAN), "marksman_backfill");
            Advancement rookieLater = watch(rookie, AchievementTriggers.QUEST_COMPLETE,
                    QuestCompleteTrigger.TriggerInstance.chainFinished(QuestPool.CHAIN_MARKSMAN), "marksman_backfill");
            AchievementBackfill.runSilently(sniper);
            AchievementBackfill.runSilently(rookie);
            helper.assertTrue(met(sniper, sniperLater) && !met(rookie, rookieLater),
                    "上线追溯只给走完任务线的玩家补上神射手");
        } finally {
            online.forEach(player -> logout(server, player));
            restoreEconomy(previous);
            MiningDb.close(ledger.connection());
        }
        helper.succeed();
    }

    // ---- 10. 监听接口: 婚姻 ----

    /**
     * 婚礼成功时对双方各通知一次, "执子之手"当场到手, 失败的婚礼不通知; 传送完成时带上蓄力开始时的水平距离通知,
     * 999 格不算、1500 格算"千里赴约" (只给传送的一方); 监听器抛出不影响婚礼与传送。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void weddingAndTeleportSeamsFireOnceWhenDone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService economy = new EconomyService(ledger, new AbuseGuard(), states());
        IEconomyService previous = swapEconomy(economy);
        List<ServerPlayer> online = new ArrayList<>();
        long marriageId = IMiningPlayerData.NO_MARRIAGE;
        try {
            ServerPlayer groom = logged(helper, online, "p2s-groom");
            ServerPlayer bride = logged(helper, online, "p2s-bride");
            economy.grant(groom, Currency.CREDIT, 1_000_000L);
            economy.grant(bride, Currency.CREDIT, 1_000_000L);
            bride.absMoveTo(groom.getX(), groom.getY(), groom.getZ());
            MarriageEngine engine = new MarriageEngine(overworld);

            Probe.arm(false, null);
            helper.assertTrue(engine.wed(groom, bride, 20_000L, null).reason() == MarriageEngine.Reason.NO_ENGAGEMENT_RING
                    && Probe.events(Wedding.class).isEmpty(), "没有订婚戒指的婚礼失败, 不通知");
            groom.getInventory().add(RingItem.createEngagement(ModItems.ENGAGEMENT_RING.get()));
            bride.getInventory().add(RingItem.createEngagement(ModItems.ENGAGEMENT_RING.get()));
            MarriageEngine.WeddingResult wedding = engine.wed(groom, bride, 20_000L, null);
            marriageId = wedding.marriageId();
            helper.assertTrue(wedding.success() && Probe.events(Wedding.class).equals(List.of(
                            new Wedding(groom.getUUID(), bride.getUUID()), new Wedding(bride.getUUID(), groom.getUUID()))),
                    "婚礼成功应对双方各通知一次, 实为 " + Probe.events(Wedding.class));
            assertDone(helper, groom, "social/married", true);
            assertDone(helper, bride, "social/married", true);

            bride.absMoveTo(groom.getX() + 999.0D, groom.getY(), groom.getZ());
            completeTeleport(helper, overworld, groom);
            helper.assertTrue(Probe.events(Teleport.class).size() == 1
                            && Math.abs(Probe.events(Teleport.class).get(0).distance() - 999.0D) < 1.0E-6,
                    "传送完成应通知一次并带上蓄力开始时的水平距离 999, 实为 " + Probe.events(Teleport.class));
            assertDone(helper, groom, "social/long_distance", false);

            bride.absMoveTo(groom.getX() + 1_500.0D, groom.getY(), groom.getZ());
            MarriageTeleport cancelled = new MarriageTeleport();
            helper.assertTrue(cancelled.tryStart(groom, overworld) == MarriageTeleport.StartResult.STARTED,
                    "前提: 蓄力应开始");
            groom.absMoveTo(groom.getX() + 3.0D, groom.getY(), groom.getZ());
            cancelled.tick(overworld);
            helper.assertTrue(!cancelled.isChanneling(groom.getUUID()) && Probe.events(Teleport.class).size() == 1,
                    "被移动打断的传送不通知");
            bride.absMoveTo(groom.getX() + 1_500.0D, groom.getY(), groom.getZ());
            completeTeleport(helper, overworld, groom);
            helper.assertTrue(Probe.events(Teleport.class).size() == 2, "第二次传送应再通知一次");
            assertDone(helper, groom, "social/long_distance", true);
            assertDone(helper, bride, "social/long_distance", false);

            Probe.arm(true, null);
            bride.absMoveTo(groom.getX() + 10.0D, groom.getY(), groom.getZ());
            completeTeleport(helper, overworld, groom);
            helper.assertTrue(Math.abs(groom.getX() - bride.getX()) < 1.0E-6 && Probe.events(Teleport.class).size() == 1,
                    "监听器抛出不影响传送");
        } finally {
            Probe.disarm();
            if (marriageId != IMiningPlayerData.NO_MARRIAGE) {
                MarriageRegistry.get(overworld).dissolve(marriageId);
            }
            for (ServerPlayer player : online) {
                setMarriage(player, IMiningPlayerData.NO_MARRIAGE, null);
                logout(server, player);
            }
            restoreEconomy(previous);
            MiningDb.close(ledger.connection());
        }
        helper.succeed();
    }

    // ---- 11. 成就数量 ----

    /** 第 25 个计数成就到手时授予 meta/count_25; meta 成就自己不计数; count_40 在 39 与 40 两侧。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void metaCountsOpenAtTwentyFiveAndForty(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper, profile("p2s-meta"));
        try {
            AchievementCatalog catalog = AchievementServices.catalog();
            List<ResourceLocation> countable = new ArrayList<>(catalog.countableIds());
            helper.assertTrue(countable.size() >= 25, "前提: 计数池应至少有 25 条, 实为 " + countable.size());
            for (ResourceLocation id : countable.subList(0, 24)) {
                grantFully(server, player, id);
            }
            assertDone(helper, player, "meta/count_10", true);
            assertDone(helper, player, "meta/count_25", false);
            grantFully(server, player, countable.get(24));
            assertDone(helper, player, "meta/count_25", true);
            helper.assertTrue(catalog.countEarned(player.getAdvancements()) == 25, "meta 成就自己不计入成就数量");
            AchievementTriggers.ACHIEVEMENT_COUNT.trigger(player, 39);
            assertDone(helper, player, "meta/count_40", false);
            AchievementTriggers.ACHIEVEMENT_COUNT.trigger(player, 40);
            assertDone(helper, player, "meta/count_40", true);
        } finally {
            logout(server, player);
        }
        helper.succeed();
    }

    // ---- 12. 静默追溯 ----

    /**
     * 上线追溯静默授予: 不做全服公告, 客户端收到的只有重置包 (不弹 Toast), 照常生成待领取奖励与本人的领取提示; 重复执行
     * 不重复发。玩家的第一个进度包之前 (登录) 与之后两种时机都要成立; 对照组走普通授予, 公告与增量包都在。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 100)
    public static void backfillGrantsSilentlyYetCreatesPendingRewards(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ResourceLocation gold = AchievementIds.id("economy/income_1500k");
        List<ResourceLocation> rewarded = List.of(AchievementIds.id("economy/first_paycheck"),
                AchievementIds.id("economy/income_300k"), gold);
        List<ServerPlayer> online = new ArrayList<>();
        ServerPlayer early = logged(helper, online, "p2s-silent-a");
        ServerPlayer loud = logged(helper, online, "p2s-loud-b");
        ServerPlayer late = logged(helper, online, "p2s-silent-c");
        ServerPlayer lateLoud = logged(helper, online, "p2s-loud-d");
        try {
            setCreditsEarned(early, 1_600_000);
            setCreditsEarned(loud, 1_600_000);
            AchievementBackfill.runSilently(early);
            AchievementTriggers.STAT_AT_LEAST.trigger(loud);
            assertDone(helper, early, gold.getPath(), true);
            assertDone(helper, loud, gold.getPath(), true);
            List<Object> earlyPackets = drainOutbound(early);
            helper.assertTrue(announcements(earlyPackets, "p2s-silent-a").isEmpty(),
                    "静默授予不能向任何人公告, 实为 " + announcements(earlyPackets, "p2s-silent-a"));
            helper.assertTrue(announcements(drainOutbound(loud), "p2s-loud-b").stream()
                            .anyMatch(keys -> keys.contains(AchievementIds.titleKey(gold))),
                    "对照组普通授予应有金档的全服公告");
            helper.assertTrue(earnedNotices(earlyPackets) == 3 && pendingIds(early).containsAll(rewarded),
                    "静默授予照常生成待领取奖励并给本人发领取提示");
        } catch (RuntimeException failure) {
            online.forEach(player -> logout(server, player));
            throw failure;
        }
        // 两步都在此刻排好 (回调里再排会改动测试框架正在遍历的表); 前一步失败时测试已结束, 后一步不会再跑。
        helper.runAfterDelay(3, () -> {
            try {
                List<Object> earlyPackets = drainOutbound(early);
                helper.assertTrue(advancementPackets(earlyPackets, gold, true) >= 1
                                && advancementPackets(earlyPackets, gold, false) == 0,
                        "登录前补出的成就只能随重置包下发, 不能出现在会弹 Toast 的增量包里");
                int pending = pendingIds(early).size();
                AchievementBackfill.runSilently(early);
                helper.assertTrue(pendingIds(early).size() == pending && earnedNotices(drainOutbound(early)) == 0,
                        "重复追溯不重复生成奖励、不再提示");

                drainOutbound(late);
                drainOutbound(lateLoud);
                setCreditsEarned(late, 1_600_000);
                setCreditsEarned(lateLoud, 1_600_000);
                AchievementBackfill.runSilently(late);
                AchievementTriggers.STAT_AT_LEAST.trigger(lateLoud);
                helper.assertTrue(announcements(drainOutbound(late), "p2s-silent-c").isEmpty(),
                        "第一个进度包之后的静默授予同样不公告");
            } catch (RuntimeException failure) {
                online.forEach(player -> logout(server, player));
                throw failure;
            }
        });
        helper.runAfterDelay(6, () -> {
            try {
                List<Object> latePackets = drainOutbound(late);
                helper.assertTrue(advancementPackets(latePackets, gold, true) >= 1
                                && advancementPackets(latePackets, gold, false) == 0,
                        "第一个进度包之后的静默授予改发重置包, 不发会弹 Toast 的增量包");
                helper.assertTrue(resetPacketsAdding(latePackets, AchievementIds.root("mining")) >= 1,
                        "重置包必须重新带上已可见的进度 (页签根), 客户端才不会丢掉整棵树");
                helper.assertTrue(pendingIds(late).containsAll(rewarded), "第一个进度包之后的静默授予同样生成奖励");
                List<Object> loudPackets = drainOutbound(lateLoud);
                helper.assertTrue(advancementPackets(loudPackets, gold, false) >= 1
                                && advancementPackets(loudPackets, gold, true) == 0,
                        "对照组普通授予走增量包 (会弹 Toast), 说明抑制只作用于静默授予");
                helper.succeed();
            } finally {
                online.forEach(player -> logout(server, player));
            }
        });
    }

    // ---- 工具 ----

    /** 第四章一档的规格。 */
    private record TierSpec(FrameType frame, boolean announce, int points) {
    }

    /** 规格表的一行。 */
    private static final class Expected {
        final String path;
        final String tier;
        final String parent;
        final ResourceLocation icon;
        final String trigger;
        final String conditions;
        boolean hidden;
        boolean titled;
        boolean tacz;

        Expected(String path, String tier, String parent, String icon, String trigger, String conditions) {
            this.path = path;
            this.tier = tier;
            this.parent = parent;
            this.icon = new ResourceLocation(icon);
            this.trigger = trigger;
            this.conditions = conditions;
        }

        Expected hidden() {
            this.hidden = true;
            return this;
        }

        Expected titled() {
            this.titled = true;
            return this;
        }

        Expected tacz() {
            this.tacz = true;
            return this;
        }
    }

    private static Expected row(String path, String tier, String parent, String icon, String trigger,
                                String conditions) {
        return new Expected(path, tier, parent, icon, trigger, conditions);
    }

    /** 探针记下的 faucet 入账。 */
    private record Faucet(UUID player, String key, long credited) {
    }

    /** 探针记下的婚礼通知。 */
    private record Wedding(UUID player, UUID spouse) {
    }

    /** 探针记下的传送通知。 */
    private record Teleport(UUID traveller, double distance) {
    }

    /** 回滚信号。 */
    private static final class SimulatedFailure extends RuntimeException {
        SimulatedFailure() {
            super("simulated failure", null, false, false);
        }
    }

    /**
     * 挂在六个监听接口上的测试探针。监听器列表随进程存活、没有注销入口, 所以整个进程只注册一次, 平时什么都不做;
     * 用例在自己的同步代码里布防 ({@link #arm})、在 finally 里撤防。同一 batch 的用例函数在服务端主线程上依次执行,
     * 布防期间收到的只会是本用例自己触发的通知。探针排在成就的监听器之后注册, 布防成"抛出"时, 成就侧已经处理完这次通知。
     */
    private static final class Probe {

        private static final List<Object> EVENTS = new ArrayList<>();
        private static final List<Boolean> INSIDE_TRANSACTION = new ArrayList<>();
        private static boolean installed;
        private static boolean armed;
        private static boolean throwing;
        @Nullable
        private static Connection watched;

        private Probe() {
        }

        /** 清空记录并布防; throwOnEvent 为 true 时每次通知都抛出; watchedConnection 非空时记下通知那一刻它是否在事务中。 */
        static void arm(boolean throwOnEvent, @Nullable Connection watchedConnection) {
            install();
            EVENTS.clear();
            INSIDE_TRANSACTION.clear();
            throwing = throwOnEvent;
            watched = watchedConnection;
            armed = true;
        }

        static void disarm() {
            armed = false;
            throwing = false;
            watched = null;
        }

        static <T> List<T> events(Class<T> type) {
            return EVENTS.stream().filter(type::isInstance).map(type::cast).toList();
        }

        static List<Boolean> firedInsideTransaction() {
            return List.copyOf(INSIDE_TRANSACTION);
        }

        private static void seen(Object event) {
            if (!armed) {
                return;
            }
            EVENTS.add(event);
            if (watched != null) {
                INSIDE_TRANSACTION.add(!autoCommit(watched));
            }
            if (throwing) {
                throw new IllegalStateException("probe listener failure injected by the test");
            }
        }

        private static void install() {
            if (installed) {
                return;
            }
            installed = true;
            EconomyServices.registerFaucetListener((player, key, credited) ->
                    seen(new Faucet(player.getUUID(), key, credited)));
            MarketServices.registerTradeListener(Probe::seen);
            CaseServices.registerOpeningListener(Probe::seen);
            QuestServices.registerClaimListener(Probe::seen);
            MarriageEvents.registerWeddingListener((player, spouse) ->
                    seen(new Wedding(player.getUUID(), spouse.getUUID())));
            MarriageEvents.registerTeleportListener((traveller, spouse, distance) ->
                    seen(new Teleport(traveller.getUUID(), distance)));
        }
    }

    /** 远端地址固定为给定 IP 的嵌入式通道: 市场同 IP 判定读的正是连接的远端地址。 */
    private static final class AddressedChannel extends EmbeddedChannel {

        @Nullable
        private SocketAddress remote;

        @Override
        protected SocketAddress remoteAddress0() {
            return remote;
        }
    }

    private static GameProfile profile(String name) {
        return new GameProfile(UUID.randomUUID(), name);
    }

    /** 登录一个普通的 mock 玩家 (测试连接没有 IP)。 */
    private static ServerPlayer logged(GameTestHelper helper, List<ServerPlayer> online, String name) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper, profile(name));
        online.add(player);
        return player;
    }

    /**
     * 以给定 IP 登录一个 mock 玩家。与 {@link MockGameTestPlayers} 相同地走真实的 placeNewPlayer, 只是连接的通道报告
     * 一个网络地址: 先让通道激活, 设好地址后再挂上 Connection 并补发 channelActive, Connection 据此记下远端地址。
     */
    private static ServerPlayer login(GameTestHelper helper, List<ServerPlayer> online, GameProfile profile,
                                      String ip) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(PacketFlow.SERVERBOUND);
        AddressedChannel channel = new AddressedChannel();
        channel.remote = new InetSocketAddress(ip, 25565);
        channel.pipeline().addLast(connection);
        channel.pipeline().fireChannelActive();
        level.getServer().getPlayerList().placeNewPlayer(connection, player);
        online.add(player);
        return player;
    }

    private static void logout(MinecraftServer server, ServerPlayer player) {
        if (server.getPlayerList().getPlayer(player.getUUID()) == player) {
            server.getPlayerList().remove(player);
        }
    }

    private static void setMarriage(ServerPlayer player, long marriageId, @Nullable UUID spouse) {
        IMiningPlayerData data = MiningCapabilities.get(player).orElseThrow(
                () -> new IllegalStateException("mock player has no mining capability"));
        data.setMarriageId(marriageId);
        data.setSpouseUUID(spouse);
    }

    /** 卖家挂一单圆石 (无基准价, 按平率收挂单费), 返回挂单 id。 */
    private static long list(MarketEngine engine, ServerPlayer seller, long unitPrice, int count) {
        seller.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, count));
        return engine.place(seller, 0, count, unitPrice, "CREDIT").listingId();
    }

    private static void buy(MarketEngine engine, ServerPlayer buyer, long listingId) {
        engine.buy(buyer, listingId, 0);
    }

    private static CaseOpeningService caseService(CaseDaoSqlite dao, CaseRoller.BoundedRandom roll) {
        return new CaseOpeningService(dao, new EconomyCaseOperations(), new CaseRoller(roll), () -> true, () -> true,
                () -> true, () -> 50_000L, () -> 10L, () -> CaseWeights.DEFAULT, () -> 20);
    }

    /** 真实适配器, 只让最后的终态推进抛出 (事务整体回滚)。 */
    private static CaseEconomyOperations completionFails(CaseEconomyOperations delegate) {
        return new CaseEconomyOperations() {
            @Override
            public <T> T inTransaction(Supplier<T> body) {
                return delegate.inTransaction(body);
            }

            @Override
            public void afterCommit(Runnable action) {
                delegate.afterCommit(action);
            }

            @Override
            public long creditBalance(ServerPlayer player) {
                return delegate.creditBalance(player);
            }

            @Override
            public long azureBalance(ServerPlayer player) {
                return delegate.azureBalance(player);
            }

            @Override
            public boolean charge(ServerPlayer player, UUID operationId, long creditCost, long azureCost) {
                return delegate.charge(player, operationId, creditCost, azureCost);
            }

            @Override
            public State state(UUID playerId, UUID operationId) {
                return delegate.state(playerId, operationId);
            }

            @Override
            public State complete(UUID playerId, UUID operationId) {
                throw new IllegalStateException("completion failure injected by the test: " + operationId);
            }

            @Override
            public State refund(UUID playerId, UUID operationId) {
                return delegate.refund(playerId, operationId);
            }
        };
    }

    /** 一行已提交 (皮肤已发) 但结算锚未落的开箱, 返回开箱 id; 账本那一侧由调用方决定。 */
    private static UUID committedButUnsettled(CaseDaoSqlite dao, UUID owner, long now) {
        UUID openingId = UUID.randomUUID();
        CaseSkin skin = CaseCatalog.requireSkin("arctic_grid");
        CaseOpeningRow reserved = dao.reserve(new CaseOpeningRow(openingId, owner, CaseCatalog.CASE_ID, 50_000L, 10L,
                CaseOpeningStatus.RESERVED, UUID.randomUUID(), skin.skinId(), skin.rarity(), skin.gunId().toString(),
                skin.displayId().toString(), "[\"arctic_grid\"]", 0, now, now));
        dao.markDebited(openingId, now + 1L);
        dao.commitOpening(openingId, new SkinAssetRow(reserved.assetId(), owner, reserved.skinId(), reserved.rarity(),
                reserved.gunId(), reserved.displayId(), openingId, now, 0L), now + 2L);
        return openingId;
    }

    /** 从开始蓄力一路推进到传送完成。 */
    private static void completeTeleport(GameTestHelper helper, ServerLevel overworld, ServerPlayer initiator) {
        MarriageTeleport teleport = new MarriageTeleport();
        helper.assertTrue(teleport.tryStart(initiator, overworld) == MarriageTeleport.StartResult.STARTED,
                "前提: 传送蓄力应开始");
        for (int tick = 0; tick < 72_000 && teleport.isChanneling(initiator.getUUID()); tick++) {
            teleport.tick(overworld);
        }
        helper.assertTrue(!teleport.isChanneling(initiator.getUUID()), "蓄力应走完");
    }

    /**
     * 把条件挂成一条临时进度: 与原版登记进度监听的方式相同 (addPlayerListener), 条件满足时原版照常授予并摘除监听。
     * 用来核对依赖 TaCZ、开发环境里不加载的那几条成就的条件。
     */
    private static <T extends AbstractCriterionTriggerInstance> Advancement watch(ServerPlayer player,
                                                                              SimpleCriterionTrigger<T> trigger,
                                                                              T instance, String name) {
        Advancement advancement = Advancement.Builder.advancement().addCriterion("met", instance)
                .build(AchievementIds.id("test/p2_social/" + name));
        trigger.addPlayerListener(player.getAdvancements(), new CriterionTrigger.Listener<>(instance, advancement,
                "met"));
        return advancement;
    }

    private static boolean met(ServerPlayer player, Advancement advancement) {
        return player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private static void grantFully(MinecraftServer server, ServerPlayer player, ResourceLocation id) {
        Advancement advancement = Objects.requireNonNull(server.getAdvancements().getAdvancement(id), id::toString);
        for (String criterion : advancement.getCriteria().keySet()) {
            player.getAdvancements().award(advancement, criterion);
        }
    }

    private static boolean done(ServerPlayer player, String path) {
        Advancement advancement = player.getServer().getAdvancements().getAdvancement(AchievementIds.id(path));
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private static void assertDone(GameTestHelper helper, ServerPlayer player, String path, boolean expected) {
        helper.assertTrue(player.getServer().getAdvancements().getAdvancement(AchievementIds.id(path)) != null,
                "进度未加载: " + path);
        helper.assertTrue(done(player, path) == expected, player.getGameProfile().getName() + ": " + path
                + (expected ? " 此时应已获得" : " 此时不应获得"));
    }

    private static int stat(ServerPlayer player, RegistryObject<ResourceLocation> stat) {
        return player.getStats().getValue(Stats.CUSTOM.get(stat.get()));
    }

    /** 直接改统计值, 不触发阈值核对 (模拟"数据已在、成就还没补"的历史状态)。 */
    private static void setCreditsEarned(ServerPlayer player, int value) {
        player.getStats().setValue(player, Stats.CUSTOM.get(AchievementStats.CREDITS_EARNED.get()), value);
    }

    private static List<ResourceLocation> pendingIds(ServerPlayer player) {
        return AchievementServices.rewards().pending(player.getUUID()).stream()
                .map(AchievementReward::advancementId).toList();
    }

    /** 读空 mock 连接的出站队列, 按顺序返回其中的封包。 */
    private static List<Object> drainOutbound(ServerPlayer player) {
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        List<Object> packets = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            packets.add(outbound);
            ReferenceCountUtil.release(outbound);
        }
        return packets;
    }

    /** 点名某玩家的进度公告 (原版 chat.type.advancement.*), 每条返回其中出现的全部翻译键。 */
    private static List<List<String>> announcements(List<Object> packets, String playerName) {
        List<List<String>> found = new ArrayList<>();
        for (Object packet : packets) {
            if (packet instanceof ClientboundSystemChatPacket chat
                    && chat.content().getContents() instanceof TranslatableContents translatable
                    && translatable.getKey().startsWith("chat.type.advancement.")
                    && translatable.getArgs().length == 2
                    && translatable.getArgs()[0] instanceof Component who
                    && who.getString().contains(playerName)) {
                List<String> keys = new ArrayList<>();
                collectKeys(chat.content(), keys);
                found.add(keys);
            }
        }
        return found;
    }

    /** 本人收到的"奖励待领取"提示条数。 */
    private static int earnedNotices(List<Object> packets) {
        int count = 0;
        for (Object packet : packets) {
            if (packet instanceof ClientboundSystemChatPacket chat
                    && chat.content().getContents() instanceof TranslatableContents translatable
                    && translatable.getKey().equals("achievement.miningdim.reward.earned")) {
                count++;
            }
        }
        return count;
    }

    /** 带着"该进度已完成"下发的进度包数量; reset 选重置包或增量包 (客户端只对增量包里完成的进度弹 Toast)。 */
    private static int advancementPackets(List<Object> packets, ResourceLocation id, boolean reset) {
        int count = 0;
        for (Object packet : packets) {
            if (packet instanceof ClientboundUpdateAdvancementsPacket update && update.shouldReset() == reset
                    && update.getProgress().containsKey(id) && update.getProgress().get(id).isDone()) {
                count++;
            }
        }
        return count;
    }

    private static int resetPacketsAdding(List<Object> packets, ResourceLocation id) {
        int count = 0;
        for (Object packet : packets) {
            if (packet instanceof ClientboundUpdateAdvancementsPacket update && update.shouldReset()
                    && update.getAdded().containsKey(id)) {
                count++;
            }
        }
        return count;
    }

    private static void collectKeys(Component component, List<String> keys) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            keys.add(translatable.getKey());
            for (Object argument : translatable.getArgs()) {
                if (argument instanceof Component nested) {
                    collectKeys(nested, keys);
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            collectKeys(sibling, keys);
        }
    }

    private static <T extends CriterionTriggerInstance> JsonObject roundTrip(GameTestHelper helper,
                                                                            DeserializationContext context,
                                                                            CriterionTrigger<T> trigger,
                                                                            CriterionTriggerInstance instance) {
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

    private static IEconomyService swapEconomy(IEconomyService replacement) {
        IEconomyService previous = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(replacement);
        return previous;
    }

    private static void restoreEconomy(@Nullable IEconomyService previous) {
        if (previous == null) {
            EconomyServices.reset();
        } else {
            EconomyServices.registerEconomyService(previous);
        }
    }

    private static Function<UUID, PlayerAbuseState> states() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return id -> states.computeIfAbsent(id, ignored -> new PlayerAbuseState());
    }

    private static boolean autoCommit(Connection connection) {
        try {
            return connection.getAutoCommit();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void execute(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int countRows(Connection connection) {
        try (Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM probe")) {
            return result.next() ? result.getInt(1) : -1;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean hasText(JsonObject lang, String key) {
        return lang.has(key) && lang.get(key).isJsonPrimitive() && !lang.get(key).getAsString().isBlank();
    }

    private static JsonObject readLang(String language) {
        String path = "assets/" + MiningConstants.MODID + "/lang/" + language + ".json";
        InputStream stream = AchievementP2SocialGameTests.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError("运行时 classpath 找不到资源: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return GsonHelper.parse(reader);
        } catch (IOException exception) {
            throw new AssertionError("读不了语言文件 " + path, exception);
        }
    }
}
