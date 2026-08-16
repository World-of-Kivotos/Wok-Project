package com.miningdim.webui.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.network.C2SWebUiRequest;
import com.miningdim.testutil.MockGameTestPlayers;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Gateway 限流与错误码回归网 (F008 每玩家令牌桶限流 / F045 判重回执脱离裸机器串)。
 *
 * 服务端进程纪律 (同 {@link WebUiServerGameTests}): 只触碰 webui.server + network record + testutil + Gson/netty,
 * 严禁 classload 任何 com.miningdim.client.webui.* 渲染类或 MCEF。
 *
 * 强断言总览 (删被测核心逻辑测试必挂, 禁 is-not-null 弱校验):
 *  - {@link #tokenBucketRefillsAtTheConfiguredRate}: 直接测 {@link WebUiRateLimiter} 纯逻辑, 时间当参数驱动
 *    (严禁 sleep) —— 满桶起步/逐字速率补充/钳到 burstCapacity/clear 回收/构造参数非正拒绝;
 *  - {@link #gatewayThrottlesRunawayClientBeforeReachingHandlers}: 走真实 {@link WebUiServerDispatcher#dispatchAndRespond}
 *    入口, 证明限流门真的挡住失控客户端且按玩家分桶、登出会回收;
 *  - {@link #gatewayRejectionsCarryStablePlayerFacingCodes}: 锁 DUPLICATE_REQUEST/TOO_MANY_REQUESTS/UNKNOWN_ACTION
 *    三条对外契约码的字面量取值与回执形状, 防前端文案字典的键漂移;
 *  - {@link #actionNameIsCappedOnTheWire}: 走真实 {@link C2SWebUiRequest#encode}/{@link C2SWebUiRequest#decode}
 *    编解码, 证明 action 名上行长度上限在编码侧与解码侧 (改版客户端手工构造超长包) 都真实生效。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WebUiGatewayGuardGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui";

    /** 自增 nonce: 给 gatewayThrottlesRunawayClientBeforeReachingHandlers 造唯一 action 名, 隔离进程级注册表的跨方法/重跑残留。 */
    private static final java.util.concurrent.atomic.AtomicInteger THROTTLE_NONCE =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * {@link WebUiRateLimiter} 纯逻辑令牌桶断言 (F008)。构造直接 new, 时间由调用方以 nowNanos 参数驱动,
     * 不靠 sleep 制造流逝 —— 令牌桶实现本就把时钟当纯参数吃, 测试理应照做。
     *
     * 四段流逝断言 + 三段构造校验各自锁一条可被删掉的核心逻辑:
     *  - 满桶起步 (新玩家进服第一批请求不许被挡) + 钳到 burstCapacity 的第 6 次拒绝;
     *  - 补充速率 1 token/s 逐字成立 (1 秒只放行 1 次, 不多不少);
     *  - 100 秒的巨量流逝被钳到 burstCapacity=5, 不许攒出无限令牌;
     *  - clear() 立即满血回收 (登出后重连不该继承旧窗口的枯竭状态);
     *  - burstCapacity/refillPerSecond 非正必须在构造期就被拒, 不许把非法配置留到运行期才炸。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tokenBucketRefillsAtTheConfiguredRate(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        WebUiRateLimiter limiter = new WebUiRateLimiter(5, 1.0);

        long now = 0L;
        for (int i = 1; i <= 5; i++) {
            helper.assertTrue(limiter.tryAcquire(player, now),
                    "a fresh bucket starts full: request " + i + " of 5 at now=0 must be allowed");
        }
        helper.assertTrue(!limiter.tryAcquire(player, now),
                "burstCapacity is 5; the 6th immediate request at the same instant must be throttled");

        long afterOneSecond = 1_000_000_000L;
        helper.assertTrue(limiter.tryAcquire(player, afterOneSecond),
                "1 second elapsed at refill rate 1/s must grant exactly 1 fresh token");
        helper.assertTrue(!limiter.tryAcquire(player, afterOneSecond),
                "only 1 token was refilled after 1 second; a second immediate request must still be throttled");

        long after100Seconds = 100_000_000_000L;
        int clampedAllowed = 0;
        for (int i = 0; i < 5; i++) {
            if (limiter.tryAcquire(player, after100Seconds)) {
                clampedAllowed++;
            } else {
                break;
            }
        }
        helper.assertTrue(clampedAllowed == 5,
                "100 seconds of accrual must be clamped to burstCapacity=5, got " + clampedAllowed + " allowed");
        helper.assertTrue(!limiter.tryAcquire(player, after100Seconds),
                "after draining the clamped burst, the next request at the same instant must still be throttled");

        limiter.clear(player);
        for (int i = 1; i <= 5; i++) {
            helper.assertTrue(limiter.tryAcquire(player, after100Seconds),
                    "clear() must reclaim the bucket back to full (logout reclaim), request " + i + " of 5");
        }

        boolean rejectedZeroCapacity = false;
        try {
            new WebUiRateLimiter(0, 1.0);
        } catch (IllegalArgumentException expected) {
            rejectedZeroCapacity = true;
        }
        helper.assertTrue(rejectedZeroCapacity, "burstCapacity=0 must be rejected at construction");

        boolean rejectedZeroRate = false;
        try {
            new WebUiRateLimiter(5, 0.0);
        } catch (IllegalArgumentException expected) {
            rejectedZeroRate = true;
        }
        helper.assertTrue(rejectedZeroRate, "refillPerSecond=0.0 must be rejected at construction");

        boolean rejectedNegativeRate = false;
        try {
            new WebUiRateLimiter(5, -1.0);
        } catch (IllegalArgumentException expected) {
            rejectedNegativeRate = true;
        }
        helper.assertTrue(rejectedNegativeRate, "a negative refillPerSecond must be rejected at construction");

        helper.succeed();
    }

    /**
     * 走真实 {@link WebUiServerDispatcher#dispatchAndRespond} 入口证明限流门真的挡在 handler 之前 (F008)。
     * 每次调用换一个不同的 requestId, 目的是绕开防重放判重窗口, 好让被挡住的确定是限流门而不是判重
     * (两者顺序在 dispatcher 内限流在前, 但本测试要证的是限流本身的存在, 不能让判重帮着挡)。
     *
     * 四段断言各锁一条可被删掉的核心逻辑:
     *  - handler 被调用次数严格小于请求总数 500 (删限流门本测试必挂, 500 次会全部放行到 handler);
     *  - 放行次数不小于 20 (突发头寸太紧会把页面冷启动的一次性批量拉取自己打死);
     *  - 换一个新玩家立刻还能再放行 1 次 (证限流按玩家分桶, 不是全局一刀切封锁);
     *  - clearPlayer 后被限流玩家立刻还能再放行 1 次 (证登出真的回收了令牌桶, 不是永久拉黑)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gatewayThrottlesRunawayClientBeforeReachingHandlers(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        String action = "webui.test.throttle-guard-" + THROTTLE_NONCE.getAndIncrement();
        java.util.concurrent.atomic.AtomicInteger handlerCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        WebUiServerDispatcher.register(action, (sender, payload) -> {
            handlerCalls.incrementAndGet();
            return "{}";
        });

        long baseRequestId = 8_000_000L;
        for (int i = 0; i < 500; i++) {
            // 每次换新 requestId: 绕开防重放判重窗口, 让被挡住的一定是限流门而不是重复提交判重。
            WebUiServerDispatcher.dispatchAndRespond(player, baseRequestId + i, action, "{}");
        }

        int countAfterBurst = handlerCalls.get();
        helper.assertTrue(countAfterBurst < 500,
                "500 distinct-requestId dispatches from one runaway player must be throttled before all reach "
                        + "the handler; got " + countAfterBurst + " (limiter absent would make this exactly 500)");
        helper.assertTrue(countAfterBurst >= 20,
                "burst headroom must stay generous enough for the page's cold-start bulk fetch; got only "
                        + countAfterBurst + " allowed");

        ServerPlayer otherPlayer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        WebUiServerDispatcher.dispatchAndRespond(otherPlayer, 1L, action, "{}");
        helper.assertTrue(handlerCalls.get() == countAfterBurst + 1,
                "a different player must own an independent bucket, not share the throttled player's; expected "
                        + (countAfterBurst + 1) + ", got " + handlerCalls.get());

        WebUiServerDispatcher.clearPlayer(player.getUUID());
        WebUiServerDispatcher.dispatchAndRespond(player, baseRequestId + 999, action, "{}");
        helper.assertTrue(handlerCalls.get() == countAfterBurst + 2,
                "clearPlayer must reclaim the throttled player's token bucket (logout reclaim), allowing one more "
                        + "request through; expected " + (countAfterBurst + 2) + ", got " + handlerCalls.get());

        helper.succeed();
    }

    /**
     * 三条对外契约码的字面量比对 (F045): 码值是前端文案字典的键, 一旦漂移前端就整条失配。刻意写字面量
     * 而非常量比较 (同 {@link WebUiServerGameTests#businessErrorHasStableCodeAndRetryPolicy} 的理由) ——
     * 收编成常量时值不许被改, 改了本测试挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gatewayRejectionsCarryStablePlayerFacingCodes(GameTestHelper helper) {
        JsonObject duplicate = JsonParser.parseString(WebUiServerDispatcher.DUPLICATE_REQUEST_JSON).getAsJsonObject();
        helper.assertTrue("DUPLICATE_REQUEST".equals(duplicate.get("errorCode").getAsString()),
                "DUPLICATE_REQUEST_JSON errorCode must be the stable wire value 'DUPLICATE_REQUEST', got "
                        + duplicate.get("errorCode"));
        helper.assertTrue(duplicate.get("retrySameOpeningId").getAsBoolean(),
                "a duplicate rejection proves the requestId's handler already ran once with an unknown outcome; "
                        + "retrySameOpeningId must be true so case.open's idempotent-by-openingId resume path is "
                        + "used instead of minting a fresh openingId (which would double-charge if the original "
                        + "call had already succeeded)");
        String duplicateMessage = duplicate.get("error").getAsString();
        helper.assertTrue(!duplicateMessage.isEmpty(),
                "duplicate rejection must carry a non-empty player-facing Chinese message");
        helper.assertTrue(!duplicateMessage.contains("duplicate_request"),
                "F045: the player must never see the bare machine string 'duplicate_request', got '"
                        + duplicateMessage + "'");
        helper.assertTrue(WebUiServerDispatcher.DUPLICATE_REQUEST_JSON.length() < 512,
                "the duplicate rejection receipt must be a fixed short payload, got "
                        + WebUiServerDispatcher.DUPLICATE_REQUEST_JSON.length() + " chars");
        helper.assertTrue(WebUiServerDispatcher.DUPLICATE_REQUEST_JSON.length() < FriendlyByteBuf.MAX_STRING_LENGTH,
                "the duplicate rejection receipt must stay far below the downstream wire limit");

        JsonObject tooMany = JsonParser.parseString(WebUiServerDispatcher.TOO_MANY_REQUESTS_JSON).getAsJsonObject();
        String tooManyCode = tooMany.get("errorCode").getAsString();
        helper.assertTrue("TOO_MANY_REQUESTS".equals(tooManyCode),
                "TOO_MANY_REQUESTS_JSON errorCode must be the stable wire value 'TOO_MANY_REQUESTS', got "
                        + tooManyCode);
        helper.assertTrue(!tooManyCode.equals(WebUiErrorCodes.RATE_LIMITED),
                "the gateway-level throttle code must stay a distinct table from the case-opening-specific "
                        + "RATE_LIMITED code (deliberately kept separate), got equal value '" + tooManyCode + "'");
        helper.assertTrue(WebUiServerDispatcher.TOO_MANY_REQUESTS_JSON.length() < 512,
                "the throttle rejection receipt must be a fixed short payload, got "
                        + WebUiServerDispatcher.TOO_MANY_REQUESTS_JSON.length() + " chars");
        helper.assertTrue(WebUiServerDispatcher.TOO_MANY_REQUESTS_JSON.length() < FriendlyByteBuf.MAX_STRING_LENGTH,
                "the throttle rejection receipt must stay far below the downstream wire limit");

        WebUiBusinessException unknownAction = new WebUiBusinessException(
                WebUiErrorCodes.UNKNOWN_ACTION, "unknown Web UI action: x", false);
        String unknownActionJson = WebUiServerDispatcher.businessErrorJson(unknownAction);
        JsonObject unknownActionResult = JsonParser.parseString(unknownActionJson).getAsJsonObject();
        helper.assertTrue("UNKNOWN_ACTION".equals(unknownActionResult.get("errorCode").getAsString()),
                "UNKNOWN_ACTION rejection errorCode must be the stable wire value 'UNKNOWN_ACTION', got "
                        + unknownActionResult.get("errorCode"));
        helper.assertTrue(unknownAction.getStackTrace().length == 0,
                "an attacker-repeatable rejection (unknown action, freely triggered) must not allocate a stack trace");
        helper.assertTrue(unknownActionJson.length() < 512,
                "the unknown-action rejection receipt must be a fixed short payload decoupled from the client-"
                        + "supplied action name, got " + unknownActionJson.length() + " chars");
        helper.assertTrue(unknownActionJson.length() < FriendlyByteBuf.MAX_STRING_LENGTH,
                "the unknown-action rejection receipt must stay far below the downstream wire limit");

        helper.succeed();
    }

    /**
     * {@link C2SWebUiRequest} action 名上行长度上限的真实编解码断言 (F008)。走真实 {@code encode}/{@code decode},
     * 不是比对字符串长度那么简单 —— 攻击面在解码侧: 一个改版客户端能手工拼出跳过 encode 侧上限检查的包,
     * 唯一能拦住它的是 decode 侧 {@code readUtf(MAX_ACTION_CHARS)} 本身的边界检查。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void actionNameIsCappedOnTheWire(GameTestHelper helper) {
        // (1) 正常长度往返: 全库最长的注册 action 名不许被上限误伤, 直接引用字面量而非常量作对照。
        FriendlyByteBuf roundTripBuf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            C2SWebUiRequest original = new C2SWebUiRequest(42L, "admin.economy.balance", "{}");
            C2SWebUiRequest.encode(original, roundTripBuf);
            C2SWebUiRequest decoded = C2SWebUiRequest.decode(roundTripBuf);
            helper.assertTrue(decoded.requestId() == 42L,
                    "requestId must round-trip verbatim through encode/decode, got " + decoded.requestId());
            helper.assertTrue("admin.economy.balance".equals(decoded.action()),
                    "the longest registered action name 'admin.economy.balance' must not be truncated by the "
                            + "wire cap, got '" + decoded.action() + "'");
            helper.assertTrue("{}".equals(decoded.payloadJson()),
                    "payloadJson must round-trip verbatim, got '" + decoded.payloadJson() + "'");
        } finally {
            roundTripBuf.release();
        }

        // (2) 编码侧: 超上限的 action 名必须在 encode 就抛, 不许静默截断放行一个变了形的名字上线。
        FriendlyByteBuf encodeGuardBuf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            String oversizedAction = "a".repeat(C2SWebUiRequest.MAX_ACTION_CHARS + 1);
            boolean threwOnEncode = false;
            try {
                C2SWebUiRequest.encode(new C2SWebUiRequest(1L, oversizedAction, "{}"), encodeGuardBuf);
            } catch (EncoderException expected) {
                threwOnEncode = true;
            }
            helper.assertTrue(threwOnEncode,
                    "encoding an action name over MAX_ACTION_CHARS must throw EncoderException, not silently truncate");
        } finally {
            encodeGuardBuf.release();
        }

        // (3) 解码侧 (真正的攻击面): 手工构造一个跳过 encode 侧检查的超长包, 模拟改版客户端。
        // 把 MAX_ACTION_CHARS 改回默认 32767 这条必挂 —— 它挡住的正是每包往日志与回执各塞 32KB 可控文本的放大器。
        FriendlyByteBuf attackBuf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            attackBuf.writeLong(1L);
            attackBuf.writeUtf("a".repeat(1000));
            attackBuf.writeUtf("{}");
            boolean threwOnDecode = false;
            try {
                C2SWebUiRequest.decode(attackBuf);
            } catch (DecoderException expected) {
                threwOnDecode = true;
            }
            helper.assertTrue(threwOnDecode,
                    "decoding a hand-crafted 1000-char action name (bypassing the encode-side check) must throw "
                            + "DecoderException; this is the exact amplifier MAX_ACTION_CHARS defends against");
        } finally {
            attackBuf.release();
        }

        // (4) 上限常量本身: 64 字符, 严格小于默认的 FriendlyByteBuf.MAX_STRING_LENGTH。
        helper.assertTrue(C2SWebUiRequest.MAX_ACTION_CHARS == 64,
                "MAX_ACTION_CHARS must be exactly 64, got " + C2SWebUiRequest.MAX_ACTION_CHARS);
        helper.assertTrue(C2SWebUiRequest.MAX_ACTION_CHARS < FriendlyByteBuf.MAX_STRING_LENGTH,
                "MAX_ACTION_CHARS must be strictly below the default FriendlyByteBuf.MAX_STRING_LENGTH ("
                        + FriendlyByteBuf.MAX_STRING_LENGTH + "), got " + C2SWebUiRequest.MAX_ACTION_CHARS);

        helper.succeed();
    }
}
