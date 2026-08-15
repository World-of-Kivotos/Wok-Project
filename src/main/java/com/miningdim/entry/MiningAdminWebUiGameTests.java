package com.miningdim.entry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.Difficulty;
import com.miningdim.core.GenState;
import com.miningdim.core.IResetService;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * W10 矿洞管理台的 admin.mining.reset GameTest。
 *
 * 三条主线:
 *  1. OP 门: 非 OP 一律被拦在<b>任何</b>业务之前 (连 payload 都不该被解析);
 *  2. 前置裁决 ({@link MiningAdminWebUiActions#planReset}): 与 {@code ResetSystem.reset} 的三条前置校验逐条对齐。
 *     它是纯函数, 故用合成的 {@link InstanceState} 穷举边界 —— 真去重置一块 256x384x256 的常驻区域会在整个
 *     测试批次里持续吃 IO/CPU, 那不是测试该干的事;
 *  3. 委派: 受理后必须真把 (instanceId, mode) 交给 {@link IResetService}, 默认 NEW_SEED (换图, 与
 *     {@code /mining reset all} 和定时自动刷新同口径), {@code reseed:false} 才是 SAME_SEED。
 *     委派用一个记录型假门面接住, 全程不触发真实重置。
 *
 * 二次确认<b>不在</b>本层: 活跃的 /mining reset 就没有二次确认 (带确认的那套在 com.miningdim.command 死代码里),
 * 服务端照旧不加。故这里没有"未确认必须被拒"的用例 —— 那是前端弹窗的责任, 测了反而是把假契约写死。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MiningAdminWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w10";

    private static final String RESET_ACTION = "admin.mining.reset";

    // ============================================================
    // 1. OP 门
    // ============================================================

    /**
     * 非 OP: 权限门必须排在最前 —— 连"难度写错了"这种业务校验都不该先于它触发, 否则未授权者能靠回执差异
     * 反推服务端状态。op() 之后同一次调用被放行 (走假门面, 不触发真实重置)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminMiningResetRequiresOperatorBeforeAnythingElse(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        helper.assertTrue(!helper.getLevel().getServer().getPlayerList().isOp(player.getGameProfile()),
                "前提校验: 新建的 mock 玩家不是 OP");

        InstanceState easy = requireFixedInstance(helper, Difficulty.EASY);
        GenState originalState = easy.genState();
        RecordingResetService fake = new RecordingResetService();
        IResetService previous = MiningServices.resetService();
        try {
            // 假门面先就位再测权限门: 万一权限门真的漏了, 这次调用也只落在假门面上, 不会真去重置一整块区域。
            MiningServices.registerResetService(fake);
            // 空 payload: 若权限门排在解析之后, 抛出来的会是 INVALID_REQUEST 而不是权限拒绝。
            assertDeniedAsNonOp(helper, player, new JsonObject());
            assertDeniedAsNonOp(helper, player, resetPayload("easy"));
            helper.assertTrue(fake.resetCalls == 0,
                    "非 OP 的两次调用一次都不许下发重置, 实得 " + fake.resetCalls + " 次");

            helper.getLevel().getServer().getPlayerList().op(player.getGameProfile());
            easy.setGenState(GenState.READY);
            JsonObject result = handle(helper, player, resetPayload("easy"));
            helper.assertTrue(result.get("accepted").getAsBoolean(),
                    "OP 的同一次调用应被放行, 实得 " + result);
            helper.assertTrue(fake.resetCalls == 1,
                    "放行后必须真把重置交给 IResetService, 实得调用 " + fake.resetCalls + " 次");
        } finally {
            easy.setGenState(originalState);
            MiningServices.registerResetService(previous);
            helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
        }
        helper.succeed();
    }

    // ============================================================
    // 2. 前置裁决 (纯函数, 穷举边界)
    // ============================================================

    /**
     * planReset 的四种在场组合 + 两种不可重置状态。
     *
     * 判据必须与 {@code ResetSystem.reset} 的前置校验一致, 否则面板会回一条"已受理"而 future 在后台
     * 静默失败 —— 玩家点完按钮只会看到区域纹丝不动。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void resetPlanMatchesTheResetServicePreconditions(GameTestHelper helper) {
        // 一、READY 且无人: 直接受理, 不清场, 0 人被踢。
        MiningAdminWebUiActions.ResetPlan empty =
                MiningAdminWebUiActions.planReset(synthetic(GenState.READY, 0), true, true);
        helper.assertTrue(empty.accepted() && empty.reasonCode() == null,
                "空区域必须直接受理, 实得 " + empty);
        helper.assertTrue(empty.evictedPlayers() == 0 && !empty.evacuateFirst(),
                "空区域不需要清场, 实得 " + empty);

        // 二、READY 有人 + 允许强制清场: 受理, 先清场, 被踢人数 = 在场人数。
        MiningAdminWebUiActions.ResetPlan kick =
                MiningAdminWebUiActions.planReset(synthetic(GenState.READY, 3), true, true);
        helper.assertTrue(kick.accepted() && kick.evacuateFirst(),
                "允许强制清场时应受理并先撤离, 实得 " + kick);
        helper.assertTrue(kick.evictedPlayers() == 3,
                "被踢人数 = 裁决那一刻的在场人数 3, 实得 " + kick.evictedPlayers());

        // 三、READY 有人 + 不许强制清场 + requireEmpty: 必须拒, 否则 reset 只会返回 failedFuture。
        MiningAdminWebUiActions.ResetPlan occupied =
                MiningAdminWebUiActions.planReset(synthetic(GenState.READY, 2), true, false);
        helper.assertTrue(!occupied.accepted() && "OCCUPIED".equals(occupied.reasonCode()),
                "有人在场又不许清场时必须同步拒绝, 实得 " + occupied);
        helper.assertTrue(occupied.evictedPlayers() == 2 && !occupied.evacuateFirst(),
                "被拒时不得清场, 但仍如实回报在场人数, 实得 " + occupied);

        // 四、READY 有人 + 不许强制清场 + 不要求清空: 配置允许带人重置, 那就受理且不清场。
        MiningAdminWebUiActions.ResetPlan allowOccupied =
                MiningAdminWebUiActions.planReset(synthetic(GenState.READY, 2), false, false);
        helper.assertTrue(allowOccupied.accepted() && !allowOccupied.evacuateFirst(),
                "requireEmpty 关闭时有人也能重置且不强制清场, 实得 " + allowOccupied);

        // 五、生成中 / 重置中: 一律拒 (13.2 只有 READY 与 READY_FALLBACK 可重置)。
        for (GenState state : new GenState[]{GenState.PENDING, GenState.GENERATING, GenState.RESETTING,
                GenState.FAILED, GenState.RECYCLED}) {
            MiningAdminWebUiActions.ResetPlan blocked =
                    MiningAdminWebUiActions.planReset(synthetic(state, 0), true, true);
            helper.assertTrue(!blocked.accepted() && "NOT_RESETTABLE".equals(blocked.reasonCode()),
                    state + " 状态不可重置, 实得 " + blocked);
        }
        // 六、降级就绪 (READY_FALLBACK) 仍可重置 —— 它是"能进人"的状态之一。
        MiningAdminWebUiActions.ResetPlan fallbackReady =
                MiningAdminWebUiActions.planReset(synthetic(GenState.READY_FALLBACK, 0), true, true);
        helper.assertTrue(fallbackReady.accepted(),
                "READY_FALLBACK 同样可重置 (isEnterable 的两个状态), 实得 " + fallbackReady);
        helper.succeed();
    }

    // ============================================================
    // 3. 委派与回执
    // ============================================================

    /**
     * 受理时交给 IResetService 的 (instanceId, mode) 必须与回执逐字一致, 默认换图。
     *
     * 把 {@code reset(...)} 那行删掉 (只回一条"已受理"), 或把默认模式改成 SAME_SEED, 本条立刻挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminMiningResetHandsTheFixedRegionToTheResetService(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        InstanceState hard = requireFixedInstance(helper, Difficulty.HARD);
        GenState originalState = hard.genState();
        int occupantsBefore = hard.refCount();
        RecordingResetService fake = new RecordingResetService();
        IResetService previous = MiningServices.resetService();
        helper.getLevel().getServer().getPlayerList().op(player.getGameProfile());
        try {
            MiningServices.registerResetService(fake);
            // 真实 genState 随离线生成进度漂移, 靠它会让本条时而测受理时而测拒绝; 钉死后在 finally 还原。
            hard.setGenState(GenState.READY);

            JsonObject result = handle(helper, player, resetPayload("hard"));
            helper.assertTrue(result.get("accepted").getAsBoolean(), "空闲的 hard 区域应被受理, 实得 " + result);
            helper.assertTrue("hard".equals(result.get("difficulty").getAsString()), "回执回显目标难度");
            helper.assertTrue(result.get("instanceId").getAsLong() == hard.instanceId(),
                    "回执里的实例 id 必须是 hard 那块常驻区域 " + hard.instanceId()
                            + ", 实得 " + result.get("instanceId").getAsLong());
            helper.assertTrue("NEW_SEED".equals(result.get("mode").getAsString()),
                    "面板默认换图 (与 /mining reset all 和定时自动刷新同口径), 实得 "
                            + result.get("mode").getAsString());
            helper.assertTrue(result.get("evictedPlayers").getAsInt() == occupantsBefore,
                    "被踢人数 = 受理那一刻该区域的在场人数 " + occupantsBefore
                            + ", 实得 " + result.get("evictedPlayers").getAsInt());
            helper.assertTrue(result.get("reasonCode").isJsonNull(),
                    "受理时 reasonCode 必须是 JSON null (契约 string|null, 缺键前端会拿到 undefined)");

            helper.assertTrue(fake.resetCalls == 1 && fake.lastInstanceId == hard.instanceId(),
                    "必须把这块区域交给 IResetService.reset, 实得 " + fake.resetCalls + " 次 / id="
                            + fake.lastInstanceId);
            helper.assertTrue(fake.lastMode == IResetService.ResetMode.NEW_SEED,
                    "默认模式是 NEW_SEED, 实得 " + fake.lastMode);
            helper.assertTrue(fake.evacuateCalls == (occupantsBefore > 0 ? 1 : 0),
                    "只有真有人在场才清场, 在场 " + occupantsBefore + " 人却清了 " + fake.evacuateCalls + " 次");

            // reseed=false 才是原样重建 (确定性验收用)。
            JsonObject sameSeed = handle(helper, player, resetPayload("hard", false));
            helper.assertTrue("SAME_SEED".equals(sameSeed.get("mode").getAsString()),
                    "reseed=false 应走 SAME_SEED, 实得 " + sameSeed.get("mode").getAsString());
            helper.assertTrue(fake.resetCalls == 2 && fake.lastMode == IResetService.ResetMode.SAME_SEED,
                    "第二次同样必须真下发, 实得 " + fake.resetCalls + " 次 / " + fake.lastMode);
        } finally {
            hard.setGenState(originalState);
            MiningServices.registerResetService(previous);
            helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
        }
        helper.succeed();
    }

    /**
     * 不可重置时: 同步回执说清原因, 且<b>绝不</b>下发给 IResetService。
     *
     * 少了这道同步判定, 面板会回"已受理"而 future 在后台以 IllegalStateException 静默失败。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminMiningResetRefusesAnInstanceThatIsAlreadyResetting(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        InstanceState medium = requireFixedInstance(helper, Difficulty.MEDIUM);
        GenState originalState = medium.genState();
        RecordingResetService fake = new RecordingResetService();
        IResetService previous = MiningServices.resetService();
        helper.getLevel().getServer().getPlayerList().op(player.getGameProfile());
        try {
            MiningServices.registerResetService(fake);
            medium.setGenState(GenState.RESETTING);

            JsonObject result = handle(helper, player, resetPayload("medium"));
            helper.assertTrue(!result.get("accepted").getAsBoolean(), "正在重置的区域不得再受理一次重置");
            helper.assertTrue("NOT_RESETTABLE".equals(result.get("reasonCode").getAsString()),
                    "实得 " + result.get("reasonCode").getAsString());
            helper.assertTrue("RESETTING".equals(result.get("genState").getAsString()),
                    "回执必须带上当时的真实状态供运维判断, 实得 " + result.get("genState").getAsString());
            helper.assertTrue(fake.resetCalls == 0 && fake.evacuateCalls == 0,
                    "被拒的一次绝不能下发重置或清场, 实得 reset " + fake.resetCalls
                            + " 次 / evacuate " + fake.evacuateCalls + " 次");
        } finally {
            medium.setGenState(originalState);
            MiningServices.registerResetService(previous);
            helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
        }
        helper.succeed();
    }

    /** 难度入参: 缺字段/取值域外都回 INVALID_REQUEST + field/value, 且在触碰任何实例之前。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminMiningResetRejectsMissingAndUnknownDifficulty(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingResetService fake = new RecordingResetService();
        IResetService previous = MiningServices.resetService();
        helper.getLevel().getServer().getPlayerList().op(player.getGameProfile());
        try {
            MiningServices.registerResetService(fake);

            WebUiBusinessException missing = rejection(helper, player, new JsonObject());
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(missing.errorCode())
                            && "difficulty".equals(missing.params().get("field")),
                    "缺 difficulty 应回 INVALID_REQUEST + field, 实得 " + missing.errorCode() + missing.params());

            WebUiBusinessException unknown = rejection(helper, player, resetPayload("insane"));
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(unknown.errorCode())
                            && "insane".equals(unknown.params().get("value")),
                    "未知难度应回显被拒的值, 实得 " + unknown.errorCode() + unknown.params());

            JsonObject badFlag = resetPayload("easy");
            badFlag.addProperty("reseed", "yes");
            WebUiBusinessException wrongType = rejection(helper, player, badFlag);
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(wrongType.errorCode())
                            && "reseed".equals(wrongType.params().get("field")),
                    "reseed 只收布尔, 字符串必须被拒而不是被当成 false, 实得 "
                            + wrongType.errorCode() + wrongType.params());

            helper.assertTrue(fake.resetCalls == 0 && fake.evacuateCalls == 0,
                    "入参被拒时绝不能触碰实例, 实得 reset " + fake.resetCalls + " 次");
        } finally {
            MiningServices.registerResetService(previous);
            helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminMiningResetIsRegisteredUnderTheContractName(GameTestHelper helper) {
        helper.assertTrue(WebUiServerDispatcher.resolve(RESET_ACTION) != null,
                RESET_ACTION + " 必须由 EntrySystem.register 调 MiningAdminWebUiActions.registerAll 注册进派发器");
        helper.assertTrue(WebUiServerDispatcher.resolve("admin.mining.resetAll") == null,
                "本组只交付一条 admin.mining.reset, 不得顺手注册契约外的动作");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 记录型假重置门面: 只记调用, 绝不真去删区块重生成 (真重置会拖垮整个测试批次)。 */
    private static final class RecordingResetService implements IResetService {

        private int resetCalls;
        private int evacuateCalls;
        private long lastInstanceId = Long.MIN_VALUE;
        private ResetMode lastMode;

        @Override
        public CompletableFuture<Void> reset(long instanceId, ResetMode mode) {
            resetCalls++;
            lastInstanceId = instanceId;
            lastMode = mode;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void evacuate(InstanceState instance, MinecraftServer server) {
            evacuateCalls++;
        }
    }

    /** 合成实例 (只喂给纯函数裁决, 不进注册表): 指定生成状态与在场人数。 */
    private static InstanceState synthetic(GenState state, int occupants) {
        InstanceState inst = new InstanceState(1L, 0L, Difficulty.EASY, RegionBox.ofDefault(0, 0),
                null, true, 0L, state);
        for (int i = 0; i < occupants; i++) {
            inst.playerSet().add(UUID.randomUUID());
        }
        return inst;
    }

    private static InstanceState requireFixedInstance(GameTestHelper helper, Difficulty difficulty) {
        InstanceState inst = MiningWebUiActions.fixedInstanceFor(difficulty);
        if (inst == null) {
            helper.fail("前提校验: R1 下 " + difficulty.configName() + " 的常驻区域必须在开服重建时就已存在");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return inst;
    }

    private static JsonObject resetPayload(String difficulty) {
        JsonObject payload = new JsonObject();
        payload.addProperty("difficulty", difficulty);
        return payload;
    }

    private static JsonObject resetPayload(String difficulty, boolean reseed) {
        JsonObject payload = resetPayload(difficulty);
        payload.addProperty("reseed", reseed);
        return payload;
    }

    private static JsonObject handle(GameTestHelper helper, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(handler(helper).handle(sender, payload)).getAsJsonObject();
    }

    private static WebUiBusinessException rejection(GameTestHelper helper, ServerPlayer sender, JsonObject payload) {
        try {
            handler(helper).handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + RESET_ACTION);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    /**
     * 非 OP 必须被权限门 (而不是业务校验) 拦下。撞上 WebUiBusinessException 即说明权限门排在了解析之后。
     */
    /**
     * 非 OP 必须拿到 {@link WebUiErrorCodes#PERMISSION_DENIED}。
     *
     * 断言的是"码是 PERMISSION_DENIED"而不是"码不是 INVALID_REQUEST", 是因为本用例还兼着验权限门的<b>次序</b>:
     * 调用方会先送一个空 payload 进来, 若权限门排在入参解析之后, 拿到的就会是 INVALID_REQUEST (缺 difficulty)。
     * 两者都不放行, 但只有前者说明"没 OP 权限的人连入参都不该被解析"。
     */
    private static void assertDeniedAsNonOp(GameTestHelper helper, ServerPlayer sender, JsonObject payload) {
        try {
            handler(helper).handle(sender, payload);
        } catch (WebUiBusinessException denied) {
            helper.assertTrue(WebUiErrorCodes.PERMISSION_DENIED.equals(denied.errorCode()),
                    "非 OP 应先被权限门拦住 (PERMISSION_DENIED), 实得 " + denied.errorCode());
            helper.assertTrue("admin.mining.reset".equals(denied.params().get("action")),
                    "权限拒绝要带 action 供前端定位是哪个后台功能被拦, 实得 " + denied.params());
            return;
        }
        helper.fail("非 OP 调用 admin.mining.reset 竟然成功返回了");
    }

    private static WebUiAction handler(GameTestHelper helper) {
        WebUiAction handler = WebUiServerDispatcher.resolve(RESET_ACTION);
        if (handler == null) {
            helper.fail("action " + RESET_ACTION + " 未注册进派发器 (EntrySystem.register 漏了 registerAll?)");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }
}
