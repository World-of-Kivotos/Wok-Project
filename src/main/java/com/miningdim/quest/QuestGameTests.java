package com.miningdim.quest;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.quest.objective.AmmoSpentObjective;
import com.miningdim.quest.objective.GunKillObjective;
import com.miningdim.quest.objective.KillEntityObjective;
import com.miningdim.quest.objective.MineBlockObjective;
import com.miningdim.quest.objective.VillagerTradeObjective;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 任务系统 GameTest。
 *
 * 断言原则: 每条用例都锚定一个<b>可被删掉的实现</b>, 删了就必须红。逐条对应关系写在各方法的注释里, 便于日后
 * 判断某条断言是不是已经退化成"永远通过"。
 *
 * 三块重点:
 *  1. 判据层 —— 枪械三道闸 (枪型/爆头/距离) 与标签匹配, 这是任务能不能正确计数的根;
 *  2. 周期层 —— 翻日翻周只在戳变化时重抽, 且重抽是"换新任务"不是"清计数";
 *  3. 经济层 —— 发奖必须走全服共享的 credit_faucet 键 (私有 faucet 键 = 绕开统一软上限的印钞口),
 *     且一条任务的奖励只发一次。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class QuestGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "quest";

    private static final String SNIPER = QuestPool.GUN_TYPE_SNIPER;
    private static final ResourceLocation TEST_GUN = new ResourceLocation("tacz", "test_sniper");

    // ============================================================
    // 1. 时钟: ISO 周戳
    // ============================================================

    /**
     * 同一 ISO 周内 (周一到周日) 周戳恒定, 跨到下周一才变, 且单调递增。
     *
     * 把 {@code QuestClock.isoWeekStampOf} 换成按 epochDay/7 的朴素实现, 本条会在"周一到周日同戳"上挂
     * —— epochDay 0 是周四, 除 7 的分界落在周四而非周一。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questClockWeekStampIsStableInsideWeekAndRollsOnMonday(GameTestHelper helper) {
        LocalDate monday = LocalDate.of(2026, 8, 12).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long mondayStamp = QuestClock.isoWeekStampOf(monday.toEpochDay());
        long sundayStamp = QuestClock.isoWeekStampOf(monday.plusDays(6).toEpochDay());
        long nextMondayStamp = QuestClock.isoWeekStampOf(monday.plusDays(7).toEpochDay());

        helper.assertTrue(monday.getDayOfWeek() == DayOfWeek.MONDAY, "取到的基准日必须是周一");
        helper.assertTrue(mondayStamp == sundayStamp,
                "同一 ISO 周内周戳必须恒定, 实得周一 " + mondayStamp + " / 周日 " + sundayStamp);
        helper.assertTrue(nextMondayStamp != mondayStamp,
                "跨到下周一周戳必须变化, 实得同为 " + mondayStamp);
        helper.assertTrue(nextMondayStamp > mondayStamp,
                "周戳必须单调递增才能直接比较, 实得 " + mondayStamp + " -> " + nextMondayStamp);
        helper.succeed();
    }

    /**
     * 跨年周按 ISO 周历归属: 2024-12-30 (周一) 属于 2025 年第 1 周, 与 2025-01-01 同戳。
     *
     * 这正是自造周历必错的那一格 —— 按自然年拆会把这一周劈成两半, 让玩家在周一到周三之间被重置两次。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questClockWeekStampFollowsIsoCrossYearWeek(GameTestHelper helper) {
        long dec30 = QuestClock.isoWeekStampOf(LocalDate.of(2024, 12, 30).toEpochDay());
        long jan01 = QuestClock.isoWeekStampOf(LocalDate.of(2025, 1, 1).toEpochDay());

        helper.assertTrue(dec30 == jan01,
                "2024-12-30 与 2025-01-01 同属一个 ISO 周, 实得 " + dec30 + " / " + jan01);
        helper.assertTrue(dec30 == 202501L,
                "该周应编码为 2025 年第 1 周 (202501), 实得 " + dec30);
        helper.succeed();
    }

    // ============================================================
    // 2. 判据层
    // ============================================================

    /**
     * 枪械目标的三道闸各自独立生效: 枪型不符 / 未爆头 / 距离不足 都不计, 三者同时满足才计 1。
     *
     * 删掉 {@code GunKillObjective.match} 里任意一道闸, 本条都会在对应的那一行挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gunKillObjectiveEnforcesTypeHeadshotAndDistanceGates(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        LivingEntity victim = spawnZombie(helper);
        GunKillObjective objective = GunKillObjective.longRangeHeadshot(SNIPER, 50, 2);

        helper.assertTrue(objective.match(gunKill(player, victim, SNIPER, true, 60)) == 1,
                "枪型/爆头/距离全满足必须计 1");
        helper.assertTrue(objective.match(gunKill(player, victim, "rifle", true, 60)) == 0,
                "枪型不符不得计入");
        helper.assertTrue(objective.match(gunKill(player, victim, SNIPER, false, 60)) == 0,
                "未爆头不得计入");
        helper.assertTrue(objective.match(gunKill(player, victim, SNIPER, true, 49.9)) == 0,
                "距离不足不得计入");
        helper.assertTrue(objective.match(gunKill(player, victim, SNIPER, true, 50)) == 1,
                "距离恰好等于下限必须计入 (闸是 >=, 不是 >)");
        helper.succeed();
    }

    /**
     * TaCZ 资源索引解析不出枪械分类 (gunType 为 null) 时: 限定枪型的目标一律不计, 不限枪型的目标照常计。
     *
     * 这是"未知"与"无"的区别。把 {@code match} 里的 null 处理换成 {@code gunType.equalsIgnoreCase(...)}
     * 会直接 NPE; 换成"未知就放行"则第一行断言挂 —— 那意味着玩家拿任何一把索引缺失的枪都能刷狙击任务。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gunKillObjectiveRejectsUnresolvedGunTypeOnlyWhenTypeIsRequired(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        LivingEntity victim = spawnZombie(helper);

        GunKillObjective typed = GunKillObjective.ofType(SNIPER, 1);
        GunKillObjective anyGun = new GunKillObjective(null, false, 0, 1);
        QuestFacts unresolved = gunKill(player, victim, null, false, 10);

        helper.assertTrue(typed.match(unresolved) == 0, "枪型未知时限定枪型的目标不得计入");
        helper.assertTrue(anyGun.match(unresolved) == 1, "枪型未知不该让'用枪械击杀'这条事实消失");
        helper.succeed();
    }

    /**
     * 方块目标按标签匹配, 因此深层变体同样计数, 而标签外的方块不计。
     *
     * 把 {@code MineBlockObjective} 的判据从标签换成单个方块, 第二行 (深层铁矿) 立刻挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mineBlockObjectiveMatchesTagVariantsOnly(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MineBlockObjective objective = new MineBlockObjective(BlockTags.IRON_ORES, "铁矿石", 3);

        helper.assertTrue(objective.match(
                new QuestFacts.BlockMine(player, Blocks.IRON_ORE.defaultBlockState())) == 1, "铁矿必须计入");
        helper.assertTrue(objective.match(
                        new QuestFacts.BlockMine(player, Blocks.DEEPSLATE_IRON_ORE.defaultBlockState())) == 1,
                "深层铁矿属同一标签, 必须计入");
        helper.assertTrue(objective.match(
                new QuestFacts.BlockMine(player, Blocks.STONE.defaultBlockState())) == 0, "石头不得计入");
        helper.succeed();
    }

    /**
     * 生物击杀目标按精确 EntityType 比对, 不做父类归并: 打尸壳不算打僵尸。
     *
     * 换成 {@code instanceof Zombie} 之类的归并判据, 第二行立刻挂 (尸壳继承自僵尸)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void killEntityObjectiveRequiresExactEntityType(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        KillEntityObjective objective = new KillEntityObjective(EntityType.ZOMBIE, 5);
        LivingEntity zombie = spawnZombie(helper);
        LivingEntity husk = requireEntity(helper, EntityType.HUSK.create(helper.getLevel()), "husk");

        helper.assertTrue(objective.match(new QuestFacts.EntityKill(player, zombie)) == 1, "僵尸必须计入");
        helper.assertTrue(objective.match(new QuestFacts.EntityKill(player, husk)) == 0,
                "尸壳是僵尸的子类型, 但不是同一个 EntityType, 不得计入");
        helper.succeed();
    }

    // ============================================================
    // 3. 进度与任务板
    // ============================================================

    /**
     * 进度达标后封顶不再增长, 领取标记至多翻一次。
     *
     * 删掉 {@code QuestProgress.record} 的 {@code isComplete()} 短路, 第三行 (封顶) 挂;
     * 删掉 {@code tryClaim} 的 {@code claimed} 判据, 最后一行 (二次领取) 挂 —— 那正是重复发奖的入口。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questProgressCapsAtRequiredCountAndClaimsExactlyOnce(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        Villager villager = (Villager) requireEntity(helper, EntityType.VILLAGER.create(helper.getLevel()), "villager");
        QuestDefinition definition = new QuestDefinition("test.trade.twice", QuestSource.SPECIAL,
                "测试用两次交易", new VillagerTradeObjective(2), 1);
        QuestProgress progress = new QuestProgress(definition);
        QuestFacts trade = new QuestFacts.VillagerTrade(player, villager);

        helper.assertTrue(progress.record(trade) && progress.count() == 1, "第一次交易应计到 1");
        helper.assertTrue(progress.record(trade) && progress.count() == 2, "第二次交易应计到 2 并达标");
        helper.assertTrue(progress.isComplete(), "计数达到 requiredCount 即达标");
        helper.assertTrue(!progress.record(trade) && progress.count() == 2,
                "达标后必须封顶, 实得计数 " + progress.count());
        helper.assertTrue(progress.tryClaim(), "首次领取应成功");
        helper.assertTrue(!progress.tryClaim(), "二次领取必须失败, 否则就是重复发奖入口");
        helper.succeed();
    }

    /**
     * 周期任务只在周期戳变化时重抽; 同戳内进度必须原样保留, 跨戳则整组换新且计数归零。
     *
     * 删掉 {@code rolloverIfStale} 里的 {@code dailyStamp != dayStamp} 判据, 第一段 (同日不动) 挂 ——
     * 那意味着玩家每次打开任务板任务都被换掉, 进度永远攒不起来。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questBoardRedealsOnlyWhenPeriodStampChanges(GameTestHelper helper) {
        QuestPool pool = QuestPool.builtin();
        RandomSource random = helper.getLevel().getRandom();
        QuestBoard board = new QuestBoard();
        board.restorePeriodic(100L, List.of(new QuestProgress(pool.byId("daily.mine.iron"), 9, false)),
                200L, List.of());

        helper.assertTrue(!board.rolloverIfStale(pool, random, 100L, 200L, 4, 1, 1),
                "戳未变时不得重抽");
        helper.assertTrue(board.daily().size() == 1 && board.daily().get(0).count() == 9,
                "同一周期内已有进度必须原样保留");

        helper.assertTrue(board.rolloverIfStale(pool, random, 101L, 200L, 4, 1, 1), "跨日必须重抽");
        helper.assertTrue(board.daily().size() == 4,
                "重抽后应发满 4 个日常槽, 实得 " + board.daily().size());
        for (QuestProgress progress : board.daily()) {
            helper.assertTrue(progress.count() == 0, "重抽发的是新任务, 计数必须从 0 起");
        }
        helper.assertTrue(board.weeklyStamp() == 200L && board.weekly().isEmpty(),
                "周戳未变时周常不得被日常的翻转带着一起重抽");
        helper.succeed();
    }

    /**
     * 重摇只换指定槽位, 且换出来的确实是另一条任务, 其余槽位一动不动。
     *
     * 删掉 {@code refreshSlot} 里传给 {@code draw} 的排除表, 重摇就可能摇出同一条任务, 第二行会在概率上挂
     * (13 选 1 时约 8% 一次), 因此这里用"必须不同"作为硬断言而不是采样。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questBoardRefreshReplacesOnlyTheTargetSlot(GameTestHelper helper) {
        QuestPool pool = QuestPool.builtin();
        RandomSource random = helper.getLevel().getRandom();
        QuestBoard board = new QuestBoard();
        board.rolloverIfStale(pool, random, 1L, 1L, 4, 1, 1);

        List<QuestProgress> before = board.daily();
        String replacedId = before.get(1).definition().id();
        QuestProgress replacement = board.refreshSlot(QuestSource.DAILY, 1, pool, random);
        List<QuestProgress> after = board.daily();

        helper.assertTrue(!replacement.definition().id().equals(replacedId),
                "重摇必须换出另一条任务, 实得仍是 " + replacedId);
        helper.assertTrue(after.get(1).definition().id().equals(replacement.definition().id()),
                "重摇结果必须真的落回该槽位");
        helper.assertTrue(after.get(0).definition().id().equals(before.get(0).definition().id())
                        && after.get(2).definition().id().equals(before.get(2).definition().id())
                        && after.get(3).definition().id().equals(before.get(3).definition().id()),
                "重摇不得波及其它槽位");
        helper.succeed();
    }

    /**
     * 同一次事实同时推进日常与周常两条任务 —— 这是设计意图 (两条任务各自独立发奖), 不是重复计数。
     *
     * 给 {@code QuestBoard.record} 加上"命中一条就 return"的短路, 本条立刻挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questBoardFeedsOneFactToEveryMatchingQuest(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        QuestPool pool = QuestPool.builtin();
        QuestBoard board = new QuestBoard();
        board.restorePeriodic(1L, List.of(new QuestProgress(pool.byId("daily.mine.iron"))),
                1L, List.of(new QuestProgress(pool.byId("weekly.mine.iron"))));

        List<QuestProgress> changed = board.record(
                new QuestFacts.BlockMine(player, Blocks.IRON_ORE.defaultBlockState()));

        helper.assertTrue(changed.size() == 2, "一次挖矿应同时推进日常与周常, 实得 " + changed.size() + " 条");
        helper.assertTrue(board.daily().get(0).count() == 1 && board.weekly().get(0).count() == 1,
                "两条任务的计数都必须真的 +1");
        helper.succeed();
    }

    /**
     * 单次事实的增量大于剩余需求时, 计数必须夹到 requiredCount 而不是溢出。
     *
     * 当前内置的四种目标每次最多返回 1, 所以这条封顶在现有内容下够不到 —— 这条用例正是为了让它不至于成为
     * 无人守护的死分支: 将来任何一个"一次破坏多个方块"式的目标 (连锁挖矿、群体击杀) 都会立刻走到这里。
     * 把 {@code QuestProgress.record} 里的 {@code Math.min} 换成裸加法, 本条即挂 (界面会显示 5/2)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questProgressClampsOversizedIncrementToRequiredCount(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        Villager villager = (Villager) requireEntity(helper, EntityType.VILLAGER.create(helper.getLevel()), "villager");
        QuestDefinition definition = new QuestDefinition("test.bulk.increment", QuestSource.SPECIAL,
                "测试用批量增量", new BulkIncrementObjective(5, 2), 1);
        QuestProgress progress = new QuestProgress(definition);

        helper.assertTrue(progress.record(new QuestFacts.VillagerTrade(player, villager)),
                "一次增量 5 应当被计入");
        helper.assertTrue(progress.count() == 2,
                "计数必须夹到 requiredCount=2, 实得 " + progress.count());
        helper.assertTrue(progress.isComplete(), "夹取后仍应达标");
        helper.succeed();
    }

    /** 单次返回大于 1 的增量的测试用目标; 内置内容里还没有这种目标, 但接口允许 (见 {@link QuestObjective#match})。 */
    private record BulkIncrementObjective(int increment, int requiredCount) implements QuestObjective {

        @Override
        public String describe() {
            return "测试用: 每次 +" + increment;
        }

        @Override
        public int match(QuestFacts facts) {
            return facts instanceof QuestFacts.VillagerTrade ? increment : 0;
        }
    }

    // ============================================================
    // 4. 存档
    // ============================================================

    /**
     * 存档往返: 周期戳、进度计数、特殊任务冷却、任务线阶段全部原样回来。
     *
     * 任一字段漏写 save 或漏读 load, 对应断言即挂。任务线阶段尤其关键 —— 丢了阶段号玩家的整条任务线会退回
     * 第一阶段重打。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questSavedDataRoundTripsProgressChainAndCooldown(GameTestHelper helper) {
        QuestPool pool = QuestPool.builtin();
        QuestChain chain = pool.chain(QuestPool.CHAIN_MARKSMAN);
        UUID playerId = UUID.randomUUID();

        QuestSavedData data = new QuestSavedData();
        QuestBoard board = data.board(playerId);
        board.restorePeriodic(11L, List.of(new QuestProgress(pool.byId("daily.mine.iron"), 7, false)),
                22L, List.of(new QuestProgress(pool.byId("weekly.mine.diamond"), 3, false)));
        board.restoreSpecial(List.of(new QuestProgress(pool.byId("special.village.trade"), 0, false)), 12_345L);
        board.restoreChain(new QuestChainState(chain, 2, new QuestProgress(chain.stages().get(2), 1, false)));

        QuestBoard back = QuestSavedData.load(data.save(new CompoundTag()), pool).board(playerId);

        helper.assertTrue(back.dailyStamp() == 11L && back.weeklyStamp() == 22L,
                "周期戳必须原样回来, 实得 " + back.dailyStamp() + " / " + back.weeklyStamp());
        helper.assertTrue(back.daily().size() == 1 && back.daily().get(0).count() == 7,
                "日常进度计数必须原样回来");
        helper.assertTrue(back.weekly().size() == 1 && back.weekly().get(0).count() == 3,
                "周常进度计数必须原样回来");
        helper.assertTrue(back.special().size() == 1, "特殊任务必须原样回来");
        helper.assertTrue(back.lastSpecialOfferGameTime() == 12_345L,
                "特殊任务冷却时间戳必须持久化, 否则重连即可绕过冷却");

        QuestChainState state = back.chain(QuestPool.CHAIN_MARKSMAN);
        helper.assertTrue(state != null && state.stageIndex() == 2,
                "任务线阶段必须原样回来, 实得 " + (state == null ? "缺失" : state.stageIndex()));
        helper.assertTrue(state.current().definition().id().equals(chain.stages().get(2).id())
                        && state.current().count() == 1,
                "任务线当前阶段的定义与进度都必须对上");
        helper.succeed();
    }

    /**
     * 内容池里已不存在的定义 id 在加载时被丢弃, 其余进度照常加载。
     *
     * NBT 键在此用字面量而非引用 {@code QuestSavedData} 的私有常量: 键名就是磁盘格式的一部分, 改键名会让
     * 所有既有存档失配, 本条正是要在改名时红。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questSavedDataDropsUnknownDefinitionIds(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        CompoundTag boardTag = new CompoundTag();
        boardTag.putUUID("uuid", playerId);
        boardTag.putLong("dailyStamp", 5L);
        boardTag.putLong("weeklyStamp", 6L);
        boardTag.putLong("lastSpecialOffer", 0L);

        ListTag daily = new ListTag();
        daily.add(progressTag("daily.mine.iron", 4));
        daily.add(progressTag("daily.quest.that.was.deleted", 9));
        boardTag.put("daily", daily);

        ListTag boards = new ListTag();
        boards.add(boardTag);
        CompoundTag root = new CompoundTag();
        root.put("boards", boards);

        QuestBoard board = QuestSavedData.load(root, QuestPool.builtin()).board(playerId);

        helper.assertTrue(board.daily().size() == 1,
                "已删除的定义 id 必须被丢弃, 实得 " + board.daily().size() + " 条");
        helper.assertTrue(board.daily().get(0).definition().id().equals("daily.mine.iron")
                        && board.daily().get(0).count() == 4,
                "仍存在的定义必须照常加载且计数不变");
        helper.succeed();
    }

    // ============================================================
    // 5. 服务层 + 经济
    // ============================================================

    /**
     * 领奖: 钱真的到账、走的是<b>全服共享的 credit_faucet 计数键</b>、且只发一次。
     *
     * 第三行是本文件最重要的一条断言。任务是新增 faucet, 若给它另起一个 faucetKey 或另配私有上限, 玩家就能
     * 在卖矿卖菜撞上每日软上限后, 靠任务继续按全额领钱 —— 这正是经济文档 8.5 与既往审计判过的印钞口。把
     * {@code QuestRewards.payout} 里的键换成任意私有字符串, 共享计数器不动, 本条立刻挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questClaimPaysThroughSharedCreditFaucetExactlyOnce(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IEconomyService economy = EconomyServices.economyService();
            QuestPool pool = QuestPool.builtin();
            QuestService service = new QuestService(pool);
            QuestDefinition definition = pool.byId("daily.mine.iron");

            QuestBoard board = service.boardOf(player);
            board.restorePeriodic(QuestClock.currentUtcDayStamp(), List.of(new QuestProgress(definition)),
                    QuestClock.currentUtcWeekStamp(), List.of());

            for (int i = 0; i < definition.objective().requiredCount(); i++) {
                service.record(new QuestFacts.BlockMine(player, Blocks.IRON_ORE.defaultBlockState()));
            }
            helper.assertTrue(board.daily().get(0).isComplete(), "喂满 requiredCount 次挖矿后任务应达标");

            long balanceBefore = economy.creditBalance(player);
            long faucetBefore = sharedFaucetGross(economy, player);

            QuestService.ClaimResult first = service.claim(player, definition.id());
            helper.assertTrue(first.outcome() == QuestService.ClaimOutcome.CLAIMED,
                    "达标任务领取应成功, 实得 " + first.outcome());
            helper.assertTrue(first.credit() > 0, "默认配置下实发额应大于 0, 实得 " + first.credit());
            helper.assertTrue(economy.creditBalance(player) - balanceBefore == first.credit(),
                    "钱包增量必须与回执一致, 实得 " + (economy.creditBalance(player) - balanceBefore));
            helper.assertTrue(sharedFaucetGross(economy, player) - faucetBefore
                            == QuestRewards.creditFor(definition),
                    "发奖必须计入全服共享的 credit_faucet 毛额计数器, 实得增量 "
                            + (sharedFaucetGross(economy, player) - faucetBefore));

            long balanceAfterFirst = economy.creditBalance(player);
            QuestService.ClaimResult second = service.claim(player, definition.id());
            helper.assertTrue(second.outcome() == QuestService.ClaimOutcome.ALREADY_CLAIMED,
                    "二次领取必须被拒, 实得 " + second.outcome());
            helper.assertTrue(economy.creditBalance(player) == balanceAfterFirst,
                    "二次领取一分钱都不许再发");
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    /** 未达标的任务领取被拒, 且一分钱不发。删掉 {@code claim} 里的 {@code isComplete} 判据本条即挂。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questClaimRefusesIncompleteQuestWithoutPaying(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IEconomyService economy = EconomyServices.economyService();
            QuestPool pool = QuestPool.builtin();
            QuestService service = new QuestService(pool);
            QuestDefinition definition = pool.byId("daily.mine.iron");

            QuestBoard board = service.boardOf(player);
            board.restorePeriodic(QuestClock.currentUtcDayStamp(), List.of(new QuestProgress(definition)),
                    QuestClock.currentUtcWeekStamp(), List.of());
            service.record(new QuestFacts.BlockMine(player, Blocks.IRON_ORE.defaultBlockState()));

            long before = economy.creditBalance(player);
            QuestService.ClaimResult result = service.claim(player, definition.id());

            helper.assertTrue(result.outcome() == QuestService.ClaimOutcome.NOT_COMPLETE,
                    "未达标任务必须拒绝领取, 实得 " + result.outcome());
            helper.assertTrue(economy.creditBalance(player) == before, "未达标不许发钱");
            helper.assertTrue(service.claim(player, "no.such.quest").outcome()
                    == QuestService.ClaimOutcome.NOT_FOUND, "不存在的任务 id 必须回 NOT_FOUND");
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    /**
     * 隐藏任务线: 领完当前阶段的奖后自动推进到下一阶段, 且新阶段是干净的从零起。
     *
     * 删掉 {@code claim} 里的 {@code chainState.advance()}, 玩家打完第一阶段就永远卡在那里, 本条即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questClaimAdvancesHiddenChainToNextStage(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            LivingEntity victim = spawnZombie(helper);
            QuestPool pool = QuestPool.builtin();
            QuestService service = new QuestService(pool);
            QuestChain chain = pool.chain(QuestPool.CHAIN_MARKSMAN);

            helper.assertTrue(service.unlockChain(player, QuestPool.CHAIN_MARKSMAN), "首次解锁应成功");
            helper.assertTrue(!service.unlockChain(player, QuestPool.CHAIN_MARKSMAN),
                    "重复解锁必须是幂等的, 否则扫描背包会不断把进度打回第一阶段");

            QuestBoard board = service.boardOf(player);
            QuestDefinition firstStage = chain.stages().get(0);
            for (int i = 0; i < firstStage.objective().requiredCount(); i++) {
                service.record(gunKill(player, victim, SNIPER, false, 12));
            }

            QuestService.ClaimResult result = service.claim(player, firstStage.id());
            helper.assertTrue(result.outcome() == QuestService.ClaimOutcome.CLAIMED,
                    "第一阶段达标后应可领取, 实得 " + result.outcome());

            QuestChainState state = board.chain(QuestPool.CHAIN_MARKSMAN);
            helper.assertTrue(state.stageIndex() == 1,
                    "领完奖必须推进到第二阶段, 实得阶段 " + state.stageIndex());
            helper.assertTrue(state.current().definition().id().equals(chain.stages().get(1).id()),
                    "推进后的当前任务必须是第二阶段的定义");
            helper.assertTrue(state.current().count() == 0 && !state.current().claimed(),
                    "新阶段必须从零起且未领取");
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    /**
     * 重摇扣费: 余额足则扣费并换任务; 余额不足则原样不动 (不扣费、不换任务)。
     *
     * 把 {@code refresh} 里的扣费失败分支删掉 (即无条件重摇), 最后两行挂 —— 那等于重摇免费。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void questRefreshChargesCreditAndRefusesWhenBroke(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IEconomyService economy = EconomyServices.economyService();
            QuestService service = new QuestService(QuestPool.builtin());
            long cost = QuestRewards.refreshCost(QuestSource.DAILY);
            helper.assertTrue(cost > 0, "本用例依赖默认配置下重摇有价, 实得 " + cost);

            economy.grant(player, Currency.CREDIT, cost);
            QuestBoard board = service.boardOf(player);
            String before = board.daily().get(0).definition().id();

            QuestService.RefreshResult ok = service.refresh(player, QuestSource.DAILY, 0);
            helper.assertTrue(ok.outcome() == QuestService.RefreshOutcome.REFRESHED,
                    "余额充足时重摇应成功, 实得 " + ok.outcome());
            helper.assertTrue(economy.creditBalance(player) == 0,
                    "重摇费必须真的被扣掉, 实得余额 " + economy.creditBalance(player));
            helper.assertTrue(!board.daily().get(0).definition().id().equals(before),
                    "重摇后该槽位必须是另一条任务");

            String afterFirst = board.daily().get(0).definition().id();
            QuestService.RefreshResult broke = service.refresh(player, QuestSource.DAILY, 0);
            helper.assertTrue(broke.outcome() == QuestService.RefreshOutcome.NOT_ENOUGH_CREDIT,
                    "余额不足时必须拒绝重摇, 实得 " + broke.outcome());
            helper.assertTrue(board.daily().get(0).definition().id().equals(afterFirst),
                    "扣费失败时任务槽不得被改动");
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    /**
     * 非周期来源没有周期戳、非可重摇来源没有重摇价 —— 两者都必须抛而不是给个哨兵值。
     *
     * 换成返回 0 之类的哨兵, 特殊任务就会被当成 1970-01-01 的日常任务参与翻转, 或者变成可以免费重摇。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nonPeriodicSourcesRefuseStampAndRefreshCost(GameTestHelper helper) {
        helper.assertTrue(throwsUnsupported(() -> QuestClock.currentStampOf(QuestSource.SPECIAL)),
                "特殊任务没有周期戳, 必须抛");
        helper.assertTrue(throwsUnsupported(() -> QuestClock.currentStampOf(QuestSource.HIDDEN)),
                "隐藏任务没有周期戳, 必须抛");
        helper.assertTrue(throwsUnsupported(() -> QuestRewards.refreshCost(QuestSource.SPECIAL)),
                "特殊任务不可重摇, 取重摇价必须抛");
        helper.assertTrue(QuestSource.DAILY.refreshable() && QuestSource.WEEKLY.refreshable(),
                "日常与周常必须是可重摇的");
        helper.assertTrue(!QuestSource.SPECIAL.periodic() && !QuestSource.HIDDEN.periodic(),
                "特殊与隐藏任务必须是非周期的");
        helper.succeed();
    }

    /**
     * 内容池自身的完整性: id 唯一 (重复即启动崩)、日常条数明显多于槽位数、任务线阶段齐备。
     *
     * 日常条数 &gt; 槽位数是"重摇"有意义的前提 —— 池子与槽位等大时重摇退化成洗牌。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void builtinPoolHasEnoughContentToMakeRefreshMeaningful(GameTestHelper helper) {
        QuestPool pool = QuestPool.builtin();
        int dailySlots = QuestConfig.DAILY_SLOTS.get();

        helper.assertTrue(pool.bySource(QuestSource.DAILY).size() > dailySlots,
                "日常内容条数必须多于槽位数 (" + dailySlots + "), 实得 "
                        + pool.bySource(QuestSource.DAILY).size());
        helper.assertTrue(pool.bySource(QuestSource.WEEKLY).size() >= 2, "周常内容至少两条才能重摇");
        helper.assertTrue(!pool.bySource(QuestSource.SPECIAL).isEmpty(), "特殊任务池不得为空");

        QuestChain chain = pool.chain(QuestPool.CHAIN_MARKSMAN);
        helper.assertTrue(chain != null && chain.stageCount() >= 2, "神射手任务线至少要有两个阶段");
        for (QuestDefinition stage : chain.stages()) {
            helper.assertTrue(pool.byId(stage.id()) == stage,
                    "任务线各阶段必须进 id 反查表, 否则重启后进度反查不回来: " + stage.id());
        }
        helper.succeed();
    }

    // ============================================================
    // 6. 分层发牌 / 撤离 / 上交 / 倾泻火力
    // ============================================================

    /**
     * 日常按 "3 简单 + 1 难" 分层发牌, 不是均匀随机抽 4 条。
     *
     * 把 {@code dealStratified} 换回一次 {@code draw(4)}, 难档条数就变成随机的 0-4, 本条即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dailyDealIsStratifiedIntoEasyAndHardTiers(GameTestHelper helper) {
        QuestPool pool = QuestPool.builtin();
        QuestBoard board = new QuestBoard();
        board.rolloverIfStale(pool, helper.getLevel().getRandom(), 1L, 1L, 4, 1, 1);

        List<QuestProgress> daily = board.daily();
        int hard = 0;
        for (QuestProgress progress : daily) {
            if (QuestPool.isHardTier(progress.definition())) {
                hard++;
            }
        }
        helper.assertTrue(daily.size() == 4, "应发满 4 个日常槽, 实得 " + daily.size());
        helper.assertTrue(hard == 1, "4 个日常槽里必须恰好 1 个难档, 实得 " + hard);
        helper.succeed();
    }

    /**
     * 重摇必须在原槽位的难度档内摇: 难档摇出来还是难档, 简单档摇出来还是简单档。
     *
     * 这是防套利的红线 —— 允许跨档重摇的话, 花 500 信用点把难档换成简单档就是一条稳赚的固定套利。把
     * {@code refreshSlot} 里的 {@code drawTier} 换回 {@code draw}, 本条会在难档那一行挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void refreshStaysInsideTheSameDifficultyTier(GameTestHelper helper) {
        QuestPool pool = QuestPool.builtin();
        RandomSource random = helper.getLevel().getRandom();
        QuestBoard board = new QuestBoard();
        board.rolloverIfStale(pool, random, 1L, 1L, 4, 1, 1);

        int hardIndex = -1;
        int easyIndex = -1;
        List<QuestProgress> daily = board.daily();
        for (int i = 0; i < daily.size(); i++) {
            if (QuestPool.isHardTier(daily.get(i).definition())) {
                hardIndex = i;
            } else if (easyIndex < 0) {
                easyIndex = i;
            }
        }
        helper.assertTrue(hardIndex >= 0 && easyIndex >= 0, "分层发牌后应当同时存在难档槽与简单档槽");

        QuestProgress newHard = board.refreshSlot(QuestSource.DAILY, hardIndex, pool, random);
        helper.assertTrue(QuestPool.isHardTier(newHard.definition()),
                "重摇难档槽必须仍摇出难档, 实得难度 " + newHard.definition().difficulty());

        QuestProgress newEasy = board.refreshSlot(QuestSource.DAILY, easyIndex, pool, random);
        helper.assertTrue(!QuestPool.isHardTier(newEasy.definition()),
                "重摇简单档槽必须仍摇出简单档, 实得难度 " + newEasy.definition().difficulty());
        helper.succeed();
    }

    /**
     * 撤离判定: 死过不算、停留不够不算、计时不可信不算; 只有活着待够了走出来才算。
     *
     * "死了被抬出去不算撤离"是这条判据存在的全部理由 —— 删掉 {@code finishVisit} 里的 {@code visit.died()}
     * 判据, 第三段立刻挂。删掉停留门槛则第一段挂, 那道门是防"进洞立刻出来"秒刷的红线。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningExtractionRejectsDeathAndShortVisits(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        long gate = 6_000L;

        QuestMiningVisits.startVisit(player, Difficulty.HARD, 1_000L);
        helper.assertTrue(QuestMiningVisits.finishVisit(player, 1_000L + gate - 1L, gate) == null,
                "停留差一 tick 也不算撤离");

        QuestMiningVisits.startVisit(player, Difficulty.HARD, 1_000L);
        QuestMiningVisits.Extraction ok = QuestMiningVisits.finishVisit(player, 1_000L + gate, gate);
        helper.assertTrue(ok != null, "活着且停留达标必须判为撤离");
        helper.assertTrue(ok.difficulty() == Difficulty.HARD && ok.dwellTicks() == gate,
                "撤离产物必须带上进入时记下的难度与实际停留时长");

        QuestMiningVisits.startVisit(player, Difficulty.EASY, 0L);
        QuestMiningVisits.markDied(player);
        helper.assertTrue(QuestMiningVisits.finishVisit(player, 100_000L, gate) == null,
                "死过的行程即使之后走出矿洞也不算撤离");

        helper.assertTrue(QuestMiningVisits.finishVisit(UUID.randomUUID(), 100_000L, 0L) == null,
                "没有在途行程时不得凭空判出一次撤离");

        QuestMiningVisits.startVisit(player, Difficulty.MEDIUM, 10_000L);
        helper.assertTrue(QuestMiningVisits.finishVisit(player, 5_000L, 0L) == null,
                "世界时间倒流时本趟不可信计时, 必须判废而不是当成负停留放行");

        QuestMiningVisits.startVisit(player, Difficulty.EASY, 0L);
        QuestMiningVisits.finishVisit(player, gate, gate);
        helper.assertTrue(QuestMiningVisits.finishVisit(player, gate * 2L, gate) == null,
                "一趟行程只能结算一次, 结算后在途记录必须已被摘掉");
        helper.succeed();
    }

    /**
     * 掉线重连: 行程重开且计时从重连那一刻起算, <b>离线时长一秒都不计入停留</b>。
     *
     * 这条守的是撤离判据最容易被绕开的地方 —— 若重连时接续掉线前的进入时间, 最优解就变成"进洞后直接退出
     * 游戏, 几小时后上线走出来", 秒过任何停留门槛。把 {@code onReconnect} 改成恢复原进入时间, 第二行立刻挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reconnectRestartsDwellClockSoOfflineTimeNeverCounts(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        long gate = 6_000L;

        QuestMiningVisits.startVisit(player, Difficulty.HARD, 0L);
        QuestMiningVisits.discardVisit(player);      // 掉线
        helper.assertTrue(!QuestMiningVisits.hasVisit(player), "掉线后不得留下在途行程");

        // 重连: 离线了一百万 tick, 但计时从重连这一刻起算。
        long reconnectAt = 1_000_000L;
        QuestMiningVisits.startVisit(player, Difficulty.HARD, reconnectAt);
        helper.assertTrue(QuestMiningVisits.finishVisit(player, reconnectAt + gate - 1L, gate) == null,
                "离线时长不得计入停留, 重连后必须重新待够门槛");

        QuestMiningVisits.startVisit(player, Difficulty.HARD, reconnectAt);
        QuestMiningVisits.Extraction ok = QuestMiningVisits.finishVisit(player, reconnectAt + gate, gate);
        helper.assertTrue(ok != null && ok.dwellTicks() == gate,
                "重连后待够门槛必须算撤离, 且停留时长只计重连之后的那一段");
        helper.succeed();
    }

    /**
     * 上交: 一次交足剩余需求 (不多扣), 交够之后再交一分不动背包。
     *
     * 删掉 {@code turnIn} 里按剩余需求裁剪的那一步, 第一段会在"背包应剩 36"上挂 (会把 100 个全收走);
     * 删掉 {@code isComplete} 短路, 第二段会在"交够后背包不再变"上挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void turnInTakesOnlyWhatIsNeededAndStopsWhenComplete(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            QuestPool pool = QuestPool.builtin();
            QuestService service = new QuestService(pool);
            QuestDefinition definition = pool.byId("daily.turnin.rotten");
            int required = definition.objective().requiredCount();

            QuestBoard board = service.boardOf(player);
            board.restorePeriodic(QuestClock.currentUtcDayStamp(), List.of(new QuestProgress(definition)),
                    QuestClock.currentUtcWeekStamp(), List.of());
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(Items.ROTTEN_FLESH, 64));
            player.getInventory().add(new ItemStack(Items.ROTTEN_FLESH, 36));

            QuestService.TurnInResult first = service.turnIn(player, definition.id());
            helper.assertTrue(first.outcome() == QuestService.TurnInOutcome.TURNED_IN,
                    "背包里够就应当上交成功, 实得 " + first.outcome());
            helper.assertTrue(first.count() == required,
                    "一次应交足剩余需求 " + required + ", 实得 " + first.count());
            helper.assertTrue(countInInventory(player, Items.ROTTEN_FLESH) == 100 - required,
                    "只能扣走需要的那些, 实得剩余 " + countInInventory(player, Items.ROTTEN_FLESH));
            helper.assertTrue(board.daily().get(0).isComplete(), "交足后任务应达标");

            QuestService.TurnInResult again = service.turnIn(player, definition.id());
            helper.assertTrue(again.outcome() == QuestService.TurnInOutcome.ALREADY_COMPLETE,
                    "交够之后再交必须被拒, 实得 " + again.outcome());
            helper.assertTrue(countInInventory(player, Items.ROTTEN_FLESH) == 100 - required,
                    "被拒的上交一个物品都不许扣");
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    /**
     * 倾泻火力算的是<b>击发</b>, 不是击杀也不是命中。
     *
     * 第二行是要害: 把 {@code AmmoSpentObjective.match} 错接到 GunKill 上, 这条任务就变成了"击杀 N 只",
     * 与设计意图 (弹药消耗) 完全不同。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ammoSpentCountsShotsNotKills(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        LivingEntity victim = spawnZombie(helper);
        AmmoSpentObjective anyGun = AmmoSpentObjective.anyGun(3);
        AmmoSpentObjective sniperOnly = new AmmoSpentObjective(SNIPER, 3);

        QuestFacts sniperShot = new QuestFacts.GunShot(player, TEST_GUN, SNIPER);
        QuestFacts rifleShot = new QuestFacts.GunShot(player, TEST_GUN, "rifle");
        QuestFacts unknownShot = new QuestFacts.GunShot(player, TEST_GUN, null);

        helper.assertTrue(anyGun.match(sniperShot) == 1, "任意枪型的击发必须计入");
        helper.assertTrue(anyGun.match(gunKill(player, victim, SNIPER, true, 10)) == 0,
                "击杀不是击发, 不得计入倾泻火力");
        helper.assertTrue(sniperOnly.match(rifleShot) == 0, "限定枪型时其它枪的击发不得计入");
        helper.assertTrue(sniperOnly.match(unknownShot) == 0, "枪型未知时限定枪型的目标不得计入");
        helper.assertTrue(anyGun.match(unknownShot) == 1, "枪型未知不该让'打出一发'这件事消失");
        helper.succeed();
    }

    private static int countInInventory(ServerPlayer player, net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // ============================================================
    // 辅助
    // ============================================================

    private static QuestFacts gunKill(ServerPlayer player, LivingEntity victim, String gunType,
                                      boolean headshot, double distance) {
        return new QuestFacts.GunKill(player, victim, TEST_GUN, gunType, headshot, distance, 30.0F);
    }

    private static LivingEntity spawnZombie(GameTestHelper helper) {
        return requireEntity(helper, EntityType.ZOMBIE.create(helper.getLevel()), "zombie");
    }

    /** 实体工厂返回 null 是环境问题而非业务失败, 直接抛让用例红在真正的原因上。 */
    private static LivingEntity requireEntity(GameTestHelper helper, LivingEntity entity, String what) {
        if (entity == null) {
            throw new IllegalStateException("failed to create test entity: " + what);
        }
        return entity;
    }

    private static CompoundTag progressTag(String id, int count) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("count", count);
        tag.putBoolean("claimed", false);
        return tag;
    }

    private static long sharedFaucetGross(IEconomyService economy, ServerPlayer player) {
        return economy.todayFaucetGross(player,
                List.of(EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY))[0];
    }

    private static boolean throwsUnsupported(Runnable action) {
        try {
            action.run();
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }

    /** 每个用例一套全新的内存账本, 免得跨用例的余额与 faucet 计数互相干扰。 */
    private static void registerFreshEconomy() {
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        Function<UUID, PlayerAbuseState> resolver = id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
        EconomyServices.reset();
        EconomyServices.registerEconomyService(new EconomyService(ledger, new AbuseGuard(), resolver));
    }

    private static IEconomyService currentEconomy() {
        return EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
    }

    /** 定位器是进程级静态, 用例结束必须原样放回, 否则会串到后面的批次。 */
    private static void restoreEconomy(IEconomyService previous) {
        EconomyServices.reset();
        if (previous != null) {
            EconomyServices.registerEconomyService(previous);
        }
    }
}
