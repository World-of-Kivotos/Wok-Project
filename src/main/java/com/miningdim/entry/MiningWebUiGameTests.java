package com.miningdim.entry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.Difficulty;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.job.JobId;
import com.miningdim.job.miner.MinerConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * W7 矿洞面板的 mining.overview / myStatus / enter / leave GameTest。
 *
 * 三条主线各有独立断言, 放宽任一条必挂:
 *  1. R1 模型: 总览恒回三行常驻<b>共享</b>区域 (每难度一个), 不存在"我的副本" —— 行数、顺序、shared 都锁死;
 *  2. 等级门以代码为准: 门槛必须是 L1/L4/L8 ({@link MinerConstants}), 不是 {@link GateResult} 头注释里那套
 *     过期的 L10/L25。把门槛改回注释口径, 两条断言同时挂;
 *  3. 维度校验: {@code regionAt} 只比 XZ, 主世界出生点就落在 Easy 区盒里 —— 少了维度判定, 站在主世界的玩家
 *     会被 myStatus 报成"正在矿洞里"。用 (100,·,100) 这个必然落在区盒内的坐标专测它。
 *
 * 另锁两处委派 (面板自己重写一份逻辑就会分叉):
 *  - enter 受理后, capability 里必须出现回退现场快照 —— 那是 {@code EntryGateway.requestEnter} 内部才会做的事,
 *    面板若绕开它自己 allocate (存量 SelectZoneC2S / command 包那两条路径正是这么写的) 本条即挂;
 *  - leave 必须真把玩家送回回退点并清掉矿山运行态 —— 那是 {@code EntrySystem.leaveToFallback} 的完整语义。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MiningWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w7";

    private static final String OVERVIEW_ACTION = "mining.overview";
    private static final String MY_STATUS_ACTION = "mining.myStatus";
    private static final String ENTER_ACTION = "mining.enter";
    private static final String LEAVE_ACTION = "mining.leave";

    /** 三行总览的契约顺序 (前端按下标画卡片, 顺序错了卡片就串)。 */
    private static final String[] DIFFICULTY_ORDER = {"easy", "medium", "hard"};

    /** 代码权威的三档门槛 (Miner_Job_DesignSpec 第八章); 与 GateResult 头注释的 L10/L25 无关。 */
    private static final int[] REQUIRED_MINER_LEVEL = {1, 4, 8};

    // ============================================================
    // 1. mining.overview
    // ============================================================

    /**
     * R1: 恒三行、每难度一行、全部是常驻共享区域且 id 互不相同。
     *
     * 这条锁的是"面板上不许出现私有副本"这个认知前提: 一旦有人把 overview 改成按玩家列实例 (或按需新建),
     * 行数/shared/id 三个断言里至少一个会挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningOverviewListsExactlyThreeResidentSharedRegions(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject overview = handle(helper, OVERVIEW_ACTION, player, new JsonObject());

        JsonArray rows = overview.getAsJsonArray("instances");
        helper.assertTrue(rows.size() == 3,
                "R1 下全服恒有三块常驻区域 (每难度一块), 实得 " + rows.size() + " 行");

        long[] ids = new long[3];
        for (int i = 0; i < 3; i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            helper.assertTrue(DIFFICULTY_ORDER[i].equals(row.get("difficulty").getAsString()),
                    "第 " + i + " 行必须是 " + DIFFICULTY_ORDER[i] + ", 实得 " + row.get("difficulty").getAsString());
            boolean expectedDropsOnDeath = Difficulty.values()[i] == Difficulty.HARD;
            helper.assertTrue(row.has("dropsOnDeath")
                            && row.get("dropsOnDeath").getAsBoolean() == expectedDropsOnDeath,
                    DIFFICULTY_ORDER[i] + " 行 dropsOnDeath 必须为 " + expectedDropsOnDeath
                            + " (仅 HARD 死亡掉落), 实得 " + row.get("dropsOnDeath"));
            helper.assertTrue(("difficulty.miningdim." + DIFFICULTY_ORDER[i]).equals(row.get("nameKey").getAsString()),
                    "nameKey 必须是翻译键 difficulty.miningdim.<难度> (服务端不发中文), 实得 "
                            + row.get("nameKey").getAsString());
            helper.assertTrue(row.get("available").getAsBoolean(),
                    DIFFICULTY_ORDER[i] + " 区域必须在开服重建时就已预建 (R1 常驻, 不按需创建)");
            helper.assertTrue(row.get("shared").getAsBoolean(),
                    DIFFICULTY_ORDER[i] + " 区域必须是共享实例 —— 面板上不存在私有副本的概念");
            // 逐行对上服务端此刻真正持有的那块区域: id / 在场人数 / region 原点都不许是面板自己编的。
            InstanceState live = MiningWebUiActions.fixedInstanceFor(Difficulty.values()[i]);
            helper.assertTrue(live != null && row.get("instanceId").getAsLong() == live.instanceId(),
                    DIFFICULTY_ORDER[i] + " 行的 instanceId 必须是该难度常驻区域的真实 id, 实得 "
                            + row.get("instanceId"));
            helper.assertTrue(row.get("playersInside").getAsInt() == live.refCount(),
                    DIFFICULTY_ORDER[i] + " 行的在场人数必须是实时 refCount " + live.refCount()
                            + ", 实得 " + row.get("playersInside").getAsInt());
            helper.assertTrue(row.get("regionOriginX").getAsInt() == live.regionBox().originX()
                            && row.get("regionOriginZ").getAsInt() == live.regionBox().originZ(),
                    DIFFICULTY_ORDER[i] + " 行的 region 原点必须取自真实 RegionBox, 实得 ("
                            + row.get("regionOriginX") + "," + row.get("regionOriginZ") + ")");
            ids[i] = row.get("instanceId").getAsLong();
        }
        helper.assertTrue(ids[0] != ids[1] && ids[1] != ids[2] && ids[0] != ids[2],
                "三块区域是三个独立实例, id 不得重复, 实得 " + ids[0] + "/" + ids[1] + "/" + ids[2]);
        helper.succeed();
    }

    /**
     * 等级门的数值权威是 {@link MinerConstants} 而不是 {@link GateResult} 的头注释。
     *
     * L4 号: Easy/Medium 解锁、Hard 仍锁。把 requiredMinerLevel 换成注释里的 10/25, 或把 unlocked 改成
     * 读原版经验等级, 本条立刻挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningOverviewGatesFollowMinerLevelCodeNotStaleDocComment(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, MinerConstants.MEDIUM_MIN_MINER_LEVEL);

        JsonArray rows = handle(helper, OVERVIEW_ACTION, player, new JsonObject()).getAsJsonArray("instances");
        for (int i = 0; i < 3; i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            helper.assertTrue(row.get("requiredMinerLevel").getAsInt() == REQUIRED_MINER_LEVEL[i],
                    DIFFICULTY_ORDER[i] + " 的门槛必须是矿工 " + REQUIRED_MINER_LEVEL[i]
                            + " 级 (代码权威 L1/L4/L8), 实得 " + row.get("requiredMinerLevel").getAsInt());
        }
        // 门槛表与代码权威同源: 有人把 MinerConstants 改成注释里的 L10/L25, 本条即挂 (提醒那场冲突被反向裁决了)。
        helper.assertTrue(REQUIRED_MINER_LEVEL[1] == MinerConstants.MEDIUM_MIN_MINER_LEVEL
                        && REQUIRED_MINER_LEVEL[2] == MinerConstants.HARD_MIN_MINER_LEVEL,
                "前提校验: 门槛表必须与 MinerConstants 一致 (L4/L8), 实得 "
                        + MinerConstants.MEDIUM_MIN_MINER_LEVEL + "/" + MinerConstants.HARD_MIN_MINER_LEVEL);
        helper.assertTrue(rows.get(0).getAsJsonObject().get("unlocked").getAsBoolean()
                        && rows.get(1).getAsJsonObject().get("unlocked").getAsBoolean(),
                "矿工 4 级已解锁 easy 与 medium");
        helper.assertTrue(!rows.get(2).getAsJsonObject().get("unlocked").getAsBoolean(),
                "矿工 4 级仍未解锁 hard (门槛 8 级)");

        setMinerLevel(player, MinerConstants.HARD_MIN_MINER_LEVEL);
        JsonArray atEight = handle(helper, OVERVIEW_ACTION, player, new JsonObject()).getAsJsonArray("instances");
        helper.assertTrue(atEight.get(2).getAsJsonObject().get("unlocked").getAsBoolean(),
                "矿工 8 级解锁 hard (门槛是 >= 而不是 >)");
        helper.assertTrue(atEight.get(0).getAsJsonObject().get("unlocked").getAsBoolean(),
                "高等级不得反过来锁住低难度");
        helper.succeed();
    }

    /**
     * 自动刷新时刻: 关闭 (autoResetHours<=0) 或从未记录基准时, nextResetGameTime 必须是 <b>JSON null</b>
     * 而不是一个算出来的时刻 —— 面板拿到一个数字就会画倒计时, 而那一刻根本不会发生任何事。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningOverviewEmitsNullNextResetWhenAutoResetIsOff(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject overview = handle(helper, OVERVIEW_ACTION, player, new JsonObject());
        JsonArray rows = overview.getAsJsonArray("instances");

        for (int i = 0; i < 3; i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            int hours = row.get("autoResetHours").getAsInt();
            boolean hasBaseline = !row.get("lastResetGameTime").isJsonNull();
            boolean hasNext = !row.get("nextResetGameTime").isJsonNull();
            helper.assertTrue(hasNext == (hours > 0 && hasBaseline),
                    DIFFICULTY_ORDER[i] + ": 只有开启定时刷新且有基准时刻才允许给出 nextResetGameTime, 实得 hours="
                            + hours + " baseline=" + hasBaseline + " next=" + hasNext);
            if (hasNext) {
                long expected = row.get("lastResetGameTime").getAsLong() + (long) hours * 3600L * 20L;
                helper.assertTrue(row.get("nextResetGameTime").getAsLong() == expected,
                        DIFFICULTY_ORDER[i] + ": 下次刷新 = 上次 + 周期 (game tick), 应为 " + expected
                                + ", 实得 " + row.get("nextResetGameTime").getAsLong());
            }
        }
        // 总览也回一份"我在哪块": 站在主世界时必须是 JSON null, 与 myStatus 的判据是同一条 (维度 + 坐标)。
        helper.assertTrue(overview.get("myDifficulty").isJsonNull(),
                "主世界玩家的 myDifficulty 必须是 null, 实得 " + overview.get("myDifficulty"));
        helper.succeed();
    }

    // ============================================================
    // 2. mining.myStatus
    // ============================================================

    /**
     * 维度门: 站在主世界 (100,·,100) —— 这个坐标必定落在 Easy 区盒的 XZ 范围内 —— 依然必须报"不在矿洞",
     * 且 difficulty / regionOrigin 全是 JSON null 而不是编造出来的 0。
     *
     * 删掉 currentRegionOf 里的维度判定, 本条立刻挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningMyStatusRefusesToCallOverworldPlayersInsideTheMine(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setNoGravity(true);
        player.teleportTo(100.5D, player.getY(), 100.5D);

        helper.assertTrue(!player.level().dimension().equals(MiningConstants.MINING_LEVEL),
                "前提校验: GameTest 世界不该是矿洞维度");
        helper.assertTrue(MiningServices.instanceManager().regionAt(100, 100) != null,
                "前提校验: (100,100) 必须落在某个区盒内, 否则挡住的是几何而不是维度, 本条测不到该测的东西");

        JsonObject status = handle(helper, MY_STATUS_ACTION, player, new JsonObject());
        helper.assertTrue(!status.get("inside").getAsBoolean(),
                "主世界玩家不许被报成在矿洞里 (regionAt 只比 XZ, 维度判定不可省)");
        helper.assertTrue(!status.get("inMiningDimension").getAsBoolean(), "确实不在矿洞维度");
        helper.assertTrue(status.get("difficulty").isJsonNull()
                        && status.get("instanceId").isJsonNull()
                        && status.get("genState").isJsonNull(),
                "不在矿洞时三个区域字段必须是 JSON null, 实得 " + status);
        helper.assertTrue(status.get("regionOriginX").isJsonNull() && status.get("regionOriginZ").isJsonNull(),
                "不在矿洞时不许编造 region 坐标 (发 0 会被前端画成'你在原点那块区域')");
        helper.assertTrue(status.get("currentInstanceId").getAsLong() == IMiningPlayerData.NO_INSTANCE,
                "从未进过矿洞时 currentInstanceId 是哨兵 -1, 实得 " + status.get("currentInstanceId").getAsLong());
        helper.succeed();
    }

    /**
     * 出生保护是真值不是缺省: 从未进过矿洞 -> 剩余 0 (不是负数); 手动写入截止时刻 -> 剩余 = 截止 - 当前。
     *
     * 少了 max(0,·) 那一步, 过期的旧截止时刻会让面板显示"还剩 -900 tick"。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningMyStatusReportsSpawnFreezeAsTicksWithoutUnderflow(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IMiningPlayerData data = dataOf(player);

        JsonObject fresh = handle(helper, MY_STATUS_ACTION, player, new JsonObject());
        helper.assertTrue(fresh.get("spawnFreezeUntilGameTime").getAsLong() == 0L
                        && fresh.get("spawnFreezeRemainingTicks").getAsLong() == 0L,
                "新号既没有出生保护截止时刻也没有剩余量, 实得 "
                        + fresh.get("spawnFreezeRemainingTicks").getAsLong());

        long now = player.serverLevel().getGameTime();
        data.setSpawnFreezeUntil(now + 200L);
        JsonObject frozen = handle(helper, MY_STATUS_ACTION, player, new JsonObject());
        helper.assertTrue(frozen.get("spawnFreezeUntilGameTime").getAsLong() == now + 200L,
                "截止时刻原样下发 (game tick, 不换算墙钟)");
        helper.assertTrue(frozen.get("spawnFreezeRemainingTicks").getAsLong() == 200L,
                "剩余 = 截止 - 当前 = 200, 实得 " + frozen.get("spawnFreezeRemainingTicks").getAsLong());

        // 早已过期的截止时刻: 差值为负, 必须钳到 0 而不是原样下发。
        data.setSpawnFreezeUntil(now - 900L);
        JsonObject expired = handle(helper, MY_STATUS_ACTION, player, new JsonObject());
        helper.assertTrue(expired.get("spawnFreezeRemainingTicks").getAsLong() == 0L,
                "过期的出生保护剩余必须是 0 而不是负数, 实得 "
                        + expired.get("spawnFreezeRemainingTicks").getAsLong());

        // capability 的实例指针与按坐标反查是两个独立事实, 不许互相覆盖。
        data.setCurrentInstanceId(4242L);
        JsonObject pointed = handle(helper, MY_STATUS_ACTION, player, new JsonObject());
        helper.assertTrue(pointed.get("currentInstanceId").getAsLong() == 4242L,
                "currentInstanceId 读 capability 原值, 实得 " + pointed.get("currentInstanceId").getAsLong());
        helper.assertTrue(!pointed.get("inside").getAsBoolean(),
                "inside 只由维度+坐标决定, 不得被 capability 指针带偏");
        data.clearMiningState();
        helper.succeed();
    }

    // ============================================================
    // 3. mining.enter
    // ============================================================

    /**
     * 等级门拒绝: 回执带代码权威的门槛 (hard=8, 不是注释的 25), 且<b>绝不</b>触碰入场链路 ——
     * 没有写回退现场快照就是它没被调用的证据 (snapshotFallback 只在 requestEnter 内部发生)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningEnterRejectsBelowGateWithoutTouchingTheEntryPipeline(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, 1);
        IMiningPlayerData data = dataOf(player);
        helper.assertTrue(!data.hasFallback(), "前提校验: 新号还没有回退现场快照");

        JsonObject result = handle(helper, ENTER_ACTION, player, enterPayload("hard"));
        helper.assertTrue(!result.get("accepted").getAsBoolean(), "1 级矿工不得被受理进入 hard");
        helper.assertTrue(GateResult.LEVEL_TOO_LOW.name().equals(result.get("reasonCode").getAsString()),
                "原因码取自 GateResult, 实得 " + result.get("reasonCode").getAsString());
        helper.assertTrue(GateResult.LEVEL_TOO_LOW.reasonKey().equals(result.get("reasonKey").getAsString()),
                "i18n 键复用命令路径那一句 message.miningdim.gate.level_too_low, 实得 "
                        + result.get("reasonKey").getAsString());
        helper.assertTrue(result.get("requiredMinerLevel").getAsInt() == MinerConstants.HARD_MIN_MINER_LEVEL
                        && result.get("requiredMinerLevel").getAsInt() == 8,
                "hard 的门槛恒发 8, 实得 " + result.get("requiredMinerLevel").getAsInt());
        helper.assertTrue(result.get("minerLevel").getAsInt() == 1, "回执带的是发送者真实矿工等级");
        helper.assertTrue(!data.hasFallback(),
                "被门控拒绝的一次不得写回退现场 (写了说明 requestEnter 已被调用, 玩家的回退点会被覆盖)");
        helper.succeed();
    }

    /** 已在实例内: 同样在进入链路之前拒掉, 且复用 requestEnter 那句 i18n 键。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningEnterRejectsWhenAlreadyInsideAnInstance(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, MinerConstants.MAX_LEVEL);
        IMiningPlayerData data = dataOf(player);
        data.setCurrentInstanceId(77L);

        JsonObject result = handle(helper, ENTER_ACTION, player, enterPayload("easy"));
        helper.assertTrue(!result.get("accepted").getAsBoolean(), "已在实例内不得再受理一次入场");
        helper.assertTrue("ALREADY_INSIDE".equals(result.get("reasonCode").getAsString()),
                "实得 " + result.get("reasonCode").getAsString());
        helper.assertTrue("message.miningdim.enter.already_inside".equals(result.get("reasonKey").getAsString()),
                "实得 " + result.get("reasonKey").getAsString());
        helper.assertTrue(!data.hasFallback(),
                "被拒的一次不得写回退现场 —— 覆盖掉它等于把玩家真正的回退点丢了");
        helper.assertTrue(data.currentInstanceId() == 77L, "被拒的一次不得改动实例指针");
        data.clearMiningState();
        helper.succeed();
    }

    /**
     * 受理路径必须真的走 {@code EntryGateway.requestEnter}。
     *
     * 判据是回退现场快照: 它只在 requestEnter 内部、门控通过之后写。把这一行换成"自己 allocate 一下"
     * (存量 SelectZoneC2S 与 command 包那两条路径正是这么写的, 且从不真的传送玩家), 本条立刻挂。
     *
     * 收尾把 mock 玩家摘出 PlayerList: allocate 的回调下一 tick 才跑, 那时找不到玩家就会整单放弃 ——
     * 否则这次入场会在批次剩余时间里真的把它传进矿洞维度, 并一直吃压力/陷阱 tick。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningEnterDelegatesToTheAuthoritativeGatewayPath(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, MinerConstants.MEDIUM_MIN_MINER_LEVEL);
        IMiningPlayerData data = dataOf(player);
        BlockPos before = player.blockPosition();
        ResourceKey<Level> dimensionBefore = player.level().dimension();
        helper.assertTrue(!data.hasFallback(), "前提校验: 调用之前没有回退现场快照");

        JsonObject result = handle(helper, ENTER_ACTION, player, enterPayload("medium"));

        boolean fallbackWritten = data.hasFallback();
        BlockPos snapshotPos = data.prevPos();
        ResourceKey<Level> snapshotDim = data.prevDimension();
        // 取消这次入场 (见方法注释)。必须在断言之前做, 断言失败也不留一条会真传送的悬挂任务。
        helper.getLevel().getServer().getPlayerList().remove(player);

        helper.assertTrue(result.get("accepted").getAsBoolean(),
                "矿工 4 级进 medium 应被受理, 实得 " + result);
        helper.assertTrue(result.get("reasonCode").isJsonNull() && result.get("reasonKey").isJsonNull(),
                "受理时两个原因字段必须是 JSON null (契约是 string|null, 缺键前端会拿到 undefined)");
        helper.assertTrue("medium".equals(result.get("difficulty").getAsString()), "回执回显目标难度");
        helper.assertTrue(!result.get("instanceId").isJsonNull(),
                "R1 下 medium 的常驻区域必然存在, 回执应带上它的 id");
        helper.assertTrue(fallbackWritten,
                "受理必须经 EntryGateway.requestEnter (它写回退现场快照); 自己 allocate 一下不算");
        helper.assertTrue(before.equals(snapshotPos),
                "回退现场记的是发起入场那一刻的坐标, 应为 " + before + ", 实得 " + snapshotPos);
        helper.assertTrue(dimensionBefore.equals(snapshotDim),
                "回退现场记的是发起入场那一刻的维度, 实得 " + snapshotDim.location());
        helper.succeed();
    }

    /** 难度入参: 缺字段与取值域外都回 INVALID_REQUEST, 后者带 field+value 让前端定位到控件。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningEnterRejectsMissingAndUnknownDifficulty(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, MinerConstants.MAX_LEVEL);

        WebUiBusinessException missing = rejection(helper, ENTER_ACTION, player, new JsonObject());
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(missing.errorCode()),
                "缺 difficulty 应回 INVALID_REQUEST, 实得 " + missing.errorCode());
        helper.assertTrue("difficulty".equals(missing.params().get("field")),
                "缺字段拒绝必须指出是哪个字段, 实得 " + missing.params());

        WebUiBusinessException unknown = rejection(helper, ENTER_ACTION, player, enterPayload("nightmare"));
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(unknown.errorCode()),
                "未知难度应回 INVALID_REQUEST, 实得 " + unknown.errorCode());
        helper.assertTrue("difficulty".equals(unknown.params().get("field"))
                        && "nightmare".equals(unknown.params().get("value")),
                "取值域外拒绝必须回显被拒的值, 实得 " + unknown.params());

        // 大小写不敏感, 与 Difficulty.byConfigName 同口径 (否则命令能进、面板进不去)。
        // 先把玩家标成"已在实例内": 这次调用于是停在同步拒绝上, 既验到了解析口径, 又不会真启动一次入场。
        dataOf(player).setCurrentInstanceId(9L);
        JsonObject upper = handle(helper, ENTER_ACTION, player, enterPayload("EASY"));
        helper.assertTrue("easy".equals(upper.get("difficulty").getAsString()),
                "难度名大小写不敏感且回执回显规范名, 实得 " + upper.get("difficulty").getAsString());
        helper.assertTrue("ALREADY_INSIDE".equals(upper.get("reasonCode").getAsString()),
                "前提校验: 这次调用应停在同步拒绝上, 实得 " + upper.get("reasonCode").getAsString());
        dataOf(player).clearMiningState();
        helper.succeed();
    }

    // ============================================================
    // 4. mining.leave
    // ============================================================

    /** 本就不在矿洞: left=false + NOT_INSIDE, 复用命令路径那句 i18n 键。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningLeaveReportsNotInsideForAPlayerWhoNeverEntered(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject result = handle(helper, LEAVE_ACTION, player, new JsonObject());
        helper.assertTrue(!result.get("left").getAsBoolean(), "没进过矿洞的玩家离不开");
        helper.assertTrue("NOT_INSIDE".equals(result.get("reasonCode").getAsString()),
                "实得 " + result.get("reasonCode").getAsString());
        helper.assertTrue("message.miningdim.leave.not_inside".equals(result.get("reasonKey").getAsString()),
                "实得 " + result.get("reasonKey").getAsString());
        helper.succeed();
    }

    /**
     * 离开必须委派 {@code EntrySystem.leaveToFallback}: 真把玩家送回回退坐标, 并清掉矿山运行态。
     *
     * 面板层若只回一句"已离开"而不传送 (或只传送不清 currentInstanceId), 两条断言分别挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningLeaveTeleportsBackToTheFallbackAndClearsMiningState(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setNoGravity(true);
        IMiningPlayerData data = dataOf(player);
        BlockPos fallback = player.blockPosition().offset(3, 0, 3);
        data.snapshotFallback(player.level().dimension(), fallback, player.gameMode.getGameModeForPlayer());
        // 用一个必不存在的实例 id: 真实的三块常驻区域不该因为一条测试而被改动在场集合。
        data.setCurrentInstanceId(Long.MAX_VALUE);
        data.setDanger(0.8f);
        data.setSpawnFreezeUntil(player.serverLevel().getGameTime() + 500L);

        JsonObject result = handle(helper, LEAVE_ACTION, player, new JsonObject());
        helper.assertTrue(result.get("left").getAsBoolean(), "在实例内的玩家应离开成功");
        helper.assertTrue(result.get("reasonCode").isJsonNull() && result.get("reasonKey").isJsonNull(),
                "成功时两个原因字段是 JSON null");
        helper.assertTrue(fallback.equals(player.blockPosition()),
                "必须真的传送回回退坐标 " + fallback + ", 实得 " + player.blockPosition());
        helper.assertTrue(data.currentInstanceId() == IMiningPlayerData.NO_INSTANCE,
                "离开后实例指针必须清成哨兵 -1, 实得 " + data.currentInstanceId());
        helper.assertTrue(data.danger() == 0.0f && data.spawnFreezeUntil() == 0L,
                "离开后 danger 与出生保护一并清空 (clearMiningState 的完整语义)");
        helper.succeed();
    }

    // ============================================================
    // 5. 注册与翻译键
    // ============================================================

    /**
     * 四条 action 必须由 {@code EntrySystem.register} 那行 registerAll 挂上。
     *
     * 本类<b>不</b>自行注册 (派发器注册表是进程级静态且 register 遇重名直接抛): 测试里补注册一次, 就等于
     * 把"接线漏了"这个缺陷永久掩盖成绿灯。删掉 EntrySystem 里那行, 本条与本类其余用例一起挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningActionsAreRegisteredUnderContractNames(GameTestHelper helper) {
        for (String action : new String[]{OVERVIEW_ACTION, MY_STATUS_ACTION, ENTER_ACTION, LEAVE_ACTION}) {
            helper.assertTrue(WebUiServerDispatcher.resolve(action) != null,
                    action + " 必须由 EntrySystem.register 调 MiningWebUiActions.registerAll 注册进派发器");
        }
        helper.assertTrue(WebUiServerDispatcher.resolve("mining.status") == null
                        && WebUiServerDispatcher.resolve("mining.instances") == null,
                "不得注册契约外的别名 action (前端契约表里只有四条 mining.*)");
        helper.succeed();
    }

    /** 服务端只发翻译键, 键在两份 lang 里都必须有条目, 否则玩家看到的是一串原始键。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningReasonAndNameKeysExistInBothLangFiles(GameTestHelper helper) {
        JsonObject zh = loadJsonResource("/assets/miningdim/lang/zh_cn.json");
        JsonObject en = loadJsonResource("/assets/miningdim/lang/en_us.json");

        for (Difficulty difficulty : Difficulty.values()) {
            String key = "difficulty.miningdim." + difficulty.configName();
            helper.assertTrue(zh.has(key) && en.has(key), "两份 lang 都必须有难度名翻译键 " + key);
        }
        for (String key : new String[]{
                GateResult.LEVEL_TOO_LOW.reasonKey(),
                "message.miningdim.enter.already_inside",
                "message.miningdim.enter.hard_death_drops",
                "message.miningdim.leave.not_inside"}) {
            helper.assertTrue(zh.has(key) && en.has(key), "两份 lang 都必须有玩家提示翻译键 " + key);
        }
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    private static JsonObject handle(GameTestHelper helper, String action, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(handler(helper, action).handle(sender, payload)).getAsJsonObject();
    }

    /** 调 action 并要求它抛业务拒绝; 没抛就地判失败。 */
    private static WebUiBusinessException rejection(GameTestHelper helper, String action,
                                                    ServerPlayer sender, JsonObject payload) {
        try {
            handler(helper, action).handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + action);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static WebUiAction handler(GameTestHelper helper, String action) {
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器 (EntrySystem.register 漏了 registerAll?)");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }

    private static JsonObject enterPayload(String difficulty) {
        JsonObject payload = new JsonObject();
        payload.addProperty("difficulty", difficulty);
        return payload;
    }

    private static IMiningPlayerData dataOf(ServerPlayer player) {
        return MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"));
    }

    private static void setMinerLevel(ServerPlayer player, int level) {
        dataOf(player).jobProgress(JobId.MINER).setLevel(level);
    }

    private static JsonObject loadJsonResource(String path) {
        try (InputStream in = MiningWebUiGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("JSON resource not found on classpath: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("failed reading JSON resource: " + path, e);
        }
    }
}
