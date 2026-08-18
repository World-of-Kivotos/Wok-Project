package com.miningdim.webui.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "system.batch" 的聚合语义、白名单安全边界与体积守卫。
 *
 * 与 {@link WebUiServerGameTests} 同纪律: 只碰 webui.server + testutil + Gson, 不触 MCEF/客户端渲染类。
 *
 * 本组要钉死的四件事, 每件删掉实现都必挂:
 *  1. 逐条保序执行且回执一一对应 (前端按下标认领, 串号即数据张冠李戴);
 *  2. 写 action 一律进不了批 —— 整批只占一个 requestId, 批内没有防重放保护, 放进去等于开一个重放即二次扣款的口子;
 *  3. 一条坏 action 不带走整屏数据;
 *  4. 体积超限时只损失溢出的那几条, 且整条回执仍收得住尾 (收不住 = 派发器把整批换成一条错误, 前端零数据)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WebUiBatchActionGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui";

    // ============================================================
    // 1. 聚合语义
    // ============================================================

    /**
     * 两条只读 action 合成一批: 回执条数、顺序、各自的 action 名与 ok 位, 以及真实业务字段都必须对上。
     *
     * 断言里带 {@code online} 与 {@code isOp} 这两个真实字段, 而不是只看 ok=true: 后者在 handler 被换成
     * 空实现时照样通过, 而"批量确实跑了真 handler"正是本条唯一要证的事。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void batchRunsEveryEntryInOrder(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonArray results = runBatch(helper, player, "system.serverStatus", "player.isOp");

        helper.assertTrue(results.size() == 2, "批量回执必须逐条对应, 实得 " + results.size() + " 条");

        JsonObject first = results.get(0).getAsJsonObject();
        helper.assertTrue("system.serverStatus".equals(first.get("action").getAsString()),
                "第一条必须仍是 system.serverStatus (顺序即契约), 实得 " + first.get("action").getAsString());
        helper.assertTrue(first.get("ok").getAsBoolean(), "system.serverStatus 应当成功");
        JsonObject status = first.getAsJsonObject("result");
        helper.assertTrue(status.get("online").getAsInt() >= 1,
                "批内 system.serverStatus 必须真跑过 handler (在线人数至少含本 mock 玩家), 实得 "
                        + status.get("online").getAsInt());
        helper.assertTrue(status.has("tps"), "批内回执必须是 handler 的原样结果 (含 tps 字段)");

        JsonObject second = results.get(1).getAsJsonObject();
        helper.assertTrue("player.isOp".equals(second.get("action").getAsString()),
                "第二条必须是 player.isOp, 实得 " + second.get("action").getAsString());
        helper.assertTrue(second.get("ok").getAsBoolean(), "player.isOp 应当成功");
        helper.assertTrue(second.getAsJsonObject("result").has("isOp"),
                "批内 player.isOp 必须回真实的 isOp 字段");
        helper.succeed();
    }

    /**
     * 白名单里的每个名字都必须真的注册过。
     *
     * 这条防的是白名单里的错别字: 那种错在运行期是"该面板永远拿不到数据但也不报装配错误" ——
     * 名字对不上就落进 UNKNOWN_ACTION 分支, 而那条分支本身是合法路径, 没有任何东西会喊出来。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyBatchableActionIsRegistered(GameTestHelper helper) {
        // 注册表由各子系统在 register 期填充; GameTest 进程里已装配完毕, 故此处必然非空。
        helper.assertTrue(WebUiServerDispatcher.resolve("system.batch") != null,
                "system.batch 自身必须已注册");
        for (String action : WebUiBatchAction.batchableActions()) {
            helper.assertTrue(WebUiServerDispatcher.resolve(action) != null,
                    "白名单里的 " + action + " 在服务端注册表里查无此人 (白名单写错了名字)");
        }
        helper.succeed();
    }

    // ============================================================
    // 2. 白名单安全边界
    // ============================================================

    /**
     * 三类进不了批的 action 各自的拒绝码。
     *
     * market.buy 是资金动作: 它若能进批, 一整批只占一个 requestId, 同一批重放就是二次扣款 —— 这是本白名单
     * 存在的全部理由。system.batch 自己也必须被挡 (嵌套批量), 且刻意不写专门的守卫, 靠白名单天然挡住。
     * 未注册的名字回 UNKNOWN_ACTION 而不是 ACTION_NOT_BATCHABLE: 两者的修法不同, 合码会让排查退化成逐个试。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void writeActionsAndNestingAreRefused(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonArray results = runBatch(helper, player,
                "market.buy", "system.batch", "miningdim.no.such.action", "player.isOp");

        assertRefused(helper, results.get(0).getAsJsonObject(),
                "market.buy", WebUiErrorCodes.ACTION_NOT_BATCHABLE);
        assertRefused(helper, results.get(1).getAsJsonObject(),
                "system.batch", WebUiErrorCodes.ACTION_NOT_BATCHABLE);
        assertRefused(helper, results.get(2).getAsJsonObject(),
                "miningdim.no.such.action", WebUiErrorCodes.UNKNOWN_ACTION);

        // 三条被拒之后第四条照跑: 拒绝是逐条的, 不是整批短路。
        JsonObject survivor = results.get(3).getAsJsonObject();
        helper.assertTrue(survivor.get("ok").getAsBoolean(),
                "同批里的合法 action 不该被前面几条的拒绝带走");
        helper.succeed();
    }

    /** 全部写 action 逐个过白名单, 一条都不许在里面。清单是手写的, 这条是它的机械核对。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void noWriteActionIsWhitelisted(GameTestHelper helper) {
        List<String> writes = List.of(
                "admin.economy.set", "admin.job.setLevel", "admin.mining.reset", "admin.setBaseValue",
                "case.apply", "case.open", "job.agent.scan", "job.agent.seal", "job.farmer.sell",
                "job.miner.scan", "job.tarot.buyPack", "market.buy", "market.cancel", "market.place",
                "marriage.buyRing", "marriage.divorce", "marriage.propose", "marriage.respond",
                "marriage.wed", "mining.enter", "mining.leave", "player.prefs.set",
                "quest.claim", "quest.refresh", "quest.turnIn", "system.batch");
        for (String action : writes) {
            helper.assertFalse(WebUiBatchAction.isBatchable(action),
                    "写 action " + action + " 绝不许进批 (批内无防重放, 重放即二次副作用)");
        }
        // 反向锚: 判据本身没被改成恒 false —— 那样上面整条循环会变成永远通过的空校验。
        helper.assertTrue(WebUiBatchAction.isBatchable("player.profile"),
                "只读 action player.profile 必须允许进批 (否则 isBatchable 已退化成恒假)");
        helper.succeed();
    }

    // ============================================================
    // 3. 一条坏 action 不带走整屏
    // ============================================================

    /**
     * 业务拒绝落成该条自己的失败信封, 且信封与单发时逐字节同形 (含 params) ——
     * 前端因此能把它直接喂给既有的 parseServerFailure, 不必为批量另写错误解析。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void businessRejectionStaysInsideItsOwnEntry(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        JsonArray calls = new JsonArray();
        // 槽位 9999 越界 -> SLOT_OUT_OF_RANGE, 带 params {slot,size}; 取一个确定越界的值让断言不依赖背包内容。
        JsonObject detail = new JsonObject();
        detail.addProperty("action", "player.itemDetail");
        JsonObject detailPayload = new JsonObject();
        detailPayload.addProperty("slot", 9999);
        detail.add("payload", detailPayload);
        calls.add(detail);
        calls.add(callOf("player.isOp"));

        JsonArray results = dispatchBatch(helper, player, calls);

        JsonObject failed = results.get(0).getAsJsonObject();
        helper.assertFalse(failed.get("ok").getAsBoolean(), "越界槽位必须落成失败条目");
        JsonObject error = failed.getAsJsonObject("error");
        helper.assertTrue(WebUiErrorCodes.SLOT_OUT_OF_RANGE.equals(error.get("errorCode").getAsString()),
                "失败信封必须带原样的 errorCode, 实得 " + error.get("errorCode").getAsString());
        helper.assertTrue(error.has("retrySameOpeningId"),
                "失败信封必须与单发同形 (含 retrySameOpeningId), 否则前端的形状校验会判契约破裂");
        helper.assertTrue(error.getAsJsonObject("params").get("slot").getAsString().equals("9999"),
                "失败信封的 params 必须原样嵌套 (占位符实参丢了文案就退化成英文原码)");

        helper.assertTrue(results.get(1).getAsJsonObject().get("ok").getAsBoolean(),
                "同批的另一条必须照常成功 —— 一条坏 action 不许带走整屏数据");
        helper.succeed();
    }

    /**
     * 非预期异常 (非业务异常) 同样只落成该条的失败信封, 不冒泡炸整批。
     *
     * 直接喂一个会抛的 handler 给 {@code invoke}: 白名单里每条真实 action 的坏输入都走 WebUiPayloads 的业务
     * 异常 (已实测), 于是没有任何外部输入能驱动到那个兜底分支 —— 而它正是这条保障的唯一实现。
     * 兜底信封刻意<b>没有</b> errorCode (与派发器的 errorJson 同形), 前端据此区分"业务拒绝"与"服务端炸了"。
     */
    /**
     * 转发不得吞掉内层回执里的 null 键。
     *
     * 真机故障 (2026-08-19): 婚姻回执特意用 serializeNulls 发 "spouseUuid": null 表示未婚, 经本通道用默认
     * Gson 转发后该键整条消失; 前端守卫是 spouseUuid === null, undefined 过不了, 于是去 .slice 一个
     * undefined, 整棵 React 树崩掉, 面板全黑。把 GSON 换回 new Gson() 本例必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void batchForwardingKeepsNullValuedKeys(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        String encoded = WebUiBatchAction.invoke(player, "player.profile", new JsonObject(),
                (sender, payload) -> "{\"spouseUuid\":null,\"spouseName\":null,\"marriedDays\":0}");

        JsonObject entry = JsonParser.parseString(encoded).getAsJsonObject();
        JsonObject result = entry.getAsJsonObject("result");
        helper.assertTrue(result.has("spouseUuid"),
                "内层回执的 null 键必须原样带过去, 实得回执 " + result);
        helper.assertTrue(result.get("spouseUuid").isJsonNull(),
                "spouseUuid 必须仍是 JSON null 而不是被改写, 实得 " + result.get("spouseUuid"));
        helper.assertTrue(result.has("spouseName") && result.get("spouseName").isJsonNull(),
                "同一条回执里的其余 null 键同样不得丢失, 实得回执 " + result);
        helper.assertTrue(result.get("marriedDays").getAsInt() == 0,
                "非 null 值不受影响, 实得 " + result.get("marriedDays"));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unexpectedExceptionStaysInsideItsOwnEntry(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        String encoded = WebUiBatchAction.invoke(player, "player.profile", new JsonObject(),
                (sender, payload) -> {
                    throw new IllegalStateException("boom");
                });
        JsonObject entry = JsonParser.parseString(encoded).getAsJsonObject();
        helper.assertFalse(entry.get("ok").getAsBoolean(), "抛异常的 handler 必须落成失败条目而不是冒泡");
        JsonObject error = entry.getAsJsonObject("error");
        helper.assertTrue("boom".equals(error.get("error").getAsString()),
                "兜底信封必须保留原始 message, 实得 " + error.get("error").getAsString());
        helper.assertFalse(error.has("errorCode"),
                "非业务异常的兜底信封不许带 errorCode (那是业务拒绝的标记, 混了前端就分不出服务端炸没炸)");
        helper.succeed();
    }

    // ============================================================
    // 4. 条数与体积守卫
    // ============================================================

    /** 条数超限是整批拒 (条数本身非法时没有"哪几条能跑"可言), 且错误码稳定可被前端识别。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tooManyCallsRejectsTheWholeBatch(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonArray calls = new JsonArray();
        for (int i = 0; i <= WebUiBatchAction.MAX_CALLS; i++) {
            calls.add(callOf("player.isOp"));
        }
        JsonObject payload = new JsonObject();
        payload.add("calls", calls);

        WebUiBusinessException thrown = null;
        try {
            WebUiServerDispatcher.resolve(WebUiBatchAction.ACTION).handle(player, payload);
        } catch (WebUiBusinessException e) {
            thrown = e;
        }
        helper.assertTrue(thrown != null, "超过 MAX_CALLS 必须整批拒");
        helper.assertTrue(WebUiErrorCodes.BATCH_TOO_LARGE.equals(thrown.errorCode()),
                "超限必须回 BATCH_TOO_LARGE, 实得 " + thrown.errorCode());
        helper.assertTrue(thrown.params().get("max").equals(String.valueOf(WebUiBatchAction.MAX_CALLS)),
                "拒绝必须回显上限值, 否则前端只能把上限抄一遍");
        helper.succeed();
    }

    /**
     * 体积守卫: 塞不下的条目退化成溢出标记, 条数不减 (保序), 且整条回执必须收在 limit 以内。
     *
     * limit 走参数而不是真去撑 32767: 那样这条守卫只能等它在真服上第一次失效时才被发现。
     * 关键断言是<b>总长不越界</b> —— 越界的下场是派发器把整批换成一条 RESPONSE_TOO_LARGE, 前端零数据。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oversizedEntriesDegradeToMarkersAndTheBatchStillFits(GameTestHelper helper) {
        /*
         * 1100 这个数是算过的, 不是试出来的: 6 条真实条目合计约 1219 字符 (必然超预算, 保证有条目退化),
         * 而 6 条全退化成标记只需约 907 字符 (在预算内, 保证 assemble 有解 —— 见它的前置条件)。
         * 两个边界之间才是本用例要验的那个中间态: 前几条原样保留, 后几条退化。
         */
        int limit = 1100;
        List<WebUiBatchAction.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            // 每条 ~200 字符的真实形状条目; 6 条合计远超 limit, 迫使后几条退化。
            JsonObject fat = new JsonObject();
            fat.addProperty("action", "player.profile");
            fat.addProperty("ok", true);
            JsonObject result = new JsonObject();
            result.addProperty("filler", "x".repeat(180));
            fat.add("result", result);
            entries.add(new WebUiBatchAction.Entry("player.profile", fat.toString()));
        }

        String assembled = WebUiBatchAction.assemble(entries, limit);
        helper.assertTrue(assembled.length() <= limit,
                "拼装结果必须收在 limit 内 (含为剩余条目预留的位置), 实得 " + assembled.length() + " > " + limit);

        JsonArray results = JsonParser.parseString(assembled).getAsJsonObject().getAsJsonArray("results");
        helper.assertTrue(results.size() == entries.size(),
                "溢出不许丢条 (前端按下标认领), 实得 " + results.size() + " 条");

        int okCount = 0;
        int overflowCount = 0;
        for (int i = 0; i < results.size(); i++) {
            JsonObject entry = results.get(i).getAsJsonObject();
            if (entry.get("ok").getAsBoolean()) {
                okCount++;
                continue;
            }
            overflowCount++;
            helper.assertTrue(WebUiErrorCodes.RESPONSE_TOO_LARGE.equals(
                            entry.getAsJsonObject("error").get("errorCode").getAsString()),
                    "溢出条目必须回 RESPONSE_TOO_LARGE, 前端据此改走单发");
        }
        helper.assertTrue(okCount >= 1, "预算内的头几条必须原样保留, 实得 " + okCount + " 条");
        helper.assertTrue(overflowCount >= 1, "本用例的 6 条必然超预算, 却一条都没退化 —— 守卫没生效");
        helper.succeed();
    }

    /**
     * 预算连"全部退化成标记"都放不下时必须就地抛, 而不是安静地返回一条超长回执。
     *
     * 那种预算无解 (标记已是最短表达), 因此它是装配缺陷而非运行期情况。生产路径恒满足这个前置条件,
     * 但没有它的话, 谁把 limit 传小了就会得到一条越界的回执 —— 而越界正是本方法存在的理由。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void infeasibleLimitIsAnAssemblyDefect(GameTestHelper helper) {
        List<WebUiBatchAction.Entry> entries = List.of(
                new WebUiBatchAction.Entry("player.isOp", "{\"ok\":true}"),
                new WebUiBatchAction.Entry("player.profile", "{\"ok\":true}"));
        boolean threw = false;
        try {
            // 两条的标记合计约 300 字符, 给 100 必然无解。
            WebUiBatchAction.assemble(entries, 100);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        helper.assertTrue(threw, "放不下全部标记的预算必须就地抛, 不许返回越界回执");

        // 反向锚: 够用的预算不许抛 —— 否则上面那条会被一个恒抛的实现骗过去。
        String assembled = WebUiBatchAction.assemble(entries,
                2 * (WebUiBatchAction.MAX_OVERFLOW_MARKER_LENGTH + 1) + 64);
        helper.assertTrue(JsonParser.parseString(assembled).getAsJsonObject()
                        .getAsJsonArray("results").size() == 2,
                "够用的预算必须正常拼出两条");
        helper.succeed();
    }

    /**
     * 预留常量的机械核对: 白名单里最长的 action 名, 其溢出标记也必须塞得进
     * {@code MAX_OVERFLOW_MARKER_LENGTH}。
     *
     * 预留不足的后果不是"标记被截断", 而是整条回执越界 —— 那是零数据。这条把常量与真实标记长度绑在一起,
     * 日后有人改标记文案 (或加一个更长的 action 名) 立刻挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void overflowMarkerFitsItsReservedBudget(GameTestHelper helper) {
        /*
         * 预算取"刚好放得下一条标记再多一点": 既满足 assemble 的前置条件 (放不下标记就是无解, 会抛),
         * 又让下面那条 400 字符的真实条目必然塞不进去, 从而逼出标记本身。
         */
        int limit = WebUiBatchAction.MAX_OVERFLOW_MARKER_LENGTH + 32;
        for (String action : WebUiBatchAction.batchableActions()) {
            String assembled = WebUiBatchAction.assemble(
                    List.of(new WebUiBatchAction.Entry(action, "{\"padding\":\"" + "x".repeat(400) + "\"}")), limit);
            JsonArray results = JsonParser.parseString(assembled).getAsJsonObject().getAsJsonArray("results");
            int markerLength = results.get(0).toString().length();
            helper.assertTrue(markerLength <= WebUiBatchAction.MAX_OVERFLOW_MARKER_LENGTH,
                    action + " 的溢出标记长 " + markerLength + " 超出预留 "
                            + WebUiBatchAction.MAX_OVERFLOW_MARKER_LENGTH + " (预留不足会让整条回执越界)");
        }
        helper.succeed();
    }

    // ============================================================
    // 5. 限流按条数计费
    // ============================================================

    /**
     * 批量必须按真实条数扣令牌, 否则它就是限流旁路 (一批 24 条只付一枚 = 每玩家上限凭空放大 24 倍)。
     *
     * 时间由参数驱动 (限流器不读时钟), 故不必 sleep 制造流逝。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void batchCostsOneTokenPerEntry(GameTestHelper helper) {
        WebUiRateLimiter limiter = new WebUiRateLimiter(10, 1.0);
        UUID player = UUID.randomUUID();
        long now = 0L;

        helper.assertTrue(limiter.tryAcquire(player, now, 4), "满桶 10 枚扣 4 应当放行");
        helper.assertTrue(limiter.tryAcquire(player, now, 4), "余 6 枚扣 4 应当放行");
        helper.assertFalse(limiter.tryAcquire(player, now, 4),
                "余 2 枚扣 4 必须拒 —— 令牌不足时一枚也不许扣 (半批执行没有调用方能处理)");
        helper.assertTrue(limiter.tryAcquire(player, now, 2),
                "上一次被拒不许扣走令牌, 余额应当仍是 2 枚");

        boolean threw = false;
        try {
            limiter.tryAcquire(player, now, 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        helper.assertTrue(threw, "cost 非正数是配置缺陷, 必须就地抛而不是静默放行");
        helper.succeed();
    }

    /**
     * 批量必须<b>真的去扣</b>那些令牌 —— 上一条只验了限流器自己会算账, 验不到派发链上有没有人调它。
     *
     * 这条用例是变异逼出来的: 把 handle 里那行 chargeExtraTokens 删掉, 全库 1290 条一条都不挂 (限流器的
     * 单元断言照旧通过, 因为它测的是限流器而不是调用点)。而那个缺失恰恰把批量变成限流旁路 —— 每玩家上限
     * 凭空放大 24 倍, 这是本文件最不该漏的一条。
     *
     * 走法: 每批 24 条全填不许进批的 action。它们逐条被白名单拒 (不跑任何真 handler, 故本用例很便宜),
     * 但计费发生在拒绝之前 —— 每批扣 23 枚 (第 1 枚按契约由派发器的限流门扣, 本用例直调 handler 未经那道门)。
     * 桶容量 120: 5 批耗 115 枚放行, 第 6 批需 138 枚必被拒。补充速率 30/秒在这几毫秒里可忽略 (约 0.03 枚)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void batchActuallySpendsItsTokens(GameTestHelper helper) {
        // 全新 mock 玩家 = 全新满桶 (令牌桶按 UUID 建, 首次触达即满)。
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        WebUiServerDispatcher.WebUiAction handler = WebUiServerDispatcher.resolve(WebUiBatchAction.ACTION);

        JsonObject payload = new JsonObject();
        JsonArray calls = new JsonArray();
        for (int i = 0; i < WebUiBatchAction.MAX_CALLS; i++) {
            calls.add(callOf("market.buy"));
        }
        payload.add("calls", calls);

        for (int round = 1; round <= 5; round++) {
            String resultJson = handler.handle(player, payload);
            helper.assertTrue(resultJson.contains(WebUiErrorCodes.ACTION_NOT_BATCHABLE),
                    "第 " + round + " 批应当放行 (逐条拒但整批执行)");
        }

        WebUiBusinessException thrown = null;
        try {
            handler.handle(player, payload);
        } catch (WebUiBusinessException e) {
            thrown = e;
        }
        helper.assertTrue(thrown != null,
                "第 6 批必须被限流拒 —— 没被拒说明批量没按条数扣令牌 (每玩家上限被放大了 24 倍)");
        helper.assertTrue(WebUiErrorCodes.TOO_MANY_REQUESTS.equals(thrown.errorCode()),
                "超预算必须回 TOO_MANY_REQUESTS, 实得 " + thrown.errorCode());
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    private static JsonObject callOf(String action) {
        JsonObject call = new JsonObject();
        call.addProperty("action", action);
        call.add("payload", new JsonObject());
        return call;
    }

    private static JsonArray runBatch(GameTestHelper helper, ServerPlayer player, String... actions) {
        JsonArray calls = new JsonArray();
        for (String action : actions) {
            calls.add(callOf(action));
        }
        return dispatchBatch(helper, player, calls);
    }

    /** 走真实注册表取 system.batch 的 handler 并 handle, 回执解析后取 results 数组。 */
    private static JsonArray dispatchBatch(GameTestHelper helper, ServerPlayer player, JsonArray calls) {
        WebUiServerDispatcher.WebUiAction handler = WebUiServerDispatcher.resolve(WebUiBatchAction.ACTION);
        helper.assertTrue(handler != null, "system.batch 必须已注册 (注册点在 WebUiServerSubsystem.register)");
        JsonObject payload = new JsonObject();
        payload.add("calls", calls);
        String resultJson = handler.handle(player, payload);
        helper.assertTrue(resultJson.length() <= net.minecraft.network.FriendlyByteBuf.MAX_STRING_LENGTH,
                "批量回执不得超出下行 writeUtf 上限, 实得 " + resultJson.length() + " 字符");
        return JsonParser.parseString(resultJson).getAsJsonObject().getAsJsonArray("results");
    }

    private static void assertRefused(GameTestHelper helper, JsonObject entry, String action, String expectedCode) {
        helper.assertTrue(action.equals(entry.get("action").getAsString()),
                "被拒条目也必须回显自己的 action 名 (保序认领), 实得 " + entry.get("action").getAsString());
        helper.assertFalse(entry.get("ok").getAsBoolean(), action + " 必须被拒");
        String actual = entry.getAsJsonObject("error").get("errorCode").getAsString();
        helper.assertTrue(expectedCode.equals(actual),
                action + " 的拒绝码必须是 " + expectedCode + ", 实得 " + actual);
    }
}
