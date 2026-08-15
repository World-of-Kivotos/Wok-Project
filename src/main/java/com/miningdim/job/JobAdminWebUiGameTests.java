package com.miningdim.job;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * W10 职业管理台的 admin.job.setLevel GameTest。
 *
 * 四条主线 (删掉被测那段逻辑必挂):
 *  1. <b>OP 门</b>: 非 OP 一律被拒, 且被拒时目标玩家的等级一个字节都没变 (门必须早于副作用);
 *  2. <b>改级的完整副作用</b>: level 与累计经验一起被对齐到该级整级线 —— 只改 level 不改 xp 的实现会在下一次
 *     入账时被 JobXpCurve 按旧 xp 重新派生回原等级, 等于白改;
 *  3. <b>syncTo</b>: 改完必须真的往目标玩家的连接里下发同步包 (直接数出站包增量), 否则玩家的客户端镜像会一直
 *     停在旧等级直到重登;
 *  4. <b>入参拒绝</b>: 未知职业 id / 越界等级 / 离线玩家各自以 INVALID_REQUEST + params.field 明确报错, 且一律
 *     零副作用, 不许静默假成功。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class JobAdminWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w10";

    private static final String SET_LEVEL_ACTION = "admin.job.setLevel";

    /** {@link MockGameTestPlayers} 给每个 mock 玩家的固定档案名 (admin 动作按名字找目标玩家)。 */
    private static final String MOCK_PLAYER_NAME = "test-mock-player";

    // ============================================================
    // 1. OP 门
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void setLevelRejectsNonOpBeforeTouchingAnyProgress(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer target = resolveTarget(helper, player);
        int before = progress(target, JobId.CHEF).level();
        try {
            helper.assertTrue(!helper.getLevel().getServer().getPlayerList().isOp(player.getGameProfile()),
                    "前置条件: 刚造的 mock 玩家不是 OP");

            boolean rejected = false;
            try {
                handler(helper).handle(player, payload(MOCK_PLAYER_NAME, JobId.CHEF.id(), 9));
            } catch (RuntimeException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "非 OP 调 admin.job.setLevel 必须被拒");
            helper.assertTrue(progress(target, JobId.CHEF).level() == before,
                    "被 OP 门拒掉的调用不许留下任何副作用, 等级应仍为 " + before
                            + ", 实得 " + progress(target, JobId.CHEF).level());

            // op() 之后同一条请求放行, 证明刚才拒的确实是权限而不是别的入参问题。
            helper.getLevel().getServer().getPlayerList().op(player.getGameProfile());
            handler(helper).handle(player, payload(MOCK_PLAYER_NAME, JobId.CHEF.id(), 9));
            helper.assertTrue(progress(target, JobId.CHEF).level() == 9,
                    "OP 放行后厨师应为 9 级, 实得 " + progress(target, JobId.CHEF).level());
            helper.succeed();
        } finally {
            helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
            progress(target, JobId.CHEF).setLevel(before);
        }
    }

    // ============================================================
    // 2. 改级的完整副作用 + 3. syncTo
    // ============================================================

    /**
     * 改级必须把累计经验一起对齐到整级线, 并把改后的真值回执出去。
     *
     * 升级与<b>降级</b>各测一次: 只写 level 不写 xp 的实现在降级这一步会被 JobProgress 的曲线派生打回原形。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void setLevelAlignsXpToTheLevelLineBothWays(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer target = resolveTarget(helper, player);
        int before = progress(target, JobId.MINER).level();
        // 旁证用的对照职业: 取当前真值而不是假定它是 1 级 —— 同批别的用例可能已经动过这个 mock 玩家。
        int untouchedChefLevel = progress(target, JobId.CHEF).level();
        helper.getLevel().getServer().getPlayerList().op(player.getGameProfile());
        try {
            JsonObject up = call(helper, player, MOCK_PLAYER_NAME, JobId.MINER.id(), 8);
            helper.assertTrue(JobId.MINER.id().equals(up.get("jobId").getAsString())
                            && MOCK_PLAYER_NAME.equals(up.get("playerName").getAsString())
                            && target.getUUID().toString().equals(up.get("playerUuid").getAsString()),
                    "回执必须指明改的是哪个玩家的哪个职业, 实得 " + up);
            helper.assertTrue(up.get("level").getAsInt() == 8,
                    "回执等级必须是 8, 实得 " + up.get("level").getAsInt());
            helper.assertTrue(up.get("totalXp").getAsLong() == JobXpCurve.cumulativeXpForLevel(8),
                    "回执累计经验必须被对齐到 8 级整级线 " + JobXpCurve.cumulativeXpForLevel(8)
                            + ", 实得 " + up.get("totalXp").getAsLong());
            helper.assertTrue(progress(target, JobId.MINER).level() == 8
                            && progress(target, JobId.MINER).xp(JobId.MINER) == JobXpCurve.cumulativeXpForLevel(8),
                    "落到 capability 上的也必须是这一对真值 (回执不许自说自话)");

            // 降级: xp 必须跟着降回 2 级线, 否则曲线会立刻把等级派生回 8。
            JsonObject down = call(helper, player, MOCK_PLAYER_NAME, JobId.MINER.id(), 2);
            helper.assertTrue(down.get("level").getAsInt() == 2
                            && down.get("totalXp").getAsLong() == JobXpCurve.cumulativeXpForLevel(2),
                    "降级同样要把累计经验对齐到 2 级线, 实得 level=" + down.get("level").getAsInt()
                            + " totalXp=" + down.get("totalXp").getAsLong());
            helper.assertTrue(JobXpCurve.levelForTotalXp(progress(target, JobId.MINER).xp(JobId.MINER)) == 2,
                    "按落盘的累计经验重新派生等级必须仍是 2 级 (证明 xp 真的降了)");

            // 别的职业不许被顺手改动 (setLevel 只动指定的那一个 EnumMap 槽)。
            helper.assertTrue(progress(target, JobId.CHEF).level() == untouchedChefLevel,
                    "只改矿工, 厨师必须原封不动 (仍为 " + untouchedChefLevel + " 级), 实得 "
                            + progress(target, JobId.CHEF).level());
            helper.succeed();
        } finally {
            helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
            progress(target, JobId.MINER).setLevel(before);
        }
    }

    /**
     * 改完必须真的下发 S2C 同步包。
     *
     * 直接数目标玩家连接的出站队列增量: mock 玩家的 Connection 挂在一个 {@link EmbeddedChannel} 上, 所有下行包
     * 都留在它的出站队列里。本条 action 的调用路径上唯一的发包点就是 {@code JobFrameworkSystem.syncTo}
     * (直接调 handler, 不经派发器, 故没有回执包) —— 删掉那一行, 增量即为 0, 本条必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void setLevelPushesJobSyncToTheTarget(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer target = resolveTarget(helper, player);
        int before = progress(target, JobId.TAROT).level();
        helper.getLevel().getServer().getPlayerList().op(player.getGameProfile());
        try {
            int outboundBefore = outboundCount(target);
            call(helper, player, MOCK_PLAYER_NAME, JobId.TAROT.id(), 6);
            int outboundAfter = outboundCount(target);
            helper.assertTrue(outboundAfter > outboundBefore,
                    "改级后必须向目标玩家下发职业同步包 (出站包数 " + outboundBefore + " -> " + outboundAfter
                            + "); 没有增量说明 syncTo 没被调用, 玩家客户端会一直显示旧等级");
            helper.assertTrue(progress(target, JobId.TAROT).level() == 6,
                    "前置校验: 这一次改级本身要成功");
            helper.succeed();
        } finally {
            helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
            progress(target, JobId.TAROT).setLevel(before);
        }
    }

    // ============================================================
    // 4. 入参拒绝 (一律零副作用)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void setLevelRejectsBadJobLevelAndOfflinePlayer(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer target = resolveTarget(helper, player);
        int before = progress(target, JobId.ENGINEER).level();
        helper.getLevel().getServer().getPlayerList().op(player.getGameProfile());
        try {
            WebUiBusinessException badJob = expectRejection(helper, player,
                    payload(MOCK_PLAYER_NAME, "wizard", 5));
            assertInvalidField(helper, badJob, "jobId", "wizard");

            WebUiBusinessException tooLow = expectRejection(helper, player,
                    payload(MOCK_PLAYER_NAME, JobId.ENGINEER.id(), JobXpCurve.MIN_LEVEL - 1));
            assertInvalidField(helper, tooLow, "level", Integer.toString(JobXpCurve.MIN_LEVEL - 1));

            WebUiBusinessException tooHigh = expectRejection(helper, player,
                    payload(MOCK_PLAYER_NAME, JobId.ENGINEER.id(), JobXpCurve.MAX_LEVEL + 1));
            assertInvalidField(helper, tooHigh, "level", Integer.toString(JobXpCurve.MAX_LEVEL + 1));

            WebUiBusinessException offline = expectRejection(helper, player,
                    payload("definitely-not-online", JobId.ENGINEER.id(), 5));
            assertInvalidField(helper, offline, "playerName", "definitely-not-online");

            helper.assertTrue(progress(target, JobId.ENGINEER).level() == before,
                    "四次被拒的调用一次副作用都不许留下, 等级应仍为 " + before
                            + ", 实得 " + progress(target, JobId.ENGINEER).level());

            // 边界值本身必须放行 (证明上面拒的是越界而不是把整个区间都拒了)。
            call(helper, player, MOCK_PLAYER_NAME, JobId.ENGINEER.id(), JobXpCurve.MIN_LEVEL);
            helper.assertTrue(progress(target, JobId.ENGINEER).level() == JobXpCurve.MIN_LEVEL,
                    "下界 " + JobXpCurve.MIN_LEVEL + " 级必须放行");
            call(helper, player, MOCK_PLAYER_NAME, JobId.ENGINEER.id(), JobXpCurve.MAX_LEVEL);
            helper.assertTrue(progress(target, JobId.ENGINEER).level() == JobXpCurve.MAX_LEVEL,
                    "上界 " + JobXpCurve.MAX_LEVEL + " 级必须放行");
            helper.succeed();
        } finally {
            helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
            progress(target, JobId.ENGINEER).setLevel(before);
        }
    }

    /** 历史别名 armorer 必须仍解析到 ENGINEER, 且回执发归一化后的稳定 id (前端照回执刷新, 不能拿回 armorer)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void setLevelNormalizesLegacyJobAlias(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer target = resolveTarget(helper, player);
        int before = progress(target, JobId.ENGINEER).level();
        helper.getLevel().getServer().getPlayerList().op(player.getGameProfile());
        try {
            JsonObject result = call(helper, player, MOCK_PLAYER_NAME, "armorer", 4);
            helper.assertTrue(JobId.ENGINEER.id().equals(result.get("jobId").getAsString()),
                    "armorer 必须归一化成 " + JobId.ENGINEER.id() + ", 实得 " + result.get("jobId").getAsString());
            helper.assertTrue(progress(target, JobId.ENGINEER).level() == 4,
                    "别名改的是同一个职业槽, 实得 " + progress(target, JobId.ENGINEER).level());
            helper.succeed();
        } finally {
            helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
            progress(target, JobId.ENGINEER).setLevel(before);
        }
    }

    // ============================================================
    // 5. 注册名
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void setLevelIsRegisteredUnderTheContractName(GameTestHelper helper) {
        ensureAdminActionRegistered();
        helper.assertTrue(WebUiServerDispatcher.resolve(SET_LEVEL_ACTION) != null,
                SET_LEVEL_ACTION + " 必须由 JobAdminWebUiActions.registerAll 注册进派发器");
        helper.assertTrue(WebUiServerDispatcher.resolve("admin.setJobLevel") == null
                        && WebUiServerDispatcher.resolve("job.admin.setLevel") == null,
                "管理台改级只有 admin.job.setLevel 一条 action, 不得另注册别名");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 幂等注册: 派发器注册表是进程级静态, register 用 putIfAbsent 守卫, 重复注册直接抛。
     *
     * 兜底时新造一个 {@link JobFrameworkSystem} 只为拿到 syncTo 这个实例方法 —— 它不读任何实例状态, 与真正
     * 装配的那个实例行为逐字相同 (构造函数只 new 了一个 JobServiceImpl 字段, 无副作用)。
     */
    private static void ensureAdminActionRegistered() {
        if (WebUiServerDispatcher.resolve(SET_LEVEL_ACTION) == null) {
            JobAdminWebUiActions.registerAll(new JobFrameworkSystem());
        }
    }

    private static WebUiAction handler(GameTestHelper helper) {
        ensureAdminActionRegistered();
        WebUiAction resolved = WebUiServerDispatcher.resolve(SET_LEVEL_ACTION);
        if (resolved == null) {
            helper.fail("action " + SET_LEVEL_ACTION + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return resolved;
    }

    private static JsonObject payload(String playerName, String jobId, int level) {
        JsonObject payload = new JsonObject();
        payload.addProperty("playerName", playerName);
        payload.addProperty("jobId", jobId);
        payload.addProperty("level", level);
        return payload;
    }

    private static JsonObject call(GameTestHelper helper, ServerPlayer sender,
                                   String playerName, String jobId, int level) {
        return JsonParser.parseString(handler(helper).handle(sender, payload(playerName, jobId, level)))
                .getAsJsonObject();
    }

    private static WebUiBusinessException expectRejection(GameTestHelper helper, ServerPlayer sender,
                                                          JsonObject payload) {
        try {
            String result = handler(helper).handle(sender, payload);
            helper.fail("本次调用应当被拒绝, 却返回了成功回执: " + result);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static void assertInvalidField(GameTestHelper helper, WebUiBusinessException rejected,
                                           String field, String value) {
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(rejected.errorCode()),
                "错误码必须是 INVALID_REQUEST, 实得 " + rejected.errorCode());
        helper.assertTrue(field.equals(rejected.params().get("field")),
                "params.field 必须指向 " + field + ", 实得 " + rejected.params());
        helper.assertTrue(value.equals(rejected.params().get("value")),
                "params.value 必须回显被拒的值 " + value + ", 实得 " + rejected.params());
    }

    /**
     * 按 action 自己那套口径 (PlayerList 按名查, 取第一个同名者) 解出目标玩家。
     *
     * 不能想当然地拿刚造的那个 mock 玩家: {@link MockGameTestPlayers} 给每个 mock 玩家的档案名都是同一个,
     * 而先前用例造的玩家仍留在 PlayerList 里, action 解出来的很可能是另一个对象。按同一口径解一遍, 断言才
     * 落在真正被改的那份进度上。
     */
    private static ServerPlayer resolveTarget(GameTestHelper helper, ServerPlayer fallback) {
        ServerPlayer resolved = helper.getLevel().getServer().getPlayerList().getPlayerByName(MOCK_PLAYER_NAME);
        if (resolved == null) {
            helper.fail("PlayerList 里找不到 mock 玩家 " + MOCK_PLAYER_NAME
                    + " (期望至少有刚造的 " + fallback.getUUID() + ")");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return resolved;
    }

    private static JobProgress progress(ServerPlayer player, JobId job) {
        IMiningPlayerData data = MiningCapabilities.get(player).orElseThrow(
                () -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"));
        return data.jobProgress(job);
    }

    /** 目标玩家连接的出站包数 (mock 连接挂在 EmbeddedChannel 上, 下行包全留在它的出站队列里)。 */
    private static int outboundCount(ServerPlayer player) {
        Channel channel = player.connection.connection.channel();
        if (!(channel instanceof EmbeddedChannel embedded)) {
            throw new IllegalStateException("mock 玩家的连接不是 EmbeddedChannel, 无法观测下行包: " + channel);
        }
        return embedded.outboundMessages().size();
    }
}
