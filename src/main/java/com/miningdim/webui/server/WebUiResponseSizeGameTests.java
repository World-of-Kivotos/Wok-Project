package com.miningdim.webui.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.market.PlayerWebUiActions;
import com.miningdim.network.S2CWebUiResponse;
import com.miningdim.testutil.MockGameTestPlayers;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 回执体积 GameTest: 客户端可控的入参不许把 S2CWebUiResponse 撑出 writeUtf 的字符上限。
 *
 * 为什么单独一条: 这是唯一一条能让 Gateway "任何异常都转成回执、绝不逃逸"的契约破掉的路径。入站
 * {@code C2SWebUiRequest.decode} 的 readUtf 上限同为 32767 字符, 客户端因此能合法送来一个三万多字符的标量;
 * 校验拒绝时若把它原样回显进 params, 回执就编不出去。而那一下 {@code EncoderException} 是从
 * {@link WebUiServerDispatcher#dispatchAndRespond} 的 {@code catch (WebUiBusinessException)} 块<b>内部</b>
 * 抛出的, 不再有任何 catch 兜住 —— 该 requestId 既收不到回执, 又已被防重放窗口烧掉 (同 id 重试只会得到
 * duplicate_request), 前端的 Promise 永不 settle, 界面就那么挂在 loading 上。
 *
 * 故本类不只比对长度, 而是把回执塞进真实的 {@link S2CWebUiResponse#encode} 走一遍编码。
 *
 * 服务端进程纪律 (同 {@link WebUiServerGameTests}): 只触碰 webui.server + market 的服务端 action + network
 * record + testutil + Gson/netty, 严禁 classload 任何 com.miningdim.client.webui.* 渲染类或 MCEF。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WebUiResponseSizeGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui";

    /**
     * 回显值的字符上限 (64 正文 + 三点省略号)。写死在测试里而不是引用被测常量: 它是"回执体积与客户端输入
     * 解耦"这条不变量的判据, 两边一起改还是绿的等于没测。
     */
    private static final int MAX_ECHOED_VALUE_CHARS = 67;

    /**
     * 取值域外的拒绝把客户端原值截断后才回显, 于是整条回执恒能编码下行。
     *
     * 删掉 {@code WebUiPayloads.illegalValue} 里的截断 (改回原样回显) 本条必挂两处: 省略号断言先挂,
     * 随后 encode 断言也挂 —— 后者正是线上真会发生的那一幕。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oversizedRejectedValueIsTruncatedSoTheResponseStillEncodes(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        WebUiServerDispatcher.WebUiAction prefsSet = ensurePrefsSetRegistered(helper);

        // 前提校验: 这条上限是真的, 且抛的确实是长度而不是别的 (恰好卡在上限的那条必须编得出去)。
        helper.assertTrue(encodeThrows("x".repeat(FriendlyByteBuf.MAX_STRING_LENGTH + 1)),
                "前提校验: 超过 " + FriendlyByteBuf.MAX_STRING_LENGTH + " 字符的 resultJson 必须编不出去");
        helper.assertTrue(!encodeThrows("x".repeat(FriendlyByteBuf.MAX_STRING_LENGTH)),
                "前提校验: 恰好 " + FriendlyByteBuf.MAX_STRING_LENGTH + " 字符仍在上限内");

        // 32000 字符是客户端真能合法送到服务端的量级 (入站 readUtf 上限同为 32767 字符), 不是臆造的极端值。
        String oversized = "a".repeat(32_000);
        JsonObject payload = new JsonObject();
        payload.addProperty("muteToasts", false);
        payload.addProperty("language", oversized);
        payload.addProperty("theme", "dark");
        payload.addProperty("brandHue", 250);

        WebUiBusinessException rejected = rejection(helper, prefsSet, player, payload);
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(rejected.errorCode()),
                "超长 language 走的是取值域外那一档 (INVALID_REQUEST), 实得 " + rejected.errorCode());
        helper.assertTrue("language".equals(rejected.params().get("field")),
                "拒绝仍必须指名是哪个字段, 实得 " + rejected.params());

        String shown = rejected.params().get("value");
        helper.assertTrue(shown.endsWith("..."),
                "回显值必须带截断省略号 (原样回显即上限失守), 实得长度 " + shown.length());
        helper.assertTrue(oversized.startsWith(shown.substring(0, shown.length() - 3)),
                "截断后剩下的必须是客户端原值的前缀, 而不是被换成了别的占位内容, 实得 " + shown);
        helper.assertTrue(shown.length() == MAX_ECHOED_VALUE_CHARS,
                "回显值恒 64 字符正文 + 省略号 = " + MAX_ECHOED_VALUE_CHARS + " 字符, 实得 " + shown.length());

        String resultJson = WebUiServerDispatcher.businessErrorJson(rejected);
        helper.assertTrue(resultJson.length() < 512,
                "整条回执必须与客户端输入体积解耦 (远小于 " + FriendlyByteBuf.MAX_STRING_LENGTH + " 的硬上限), 实得 "
                        + resultJson.length());
        // 上一条只证明长度小; 这一条才证明这份回执真的过得了下行编码 —— 挂死那一幕就发生在这个方法里。
        helper.assertTrue(!encodeThrows(resultJson),
                "截断后的业务拒绝回执必须能经 S2CWebUiResponse.encode 下行, 实得 " + resultJson);

        // 截断发生在 params 通道内部, 不许顺手把整条 params 丢掉 —— 那样前端就定位不到出问题的控件了。
        JsonObject params = JsonParser.parseString(resultJson).getAsJsonObject().getAsJsonObject("params");
        helper.assertTrue("language".equals(params.get("field").getAsString())
                        && shown.equals(params.get("value").getAsString()),
                "截断后的 field/value 必须原样进回执 params, 实得 " + params);

        helper.succeed();
    }

    /**
     * 未知 action 名超长时, Gateway 仍必须把回执发出去而不是让编码异常逃逸。
     *
     * 这条与上一条同型但换了入参通道: 拒绝发生在 handler 之外 (查表落空), 回执经
     * {@code catch (Exception)} 那条兜底路径产出。把 {@code respond} 的体积守卫删掉本条即挂 ——
     * {@code "unknown Web UI action: " + action} 撑过上限, EncoderException 从 catch 块内部抛出后无人接。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oversizedActionNameStillGetsAnEncodableResponse(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // 取入站 readUtf 的上限本身: 这既是客户端真能送到的最大 action 名, 也保证加上前缀后必然撑过下行上限。
        String oversizedAction = "a".repeat(FriendlyByteBuf.MAX_STRING_LENGTH);

        // 前提校验: 这条 action 名真的能把异常 message 撑过上限 (否则本条测的就不是它该测的东西)。
        helper.assertTrue(
                ("unknown Web UI action: " + oversizedAction).length() > FriendlyByteBuf.MAX_STRING_LENGTH,
                "前提校验: 未知 action 的异常 message 必须超过 " + FriendlyByteBuf.MAX_STRING_LENGTH + " 字符");

        assertRespondsWithoutEscaping(helper, player, 90_001L, oversizedAction, "{}");
        helper.succeed();
    }

    /**
     * payload 是超长的非对象标量时同上。
     *
     * Gson 对非对象元素抛的是 {@code "Not a JSON Object: " + 元素全文}, 于是 message 同样随客户端输入长大。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oversizedNonObjectPayloadStillGetsAnEncodableResponse(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ensurePrefsSetRegistered(helper);
        // 连引号在内恰好占满入站上限, 与 action 那条同理: 客户端真送得出, 且 Gson 拼上前缀后必然超下行上限。
        String oversizedPayload = "\"" + "a".repeat(FriendlyByteBuf.MAX_STRING_LENGTH - 2) + "\"";

        // 前提校验: 确认 Gson 真的把整个元素塞进 message (这是本条成立的前提, 不是猜的)。
        String gsonMessage;
        try {
            JsonParser.parseString(oversizedPayload).getAsJsonObject();
            helper.fail("前提校验: 非对象 payload 本应抛 IllegalStateException");
            return;
        } catch (IllegalStateException expected) {
            gsonMessage = expected.getMessage();
        }
        helper.assertTrue(gsonMessage != null && gsonMessage.length() > FriendlyByteBuf.MAX_STRING_LENGTH,
                "前提校验: Gson 的异常 message 必须含元素全文并超过上限, 实得长度 "
                        + (gsonMessage == null ? -1 : gsonMessage.length()));

        assertRespondsWithoutEscaping(helper, player, 90_002L, "player.prefs.set", oversizedPayload);
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 走真实 Gateway 入口派发一次, 要求过程中不逃逸任何异常。
     *
     * 这里的 catch 是判据不是生吞: Gateway 的契约就是"任何异常都转成回执、绝不逃逸", 逃逸即契约破裂,
     * 捕到就地判失败并把现场带出来。回执发包经 mock 玩家的活动 EmbeddedChannel 落出站队列, 无副作用。
     */
    private static void assertRespondsWithoutEscaping(GameTestHelper helper, ServerPlayer sender,
                                                      long requestId, String action, String payloadJson) {
        try {
            WebUiServerDispatcher.dispatchAndRespond(sender, requestId, action, payloadJson);
        } catch (Exception escaped) {
            helper.fail("Gateway 必须把超限回执换成定长回执, 实际逃逸了 "
                    + escaped.getClass().getSimpleName() + ": " + escaped.getMessage());
        }
    }

    /**
     * 把 resultJson 塞进真实下行编码路径, 抛 {@link EncoderException} 即返回 true。
     * 这是本类唯一的 catch: 它把"抛没抛"转成断言判据, 不是吞掉异常。
     */
    private static boolean encodeThrows(String resultJson) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2CWebUiResponse.encode(new S2CWebUiResponse(1L, false, resultJson), buf);
            return false;
        } catch (EncoderException tooBig) {
            return true;
        } finally {
            buf.release();
        }
    }

    /** 调 action 并要求它抛业务拒绝; 没抛就地判失败 (返回值必非 null, 调用方可直接取字段)。 */
    private static WebUiBusinessException rejection(GameTestHelper helper,
                                                    WebUiServerDispatcher.WebUiAction action,
                                                    ServerPlayer sender, JsonObject payload) {
        try {
            action.handle(sender, payload);
        } catch (WebUiBusinessException expected) {
            return expected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了");
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    /**
     * 幂等确保 player.prefs.set 已注册 (范式同 {@code WebUiServerGameTests.ensureEchoRegistered})。派发器的注册表
     * 是<b>进程级静态</b>且 register 用 putIfAbsent 守卫, 重复注册直接抛, 故已注册就直接 resolve。
     */
    private static WebUiServerDispatcher.WebUiAction ensurePrefsSetRegistered(GameTestHelper helper) {
        WebUiServerDispatcher.WebUiAction existing = WebUiServerDispatcher.resolve("player.prefs.set");
        if (existing == null) {
            PlayerWebUiActions.registerAll();
            existing = WebUiServerDispatcher.resolve("player.prefs.set");
        }
        helper.assertTrue(existing != null, "player.prefs.set 必须由 PlayerWebUiActions.registerAll 注册");
        return existing;
    }
}
