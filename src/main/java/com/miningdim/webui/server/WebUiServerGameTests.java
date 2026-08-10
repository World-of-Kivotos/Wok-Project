package com.miningdim.webui.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Web UI 服务端派发器纯逻辑 GameTest (Web UI bridge 契约第 6/7 节)。
 *
 * 服务端进程纪律 (契约第 1 节): 本类只触碰 webui.server + network record + testutil + Gson, 严禁 classload
 * 任何 com.miningdim.client.webui.* 渲染类或 MCEF —— 这些只在 Dist.CLIENT 路径加载, GameTest 是服务端进程,
 * 一旦触链即 NoClassDefFoundError。故本测试只验 dispatcher 的查表 + handle 纯逻辑, 不经网络发包 (发包路径
 * MiningNetwork.sendWebUiResponse 在真实游戏内由 register 注册的 CHANNEL 承载, 非纯逻辑可单测)。
 *
 * 强断言 (删被测核心逻辑测试必挂, 禁 is-not-null 弱校验):
 *  - "system.echo" 处理器回送 player == mock 玩家名 / echo == payload.msg / serverTick 为数字且 == 服务器 tick;
 *  - 缺 "msg" 字段时处理器自然抛 (坏输入冒泡, 不静默填默认值);
 *  - 重复注册同名 action 抛 IllegalStateException (装配缺陷暴露);
 *  - 同 sender 同 requestId 第二次派发被判重短路, handler 副作用只发生一次 (防重放红线 6)。
 *
 * 防重放测试 ({@link #duplicateRequestIdShortCircuitsSideEffectOnce}) 走真实 dispatchAndRespond 入口 (去重逻辑
 * 所在的 Gateway), 经 mock 玩家的活动 EmbeddedChannel 回执发包无害落出站队列。其余纯逻辑断言不依赖结构,
 * 用 template = "empty"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WebUiServerGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui";

    /**
     * 注册内置 action 后, 经注册表取 "system.echo" 处理器直接 handle, 强断言响应 JSON 三字段。
     * 用 WebUiServerSubsystem.register 走真实注册路径 (而非测试里另造 handler), 删 handleEcho 逻辑本测试必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void echoActionReturnsPlayerEchoAndTick(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        WebUiServerDispatcher.WebUiAction echo = ensureEchoRegistered(helper);

        JsonObject payload = JsonParser.parseString("{\"msg\":\"hi\"}").getAsJsonObject();
        String resultJson = echo.handle(player, payload);
        JsonObject result = JsonParser.parseString(resultJson).getAsJsonObject();

        // player: 取自服务端校验的 sender.getName(), 等于 mock 玩家名 (非前端传入)。
        helper.assertTrue(result.get("player").getAsString().equals(player.getName().getString()),
                "echo response player must equal the server-resolved sender name '"
                        + player.getName().getString() + "'");
        // echo: 原样回送 payload.msg == "hi"。
        helper.assertTrue(result.get("echo").getAsString().equals("hi"),
                "echo response must echo payload.msg verbatim ('hi')");
        // serverTick: 服务端权威时基, 为数字且等于发送者所在服务器的当前 tick 计数。
        long expectedTick = player.server.getTickCount();
        helper.assertTrue(result.get("serverTick").getAsLong() == expectedTick,
                "echo response serverTick must equal the server's current tick count (" + expectedTick + ")");

        helper.succeed();
    }

    /**
     * 坏输入纪律 (契约第 6 节 / C9): payload 缺 "msg" 时 handleEcho 内 get("msg") 返回 null,
     * 对其 getAsString 自然抛 NullPointerException, 不静默填默认值 —— 异常由 dispatcher 的 Gateway 兜底,
     * 但 handler 自身必须让坏输入冒泡。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void echoActionMissingMsgThrows(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        WebUiServerDispatcher.WebUiAction echo = ensureEchoRegistered(helper);

        JsonObject payloadNoMsg = JsonParser.parseString("{\"other\":1}").getAsJsonObject();
        boolean threw = false;
        try {
            echo.handle(player, payloadNoMsg);
        } catch (RuntimeException e) {
            threw = true;
        }
        helper.assertTrue(threw,
                "echo handler must throw on missing 'msg' (bad input bubbles, no silent default)");
        helper.succeed();
    }

    /**
     * 重复注册同名 action 是装配缺陷 (两个子系统抢同一 action 名): register 必抛 IllegalStateException 暴露,
     * 不静默覆盖。删 putIfAbsent 守卫 (改回 put) 本测试必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void duplicateActionRegistrationThrows(GameTestHelper helper) {
        // 进程级静态注册表跨测试方法/重跑持久, 故用自增 nonce 取唯一 action 名, 保证本次注册前必为空 (测试隔离)。
        String action = "webui.test.duplicate-guard-" + DUP_GUARD_NONCE.getAndIncrement();
        helper.assertTrue(WebUiServerDispatcher.resolve(action) == null,
                "the freshly-named duplicate-guard action must not be pre-registered");

        WebUiServerDispatcher.WebUiAction noop = (sender, payload) -> "{}";
        WebUiServerDispatcher.register(action, noop);

        boolean threw = false;
        try {
            WebUiServerDispatcher.register(action, noop);
        } catch (IllegalStateException e) {
            threw = true;
        }
        helper.assertTrue(threw,
                "re-registering an existing action must throw IllegalStateException (no silent overwrite)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void businessErrorHasStableCodeAndRetryPolicy(GameTestHelper helper) {
        WebUiBusinessException error = new WebUiBusinessException(
                "RATE_LIMITED", "开箱请求过快，请稍后再试", false);
        JsonObject result = JsonParser.parseString(
                WebUiServerDispatcher.businessErrorJson(error)).getAsJsonObject();
        helper.assertTrue(result.get("errorCode").getAsString().equals("RATE_LIMITED"),
                "expected business rejection includes its stable errorCode");
        helper.assertTrue(!result.get("retrySameOpeningId").getAsBoolean(),
                "expected business rejection includes an explicit opening-id retry policy");
        helper.assertTrue(result.get("error").getAsString().contains("稍后再试"),
                "expected business rejection retains a player-facing message");
        helper.assertTrue(error.getStackTrace().length == 0,
                "attacker-driven business rejections allocate no stack trace");
        helper.succeed();
    }

    /** 自增 nonce: 给 duplicateActionRegistrationThrows 造唯一 action 名, 隔离进程级注册表的跨方法/重跑残留。 */
    private static final java.util.concurrent.atomic.AtomicInteger DUP_GUARD_NONCE =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 自增 nonce: 给去重测试造唯一 action 名, 隔离进程级注册表的跨方法/重跑残留 (同 DUP_GUARD_NONCE 理由)。 */
    private static final java.util.concurrent.atomic.AtomicInteger DEDUP_NONCE =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 防重放红线 (契约第八章红线 6 / 5.3): 同一 sender 同一 requestId 第二次派发一个有副作用的 action, 副作用只发生
     * 一次 (第二次被 dispatcher 判重短路, 不触达 handler)。删 {@code dispatchAndRespond} 的 markRequestProcessed
     * 判重 (或把"已见则放行"改回"无条件执行") 本测试必挂。
     *
     * 用真实派发入口 {@link WebUiServerDispatcher#dispatchAndRespond} (而非直接 handle), 因去重逻辑就在该 Gateway
     * 入口; 副作用经 handler 内自增 AtomicInteger 观测 (改资金/库存副作用在 market.* 上, 此处用计数器等价代理:
     * 二者都经同一 dispatch 入口, 判重在 handler 之前, 与具体副作用无关)。
     *
     * 三段断言覆盖 (1) 同 requestId 第二次短路 (2) 不同 requestId 仍放行 (证判重按 requestId 而非一刀切封)
     * (3) clearPlayer 后旧 requestId 重新放行 (证登出清窗口真生效, 新会话从空窗口起)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void duplicateRequestIdShortCircuitsSideEffectOnce(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 唯一 action 名 + 计数器: 进程级注册表跨方法持久, 每次跑造新名避免撞 putIfAbsent 重复守卫。
        String action = "webui.test.dedup-" + DEDUP_NONCE.getAndIncrement();
        java.util.concurrent.atomic.AtomicInteger sideEffectCount = new java.util.concurrent.atomic.AtomicInteger();
        WebUiServerDispatcher.register(action, (sender, payload) -> {
            sideEffectCount.incrementAndGet();
            return "{}";
        });

        long requestId = 7_000_001L;

        // 第一次: 放行, 副作用执行一次。
        WebUiServerDispatcher.dispatchAndRespond(player, requestId, action, "{}");
        helper.assertTrue(sideEffectCount.get() == 1,
                "first dispatch of a fresh requestId must run the handler side effect exactly once, got "
                        + sideEffectCount.get());

        // 第二次同 requestId: 判重短路, 副作用不再发生 (仍为 1, 非 2)。删去重逻辑则这里变 2, 测试挂。
        WebUiServerDispatcher.dispatchAndRespond(player, requestId, action, "{}");
        helper.assertTrue(sideEffectCount.get() == 1,
                "re-dispatching the SAME requestId must be short-circuited as duplicate (side effect stays 1), got "
                        + sideEffectCount.get());

        // 不同 requestId 同 action: 仍放行 (判重按 requestId, 非封该 action/玩家), 副作用累加到 2。
        WebUiServerDispatcher.dispatchAndRespond(player, requestId + 1, action, "{}");
        helper.assertTrue(sideEffectCount.get() == 2,
                "a different requestId for the same action must still run (dedup keys on requestId), got "
                        + sideEffectCount.get());

        // 登出清窗口后, 原 requestId 不再被视为已处理 -> 重新放行 (新会话从空窗口起), 副作用累加到 3。
        WebUiServerDispatcher.clearPlayer(player.getUUID());
        WebUiServerDispatcher.dispatchAndRespond(player, requestId, action, "{}");
        helper.assertTrue(sideEffectCount.get() == 3,
                "after clearPlayer the previously-seen requestId is accepted again (window cleared), got "
                        + sideEffectCount.get());

        helper.succeed();
    }

    /**
     * 幂等确保 "system.echo" 已注册并返回其处理器。进程级注册表跨测试方法持久, 故若已注册则直接 resolve,
     * 否则走真实 {@link WebUiServerSubsystem#register} 注册路径 (首次调用即覆盖真实注册逻辑, 删 handleEcho 必挂),
     * 避免重复注册触发 dispatcher 的 putIfAbsent 重复守卫。
     */
    private static WebUiServerDispatcher.WebUiAction ensureEchoRegistered(GameTestHelper helper) {
        WebUiServerDispatcher.WebUiAction existing = WebUiServerDispatcher.resolve("system.echo");
        if (existing == null) {
            // modBus/forgeBus 在 echo action 注册路径上不被使用, 传 null 不触发任何事件订阅。
            new WebUiServerSubsystem().register((IEventBus) null, (IEventBus) null);
            existing = WebUiServerDispatcher.resolve("system.echo");
        }
        helper.assertTrue(existing != null, "system.echo must be registered by WebUiServerSubsystem.register");
        return existing;
    }
}
