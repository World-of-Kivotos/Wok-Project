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
 *  - 重复注册同名 action 抛 IllegalStateException (装配缺陷暴露)。
 *
 * 纯逻辑断言不依赖结构, 用 template = "empty"。
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

    /** 自增 nonce: 给 duplicateActionRegistrationThrows 造唯一 action 名, 隔离进程级注册表的跨方法/重跑残留。 */
    private static final java.util.concurrent.atomic.AtomicInteger DUP_GUARD_NONCE =
            new java.util.concurrent.atomic.AtomicInteger();

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
