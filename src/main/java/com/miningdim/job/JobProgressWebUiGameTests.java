package com.miningdim.job;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * W3 职业一的 job.progress GameTest。
 *
 * 强断言 (删被测核心逻辑必挂):
 *  1. 恒 8 条且顺序逐字 = {@link JobId#values()} 声明序 (漏一个职业 / 换个顺序即挂);
 *  2. 每条恰好 7 个契约字段 —— <b>多一个 displayName 就挂</b> (专用服务端不加载 lang, 服务端不下发中文);
 *  3. levelXp/nextLevelXp 是 "本级已获 / 本级跨度", 满级两栏同时发 0 (前端据此判毕业而不是画 0/0 的空槽);
 *  4. <b>K2 跨日</b>: 昨天吃满额度、今天没开工时读到满额度, 且落盘的 dayStamp/dailyXp 一个字节都没被改
 *     (只读接口顺手翻日 = 把衰减档位洗回第 0 档 = 印钞);
 *  5. 与 player.profile 的 jobs 数组<b>逐字相等</b> —— K1 要求两条 action 同形且同实现, 这条锁住"同一份实现",
 *     谁把 JobProgressJson 复制成第二份都会在这里分叉;
 *  6. action 以契约名注册进派发器 (其余用例直接拿 handler 引用, 名字打错也照样绿)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class JobProgressWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w3";

    private static final String PROGRESS_ACTION = "job.progress";
    private static final String PROFILE_ACTION = "player.profile";

    /**
     * 单条进度的契约字段全集 (写死在测试里而不是引用被测代码): 前端按这七个键解析, 服务端多发一个字段就是
     * 契约漂移 —— 尤其是被 K1 明令去掉的 displayName。
     */
    private static final Set<String> ENTRY_KEYS = Set.of(
            "jobId", "level", "totalXp", "levelXp", "nextLevelXp", "dailyXp", "dailyRemaining");

    // ============================================================
    // 1. 形状: 恒 8 条 / 声明序 / 只发这 7 个字段
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void jobProgressReturnsEightEntriesInDeclarationOrder(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            JsonArray jobs = handle(helper, PROGRESS_ACTION, player).getAsJsonArray("jobs");

            helper.assertTrue(jobs.size() == JobId.values().length,
                    "jobs 恒发 " + JobId.values().length + " 条, 实得 " + jobs.size());
            for (int i = 0; i < JobId.values().length; i++) {
                JsonObject entry = jobs.get(i).getAsJsonObject();
                String expectedId = JobId.values()[i].id();
                helper.assertTrue(expectedId.equals(entry.get("jobId").getAsString()),
                        "第 " + i + " 条必须是 " + expectedId + ", 实得 " + entry.get("jobId").getAsString());
                helper.assertTrue(ENTRY_KEYS.equals(entry.keySet()),
                        expectedId + " 条目的字段集必须恰好是 " + ENTRY_KEYS + ", 实得 " + entry.keySet());
            }
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 2. 等级跨度 / 满级态 / 新号默认态
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void jobProgressDerivesLevelSpanAndZeroesBothAtMaxLevel(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IMiningPlayerData data = playerData(player);
            // 入账日戳必须取 action 用的那一份: 拿别的戳入账再断言今天读得到 500, 断的是错的行为。
            long today = EconomyServices.economyService().currentDayStamp();
            data.jobProgress(JobId.MINER).setLevel(5);
            data.jobProgress(JobId.MINER).grantXp(JobId.MINER, 500L, today);
            data.jobProgress(JobId.CHEF).setLevel(JobXpCurve.MAX_LEVEL);

            JsonArray jobs = handle(helper, PROGRESS_ACTION, player).getAsJsonArray("jobs");

            long span5 = JobXpCurve.cumulativeXpForLevel(6) - JobXpCurve.cumulativeXpForLevel(5);
            JsonObject miner = entry(helper, jobs, JobId.MINER);
            helper.assertTrue(miner.get("level").getAsInt() == 5, "矿工应为 5 级");
            helper.assertTrue(miner.get("totalXp").getAsLong() == JobXpCurve.cumulativeXpForLevel(5) + 500L,
                    "totalXp 是累计有效经验");
            helper.assertTrue(miner.get("levelXp").getAsLong() == 500L,
                    "levelXp 是本级已获 500, 实得 " + miner.get("levelXp").getAsLong());
            helper.assertTrue(miner.get("nextLevelXp").getAsLong() == span5,
                    "nextLevelXp 是本级跨度 " + span5 + " 而不是'还差多少', 实得 "
                            + miner.get("nextLevelXp").getAsLong());
            helper.assertTrue(miner.get("dailyXp").getAsLong() == 500L,
                    "dailyXp 是当日已入账有效经验 500, 实得 " + miner.get("dailyXp").getAsLong());
            helper.assertTrue(miner.get("dailyRemaining").getAsLong()
                            == JobXpPolicies.dailySoftCap(JobId.MINER) - 500L,
                    "剩余额度 = 软上限 - 当日已获");

            // 满级: 两栏同时发 0, 前端据 nextLevelXp===0 判毕业, 不画 0/0 的 NaN 宽度进度条。
            JsonObject chef = entry(helper, jobs, JobId.CHEF);
            helper.assertTrue(chef.get("level").getAsInt() == JobXpCurve.MAX_LEVEL, "厨师应为满级");
            helper.assertTrue(chef.get("nextLevelXp").getAsLong() == 0L, "满级 nextLevelXp 必须发 0");
            helper.assertTrue(chef.get("levelXp").getAsLong() == 0L,
                    "满级 levelXp 必须同时发 0 (否则前端画出 x/0 的进度条)");
            helper.assertTrue(chef.get("totalXp").getAsLong() == JobXpCurve.GRADUATION_XP,
                    "满级 totalXp 仍是真实累计经验, 不被清零");

            // 没练过的职业: 全 0 且非负, 跨度 = 达到 2 级所需累计经验 (新号不许出现负数或 0/0)。
            JsonObject brewer = entry(helper, jobs, JobId.BREWER);
            helper.assertTrue(brewer.get("level").getAsInt() == 1 && brewer.get("totalXp").getAsLong() == 0L,
                    "没练过的职业是 1 级 0 经验");
            helper.assertTrue(brewer.get("levelXp").getAsLong() == 0L
                            && brewer.get("nextLevelXp").getAsLong() == JobXpCurve.cumulativeXpForLevel(2),
                    "1 级的本级跨度 = 达到 2 级所需累计经验");
            helper.assertTrue(brewer.get("dailyXp").getAsLong() == 0L
                            && brewer.get("dailyRemaining").getAsLong() == JobXpPolicies.dailySoftCap(JobId.BREWER),
                    "新号当日额度是满的, 且不得为负");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 3. K2: 跨日只读翻日, 且不许写脏落盘字段
    // ============================================================

    /**
     * 昨天吃满额度、今天没开工时, 职业页必须显示"今天一点没用"。
     *
     * 两个方向都要断言: 读出来的别撒谎 (dailyXp=0 / 额度满), <b>且</b>落盘的别被改 —— 只读接口顺手翻日等于把
     * 衰减档位洗回第 0 档, 玩家今天第一铲子就按全额结算, 那是印钞。矿工与农夫各测一遍: 两者软上限不同源
     * (JobXpCurve 3800 / FarmerXpCurve 2150), 只测一个会漏掉 per-job 档位错配。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void jobProgressRollsOverDailyQuotaWithoutMutatingStoredState(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IMiningPlayerData data = playerData(player);
            long yesterday = EconomyServices.economyService().currentDayStamp() - 1L;

            for (JobId job : new JobId[]{JobId.MINER, JobId.FARMER}) {
                long softCap = JobXpPolicies.dailySoftCap(job);
                JobProgress progress = data.jobProgress(job);
                progress.grantXp(job, softCap * 100L, yesterday);
                helper.assertTrue(progress.dailyRemaining(job) == 0L && progress.dailyXp(job) > 0L,
                        "前置条件不成立: " + job.id() + " 昨天没把额度吃满, 本条测不到翻日");
            }
            long minerStoredDailyXp = data.jobProgress(JobId.MINER).dailyXp(JobId.MINER);
            long farmerStoredDailyXp = data.jobProgress(JobId.FARMER).dailyXp(JobId.FARMER);

            JsonArray jobs = handle(helper, PROGRESS_ACTION, player).getAsJsonArray("jobs");

            for (JobId job : new JobId[]{JobId.MINER, JobId.FARMER}) {
                long softCap = JobXpPolicies.dailySoftCap(job);
                JsonObject row = entry(helper, jobs, job);
                helper.assertTrue(row.get("dailyXp").getAsLong() == 0L,
                        job.id() + " 跨 UTC 日后 dailyXp 必须回 0, 实得 " + row.get("dailyXp").getAsLong());
                helper.assertTrue(row.get("dailyRemaining").getAsLong() == softCap,
                        job.id() + " 跨 UTC 日后额度必须是满的 " + softCap
                                + ", 实得 " + row.get("dailyRemaining").getAsLong());
            }

            helper.assertTrue(data.jobProgress(JobId.MINER).dayStamp() == yesterday
                            && data.jobProgress(JobId.FARMER).dayStamp() == yesterday,
                    "job.progress 是只读查询, 不许把 dayStamp 改成今天");
            helper.assertTrue(data.jobProgress(JobId.MINER).dailyXp(JobId.MINER) == minerStoredDailyXp
                            && data.jobProgress(JobId.FARMER).dailyXp(JobId.FARMER) == farmerStoredDailyXp,
                    "job.progress 是只读查询, 不许清掉落盘的 dailyXp (清了就等于把衰减档洗回第 0 档)");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 4. K1: 与 player.profile 的 jobs 逐字相等 (同一份实现)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void jobProgressJobsAreByteIdenticalToPlayerProfileJobs(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IMiningPlayerData data = playerData(player);
            long today = EconomyServices.economyService().currentDayStamp();
            // 造出三种不同形态 (半级 / 满级 / 未开工), 让"两边同形"不是靠一堆全 0 蒙混过关。
            data.jobProgress(JobId.ENGINEER).setLevel(3);
            data.jobProgress(JobId.ENGINEER).grantXp(JobId.ENGINEER, 700L, today);
            data.jobProgress(JobId.TAROT).setLevel(JobXpCurve.MAX_LEVEL);

            JsonArray fromProgress = handle(helper, PROGRESS_ACTION, player).getAsJsonArray("jobs");
            JsonArray fromProfile = handle(helper, PROFILE_ACTION, player).getAsJsonArray("jobs");

            helper.assertTrue(fromProgress.equals(fromProfile),
                    "job.progress 的 jobs 必须与 player.profile 的 jobs 逐字相等 (K1: 同形且同实现)。"
                            + " job.progress=" + fromProgress + " player.profile=" + fromProfile);
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 5. 注册名
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void jobProgressIsRegisteredUnderTheContractName(GameTestHelper helper) {
        ensureProgressRegistered();
        helper.assertTrue(WebUiServerDispatcher.resolve(PROGRESS_ACTION) != null,
                PROGRESS_ACTION + " 必须由 JobWebUiActions.registerAll 注册进派发器");
        helper.assertTrue(WebUiServerDispatcher.resolve("jobs.progress") == null
                        && WebUiServerDispatcher.resolve("job.progress.get") == null,
                "不得为同一份数据另注册别名 action (前端 SERVER_ACTIONS 里只有 job.progress 一条)");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 幂等注册 (范式同 PlayerWebUiW1GameTests.ensurePlayerActionsRegistered): 派发器注册表是<b>进程级静态</b>,
     * register 用 putIfAbsent 守卫, 重复注册直接抛。故已注册就什么都不做。
     */
    private static void ensureProgressRegistered() {
        if (WebUiServerDispatcher.resolve(PROGRESS_ACTION) == null) {
            JobWebUiActions.registerAll();
        }
    }

    /** 按契约名取 handler 并调用 (服务端纯逻辑, 不经网络层); 未注册就地判失败。 */
    private static JsonObject handle(GameTestHelper helper, String action, ServerPlayer sender) {
        ensureProgressRegistered();
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return JsonParser.parseString(handler.handle(sender, new JsonObject())).getAsJsonObject();
    }

    private static JsonObject entry(GameTestHelper helper, JsonArray jobs, JobId job) {
        for (int i = 0; i < jobs.size(); i++) {
            JsonObject row = jobs.get(i).getAsJsonObject();
            if (job.id().equals(row.get("jobId").getAsString())) {
                return row;
            }
        }
        helper.fail("回执缺职业条目 " + job.id() + ", 实得 " + jobs);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static IMiningPlayerData playerData(ServerPlayer player) {
        return MiningCapabilities.get(player).orElseThrow(
                () -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"));
    }

    private static IEconomyService swapEconomy(IEconomyService fake) {
        IEconomyService prev = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(fake);
        return prev;
    }

    private static void restoreEconomy(IEconomyService prev) {
        if (prev != null) {
            EconomyServices.registerEconomyService(prev);
        } else {
            EconomyServices.reset();
        }
    }

    private static Function<UUID, PlayerAbuseState> newStateResolver() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
    }
}
