package com.miningdim.webui.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.auth.PlayerLoginGate;
import com.miningdim.network.MiningNetwork;
import com.miningdim.network.S2CWebUiEvent;
import com.miningdim.network.S2CWebUiResponse;
import com.miningdim.testutil.MockGameTestPlayers;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebUI 网关登录门回归网: 未通过 AccessHub /login 的连接, 任何服务端 action 都跑不到 handler。
 *
 * 全部走真实 {@link WebUiServerDispatcher#dispatchAndRespond} 入口, 并从 mock 玩家的 EmbeddedChannel 里把下行
 * 的 {@link S2CWebUiResponse} 解出来核对 —— 只数 handler 调用次数证明不了玩家拿到的是哪个错误码。
 * 登录态经 {@link PlayerLoginGate#forceVerdictForTest} 按玩家注入 (dev 里没有 AccessHub)。
 *
 * 服务端进程纪律同 {@link WebUiGatewayGuardGameTests}: 不 classload 任何 client.webui 类或 MCEF。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WebUiLoginGateGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_login_gate";

    private static final ResourceLocation MAIN_CHANNEL = new ResourceLocation(MiningConstants.MODID, "main");

    /** 每次运行造唯一 action 名, 隔离进程级注册表的跨方法残留。 */
    private static final AtomicInteger NONCE = new AtomicInteger();

    /**
     * 核心不变量: 被登录门拒绝时 handler 一次都不跑, 玩家拿到 NOT_LOGGED_IN; 登录后同一个 requestId 还能真正执行。
     *
     * 逐段各锁一条可被删掉的逻辑:
     *  - 普通 action 被拒且回 NOT_LOGGED_IN (删登录门, handler 计数变 1);
     *  - 未注册的 action 也回 NOT_LOGGED_IN 而不是 UNKNOWN_ACTION (登录门排在查注册表之前);
     *  - system.batch 整批被拒, 回的是网关级失败信封而不是逐条结果 (批内 handler 一条都没跑);
     *  - system.handshake 放行 (PRE_LOGIN_ACTIONS);
     *  - UNAVAILABLE 回 LOGIN_CHECK_UNAVAILABLE;
     *  - 登录后用<b>同一个</b> requestId 重发能执行 (登录门排在判重之前, 被拒的请求没烧掉 id)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unauthenticatedRequestsNeverReachHandlers(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = channelOf(player);
        drainResponses(channel);

        String action = "webui.test.login-gate-" + NONCE.getAndIncrement();
        AtomicInteger handlerCalls = new AtomicInteger();
        WebUiServerDispatcher.register(action, (sender, payload) -> {
            handlerCalls.incrementAndGet();
            return "{}";
        });

        long requestId = 9_100_000L;
        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
            WebUiServerDispatcher.dispatchAndRespond(player, requestId, action, "{}");
            helper.assertTrue(handlerCalls.get() == 0,
                    "a player who has not /login-ed must never reach an action handler, got " + handlerCalls.get());
            assertRejected(helper, singleResponse(helper, channel, requestId), "NOT_LOGGED_IN");

            WebUiServerDispatcher.dispatchAndRespond(player, requestId + 1, "webui.test.no-such-action", "{}");
            assertRejected(helper, singleResponse(helper, channel, requestId + 1), "NOT_LOGGED_IN");

            JsonObject batchPayload = new JsonObject();
            JsonArray calls = new JsonArray();
            for (String read : new String[] {"player.wallet", "player.inventory"}) {
                JsonObject call = new JsonObject();
                call.addProperty("action", read);
                call.add("payload", new JsonObject());
                calls.add(call);
            }
            batchPayload.add("calls", calls);
            WebUiServerDispatcher.dispatchAndRespond(player, requestId + 2, "system.batch", batchPayload.toString());
            assertRejected(helper, singleResponse(helper, channel, requestId + 2), "NOT_LOGGED_IN");

            WebUiServerDispatcher.dispatchAndRespond(player, requestId + 3, "system.handshake", "{}");
            S2CWebUiResponse handshake = singleResponse(helper, channel, requestId + 3);
            helper.assertTrue(handshake.success(),
                    "system.handshake is the one pre-login action (public version + action list) and must succeed; "
                            + "got " + handshake.resultJson());
        }

        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.UNAVAILABLE)) {
            WebUiServerDispatcher.dispatchAndRespond(player, requestId + 4, action, "{}");
            assertRejected(helper, singleResponse(helper, channel, requestId + 4), "LOGIN_CHECK_UNAVAILABLE");
            helper.assertTrue(handlerCalls.get() == 0, "UNAVAILABLE must also stop the handler");
        }

        // "登录"之后用同一个 requestId 重发: 被拒的那次不能把 id 烧进防重放窗口。
        WebUiServerDispatcher.dispatchAndRespond(player, requestId, action, "{}");
        helper.assertTrue(handlerCalls.get() == 1,
                "after login the same requestId must execute exactly once (the login gate runs before the "
                        + "replay window), got " + handlerCalls.get() + " handler calls");
        S2CWebUiResponse accepted = singleResponse(helper, channel, requestId);
        helper.assertTrue(accepted.success(), "after login the retry must succeed, got " + accepted.resultJson());
        helper.succeed();
    }

    /**
     * 两条登录门回执的字面量与形状 (码值是前端 errorText 表与登录提示遮罩的键, 漂移即失配)。
     * 刻意写字面量而不是比常量, 理由同 {@link WebUiGatewayGuardGameTests#gatewayRejectionsCarryStablePlayerFacingCodes}。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginGateReceiptsCarryStablePlayerFacingCodes(GameTestHelper helper) {
        JsonObject notLoggedIn = JsonParser.parseString(WebUiServerDispatcher.NOT_LOGGED_IN_JSON).getAsJsonObject();
        helper.assertTrue("NOT_LOGGED_IN".equals(notLoggedIn.get("errorCode").getAsString()),
                "NOT_LOGGED_IN_JSON errorCode must be the wire value 'NOT_LOGGED_IN', got " + notLoggedIn.get("errorCode"));
        helper.assertTrue(notLoggedIn.get("error").getAsString().contains("/login"),
                "the not-logged-in message is shown verbatim by several pages and must tell the player to /login, got '"
                        + notLoggedIn.get("error").getAsString() + "'");
        helper.assertTrue(notLoggedIn.get("retrySameOpeningId").getAsBoolean(),
                "retrySameOpeningId must be true: the case page's pending openingId may belong to an earlier "
                        + "request with an unknown outcome, dropping it risks a double charge after login");

        JsonObject unavailable =
                JsonParser.parseString(WebUiServerDispatcher.LOGIN_CHECK_UNAVAILABLE_JSON).getAsJsonObject();
        helper.assertTrue("LOGIN_CHECK_UNAVAILABLE".equals(unavailable.get("errorCode").getAsString()),
                "LOGIN_CHECK_UNAVAILABLE_JSON errorCode must be the wire value 'LOGIN_CHECK_UNAVAILABLE', got "
                        + unavailable.get("errorCode"));
        helper.assertTrue(!unavailable.get("error").getAsString().contains("/login"),
                "when the login state cannot be determined /login does not help; the message must not send the "
                        + "player back to typing passwords");

        for (String receipt : new String[] {WebUiServerDispatcher.NOT_LOGGED_IN_JSON,
                WebUiServerDispatcher.LOGIN_CHECK_UNAVAILABLE_JSON}) {
            helper.assertTrue(receipt.length() < 512, "login gate receipts must be fixed short payloads: " + receipt);
        }
        helper.assertTrue(WebUiServerDispatcher.PRE_LOGIN_ACTIONS.equals(Set.of("system.handshake")),
                "only system.handshake may run before login; adding a name claims it is harmless for an "
                        + "unauthenticated connection, "
                        + "got " + WebUiServerDispatcher.PRE_LOGIN_ACTIONS);
        helper.succeed();
    }

    /**
     * 登录落地时平板收到 {@link WebUiEventNames#LOGIN_CONFIRMED} 推送, 且只收到一次; 进服即放行的玩家收不到。
     *
     * 走真实接线: 请求被网关拒 -> 登录门把玩家记进等待表 -> 判定放行后<b>真实 tick</b> 的巡检发现翻转 ->
     * WebUiServerSubsystem 注册的监听器推事件 -> 从 mock 玩家的 EmbeddedChannel 解出下行事件包。删掉
     * LoginGateSubsystem 的巡检、WebUiServerSubsystem 里那行监听器注册、或推送前的 atJoin 判断, 都会挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 100)
    public static void tabletIsToldWhenTheLoginGoesThrough(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = channelOf(player);
        List<String> atJoin = drainEventNames(channel);
        helper.assertTrue(!atJoin.contains(WebUiEventNames.LOGIN_CONFIRMED),
                "a player allowed at join never saw the login notice and must not be sent " + WebUiEventNames.LOGIN_CONFIRMED
                        + ", got " + atJoin);

        String action = "webui.test.login-confirmed-" + NONCE.getAndIncrement();
        WebUiServerDispatcher.register(action, (sender, payload) -> "{}");
        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
            WebUiServerDispatcher.dispatchAndRespond(player, 9_200_000L, action, "{}");
        }
        helper.assertTrue(drainEventNames(channel).isEmpty(),
                "nothing may be pushed before the server notices the login (the sweep runs on the tick)");

        List<String> events = new ArrayList<>();
        helper.succeedWhen(() -> {
            events.addAll(drainEventNames(channel));
            long confirmations = events.stream().filter(WebUiEventNames.LOGIN_CONFIRMED::equals).count();
            helper.assertTrue(confirmations == 1,
                    "after the verdict flips the tablet must get exactly one " + WebUiEventNames.LOGIN_CONFIRMED
                            + " push, got " + events);
        });
    }

    // ============================================================
    // 工具
    // ============================================================

    private static void assertRejected(GameTestHelper helper, S2CWebUiResponse response, String expectedCode) {
        helper.assertTrue(!response.success(),
                "request " + response.requestId() + " must be rejected, got success: " + response.resultJson());
        JsonObject body = JsonParser.parseString(response.resultJson()).getAsJsonObject();
        helper.assertTrue(body.has("errorCode") && expectedCode.equals(body.get("errorCode").getAsString()),
                "request " + response.requestId() + " must carry errorCode " + expectedCode + ", got "
                        + response.resultJson());
    }

    /** 取出迄今为止下行的全部回执, 要求其中恰好一条属于 requestId。 */
    private static S2CWebUiResponse singleResponse(GameTestHelper helper, EmbeddedChannel channel, long requestId) {
        List<S2CWebUiResponse> matching = new ArrayList<>();
        for (S2CWebUiResponse response : drainResponses(channel)) {
            if (response.requestId() == requestId) {
                matching.add(response);
            }
        }
        if (matching.size() != 1) {
            helper.fail("expected exactly one Web UI response for requestId " + requestId + ", got " + matching.size());
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return matching.get(0);
    }

    private static List<S2CWebUiResponse> drainResponses(EmbeddedChannel channel) {
        int responseDiscriminator = discriminatorOf(new S2CWebUiResponse(0L, true, "{}"));
        List<S2CWebUiResponse> responses = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundCustomPayloadPacket payload
                        && MAIN_CHANNEL.equals(payload.getIdentifier())) {
                    FriendlyByteBuf copy = new FriendlyByteBuf(payload.getData().copy());
                    try {
                        if (copy.readVarInt() == responseDiscriminator) {
                            responses.add(S2CWebUiResponse.decode(copy));
                        }
                    } finally {
                        copy.release();
                    }
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return responses;
    }

    /** 取出迄今为止下行的全部 Web UI 事件包的事件名 (回执等其它包一并丢弃)。 */
    private static List<String> drainEventNames(EmbeddedChannel channel) {
        int eventDiscriminator = discriminatorOf(new S2CWebUiEvent("", "{}"));
        List<String> names = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundCustomPayloadPacket payload
                        && MAIN_CHANNEL.equals(payload.getIdentifier())) {
                    FriendlyByteBuf copy = new FriendlyByteBuf(payload.getData().copy());
                    try {
                        if (copy.readVarInt() == eventDiscriminator) {
                            names.add(S2CWebUiEvent.decode(copy).eventName());
                        }
                    } finally {
                        copy.release();
                    }
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return names;
    }

    /**
     * 向真实通道问一个包的 discriminator: 编一条探针消息, 取 SimpleChannel 写在包体最前面的那个字节。
     *
     * 不写死注册序号: discriminator 就是 {@link MiningNetwork#register} 里 registerMessage 的调用次序, 在目标包
     * 之前增删任何一个包都会让写死的数错位, 而错位的症状不是报错, 是一条回执、一个事件都解不出来
     * (MiningEntryFeeGameTests 在 F087 删 SelectZoneC2S 时撞过一次)。探针只写进本地缓冲, 不经过玩家的连接。
     */
    private static int discriminatorOf(Object probe) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MiningNetwork.CHANNEL.encodeMessage(probe, buf);
            return buf.readUnsignedByte();
        } finally {
            buf.release();
        }
    }

    private static EmbeddedChannel channelOf(ServerPlayer player) {
        return (EmbeddedChannel) player.connection.connection.channel();
    }
}
