package com.miningdim.webui.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "system.batch" —— 一次往返跑完多条<b>只读</b> action。
 *
 * 为什么需要它: 每条 action 是一个独立的 C2S 包 + 一次主线程任务 + 一条 S2C 回执。平板冷启动实测 11 条
 * (外壳 4 + 首页 7), 翻一页再来一批。11 次往返的代价不在带宽而在<b>次数</b>: 每次都要排一遍主线程任务队列,
 * 且客户端那 11 个 Promise 各自到达, 于是同一屏的数字是逐个跳出来的。合成一条之后, 11 个 handler 在同一个
 * 主线程任务里跑完, 一起回, 一起上屏。
 *
 * <b>白名单是安全边界, 不是性能清单。</b> 整批只占一个 requestId, 于是批内 handler 拿不到派发器那道
 * "同 requestId 只执行一次"的防重放保护 (见 {@code WebUiServerDispatcher.markRequestProcessed})。只读 action
 * 重放无害; 写 action 重放会二次扣款、二次发货。因此往 {@link #BATCHABLE} 里加名字等于宣称"这条 action 重放
 * 无副作用", 加错的代价是资金漏洞而不是性能回退。判据只有一条: 该 handler 不得改变任何玩家可见的持久状态
 * (幂等的惰性初始化不算 —— 如 quest.board 的当日任务板按天生成, 调两次得同一块板)。
 *
 * 异常纪律: 本类是<b>第二个 Gateway 边界</b>。与 {@code WebUiServerDispatcher.dispatchAndRespond} 同构 ——
 * 逐条 handler 的异常在此翻成该条自己的失败信封, 不让一条坏 action 炸掉整屏; 与那边同样按
 * 业务异常 DEBUG / 其余 WARN 带堆栈的口径记日志, 现场一条不少。批本身的结构性错误 (calls 不是数组、
 * 条数超限、条目缺 action/payload 键) 一律自然抛, 由派发器兜底整批拒 —— 那些是契约破裂, 不是某一条的失败。
 */
final class WebUiBatchAction {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/webui");

    private static final Gson GSON = new Gson();

    static final String ACTION = "system.batch";

    /**
     * 单批条数上限。24 覆盖实测冷启动 11 条约两倍余量, 同时把"一条请求最多能给主线程排多少个 handler"钉死。
     *
     * 不设成配置键: 它是协议形状的一部分 (超限即整批拒), 而不是运营旋钮; 且前端的批量调度器要按同一个数
     * 切批, 两侧各读一份配置只会漂移。
     */
    static final int MAX_CALLS = 24;

    /**
     * 溢出标记条目的长度上限。{@link #assemble} 用它为"后面还没编的条目"预留位置, 保证整批永远收得住尾 ——
     * 预留不足的后果是最后几条既编不进去、又没有位置放它们的失败标记, 于是整条回执撑爆 writeUtf。
     *
     * 160 由 {@code WebUiBatchActionGameTests.overflowMarkerFitsItsReservedBudget} 对全部白名单 action 名逐个
     * 核过, 不是估的 —— 第一版把它写成 160 而标记实测 181 (message 写长了), 那条用例当场挂, 于是有了下面
     * 那句短 message: 标记的语义全在 errorCode 里, message 每多一个字都要在预留里乘以条数。
     * 当前最长的白名单名 (admin.economy.balance, 21 字符) 对应标记 147 字符, 余 13 字符裕量。
     */
    static final int MAX_OVERFLOW_MARKER_LENGTH = 160;

    /** {@code {"results":[} 加收尾 {@code ]}}。手工拼装而不是 JsonArray 一次序列化, 才能逐条精确计账。 */
    private static final int ENVELOPE_OVERHEAD = "{\"results\":[]}".length();

    /**
     * 允许进批的只读 action 全集。
     *
     * 刻意手工列举而不是从注册表按名字前缀猜 (如"含 .state 的都算只读"): 那种规则挡不住下一个叫
     * job.xxx.state 却在里面扣钱的 handler, 而它失效时不报错。清单缺一条的后果只是那条走单发,
     * 多一条写 action 的后果是资金漏洞 —— 两侧代价不对称, 所以宁可漏。
     *
     * 不在表内的写 action 一律逐条拒 (errorCode {@code ACTION_NOT_BATCHABLE}), 包括 {@link #ACTION} 自己:
     * 嵌套批量不需要专门的守卫, 白名单天然把它挡在外面。
     */
    private static final Set<String> BATCHABLE = Set.of(
            "admin.economy.balance",
            "admin.listItems",
            "case.state",
            "champion.codex",
            "champion.inspect",
            "economy.priceTable",
            "economy.status",
            "economy.today",
            "hub.panels",
            "job.agent.state",
            "job.blueprints",
            "job.brewer.state",
            "job.chef.state",
            "job.engineer.state",
            "job.farmer.state",
            "job.miner.state",
            "job.munitions.state",
            "job.progress",
            "job.tarot.state",
            "market.baseValue",
            "market.categories",
            "market.feePreview",
            "market.history",
            "market.list",
            "market.mine",
            "market.p2pCap",
            "market.pendingPayout",
            "market.tradable",
            "marriage.sharedInv",
            "marriage.state",
            "mining.myStatus",
            "mining.overview",
            "player.inventory",
            "player.isOp",
            "player.itemDetail",
            "player.prefs.get",
            "player.profile",
            "player.roster",
            "player.wallet",
            "quest.board",
            "system.serverStatus");

    private WebUiBatchAction() {
    }

    static void registerAll() {
        WebUiServerDispatcher.register(ACTION, WebUiBatchAction::handle);
    }

    /** 该 action 是否允许进批 (GameTest 与派发器共用同一份判据, 不许各自再写一遍)。 */
    static boolean isBatchable(String action) {
        return BATCHABLE.contains(action);
    }

    /** 白名单全集 (GameTest 用来逐个核 action 名真的注册过、且溢出标记长度都在预留内)。 */
    static Set<String> batchableActions() {
        return BATCHABLE;
    }

    /**
     * payload = {@code {"calls":[{"action":"...","payload":{...}}, ...]}};
     * 回执 = {@code {"results":[{"action":"...","ok":true,"result":{...}} | {"action":"...","ok":false,"error":{...}}]}}。
     *
     * 回执逐条<b>保序</b>且与入参一一对应 —— 前端按下标认领, 不按 action 名匹配 (同一批里允许出现两条同名
     * 不同 payload 的调用, 按名匹配会串号)。
     *
     * 失败条目的 error 是<b>标准失败信封原样嵌套</b> (与单发时 onFailure 拿到的那坨完全同形), 前端因此可以
     * 把它直接喂给既有的 parseServerFailure, 不必为批量另写一套错误解析。
     */
    private static String handle(ServerPlayer sender, JsonObject payload) {
        JsonArray calls = payload.getAsJsonArray("calls");
        if (calls.size() > MAX_CALLS) {
            throw new WebUiBusinessException(WebUiErrorCodes.BATCH_TOO_LARGE,
                    "batched call count " + calls.size() + " exceeds the per-request maximum " + MAX_CALLS,
                    false, Map.of("count", String.valueOf(calls.size()), "max", String.valueOf(MAX_CALLS)));
        }
        /*
         * 按真实条数补扣令牌: 派发器的限流门已为本次请求扣了一枚, 这里补 size - 1 枚。不补的话聚合请求就是
         * 一个限流旁路 —— 一批 24 条只付一枚, 而那 24 个 handler 在主线程上是实打实各跑一次。
         * 令牌不足时整批拒: 半批执行没有任何调用方能处理 (前端的恢复路径是"整批重来", 不是"补齐缺的几条")。
         */
        if (!WebUiServerDispatcher.chargeExtraTokens(sender.getUUID(), calls.size() - 1)) {
            throw new WebUiBusinessException(WebUiErrorCodes.TOO_MANY_REQUESTS,
                    "not enough rate-limit budget for a batch of " + calls.size(), false);
        }

        List<Entry> entries = new ArrayList<>(calls.size());
        for (JsonElement element : calls) {
            JsonObject callObject = element.getAsJsonObject();
            // 缺键自然抛 (整批拒): 契约破裂时补默认值只会让"前端拼错了 payload"长成一条看起来合法的成功回执。
            String action = callObject.get("action").getAsString();
            JsonObject callPayload = callObject.get("payload").getAsJsonObject();
            entries.add(new Entry(action, runOne(sender, action, callPayload)));
        }
        return assemble(entries, FriendlyByteBuf.MAX_STRING_LENGTH);
    }

    /**
     * 跑一条并把结果编成条目 JSON。三种拒绝 (未注册 / 不许进批 / 业务拒绝) 与一种兜底 (其余异常) 都落成
     * 这一条自己的失败信封, 不影响同批其它条目。
     */
    private static String runOne(ServerPlayer sender, String action, JsonObject callPayload) {
        if (!isBatchable(action)) {
            /*
             * 未注册与"注册了但不许进批"刻意回不同的码: 前者是契约漂移 (前端调了服务端没有的 action, 握手
             * 自检该抓到), 后者是前端把一条写 action 误塞进了批量 —— 两种的修法完全不同, 合成一个码会让
             * 排查从"看一眼白名单"退化成"逐个试"。
             */
            String code = WebUiServerDispatcher.resolve(action) == null
                    ? WebUiErrorCodes.UNKNOWN_ACTION
                    : WebUiErrorCodes.ACTION_NOT_BATCHABLE;
            return failureEntry(action, new WebUiBusinessException(code,
                    "action '" + action + "' is not available inside " + ACTION, false));
        }
        return invoke(sender, action, callPayload, WebUiServerDispatcher.resolve(action));
    }

    /**
     * 跑一个已定位的 handler 并把它的异常翻成条目自己的失败信封。
     *
     * handler 是入参而不是就地 resolve, 为的是让"非预期异常不炸整批"这条真能被测到: 白名单里每条 action 的
     * 坏输入校验都走 {@code WebUiPayloads} 的业务异常 (实测过), 于是没有任何真实 action 能从外部驱动到下面
     * 那个 {@code catch (Exception)} 分支 —— 而它恰恰是"一条坏 action 会不会带走整屏数据"的唯一保障。
     */
    static String invoke(ServerPlayer sender, String action, JsonObject callPayload,
                         WebUiServerDispatcher.WebUiAction handler) {
        try {
            JsonObject entry = new JsonObject();
            entry.addProperty("action", action);
            entry.addProperty("ok", true);
            // 解析后原样嵌套而不是把 resultJson 当字符串塞进去: 后者要对整坨回执做一遍转义, 每个引号变两个
            // 字符, 32767 的下行预算凭空缩水近一半。顺带把 handler 吐出的东西验明是合法 JSON。
            entry.add("result", JsonParser.parseString(handler.handle(sender, callPayload)));
            return GSON.toJson(entry);
        } catch (WebUiBusinessException e) {
            LOGGER.debug("batched Web UI action '{}' rejected for player {} (errorCode={}): {}",
                    action, sender.getName().getString(), e.errorCode(), e.getMessage());
            return failureEntry(action, e);
        } catch (Exception e) {
            // 与派发器同口径: 非预期异常保留 WARN 现场 (含堆栈), 只是收口位置换成了这一条条目。
            LOGGER.warn("batched Web UI action '{}' failed for player {}", action, sender.getName().getString(), e);
            return rawFailureEntry(action, WebUiServerDispatcher.errorJson(e.getMessage()));
        }
    }

    private static String failureEntry(String action, WebUiBusinessException error) {
        return rawFailureEntry(action, WebUiServerDispatcher.businessErrorJson(error));
    }

    private static String rawFailureEntry(String action, String failureEnvelopeJson) {
        JsonObject entry = new JsonObject();
        entry.addProperty("action", action);
        entry.addProperty("ok", false);
        entry.add("error", JsonParser.parseString(failureEnvelopeJson));
        return GSON.toJson(entry);
    }

    /**
     * 单条放不进本批时的替代条目。语义与 {@code respond} 的整条替换一致, 只是粒度细到一条。
     *
     * message 刻意极短: 这条标记的长度要乘以条数进 {@link #MAX_OVERFLOW_MARKER_LENGTH} 的预留, 而它的语义
     * 全部由 errorCode 承载 (前端认码不认文本, 见 batch.ts 的 RESPONSE_TOO_LARGE 分支)。
     */
    private static String overflowEntry(String action) {
        return rawFailureEntry(action, WebUiServerDispatcher.businessErrorJson(new WebUiBusinessException(
                WebUiErrorCodes.RESPONSE_TOO_LARGE, "batch entry too large", false)));
    }

    /**
     * 逐条计账拼装回执, 超预算的条目退化成溢出标记。
     *
     * {@code limit} 是参数而不是直读 {@link FriendlyByteBuf#MAX_STRING_LENGTH}, 为的是让体积守卫本身可测:
     * 真要靠真实数据把 32767 撑爆才能验这条, 那这个守卫就只能等它在真服上第一次失效时才被发现。
     *
     * 关键是<b>为剩余条目预留</b>: 只判"当前这条放不放得下"是不够的 —— 第 20 条恰好塞满时, 后面 4 条连自己
     * 的失败标记都没地方写, 于是整条回执越界, 而越界的下场是派发器把<b>整批</b>换成一条 RESPONSE_TOO_LARGE,
     * 前端一条数据都拿不到。预留之后, 溢出只损失溢出的那几条, 前端可以按 errorCode 把它们改走单发。
     */
    static String assemble(List<Entry> entries, int limit) {
        /*
         * 前置条件: 预算必须至少放得下"全部条目都退化成标记"的那一版。
         *
         * 放不下就无解 —— 标记已经是最短的表达, 没有比它更小的东西可写, 于是那种预算下必然越界。生产路径
         * 恒满足 (24 条 x 161 + 14 = 3878 远小于 32767), 所以这是装配缺陷而不是运行期情况, 就地抛。
         *
         * 这条是写完 assemble 之后被自己的用例逼出来的: 第一版没有它, 于是"limit 给小了"的表现不是报错,
         * 而是安静地返回一条超长回执 —— 那正是本方法要防的东西。
         */
        int worstCase = ENVELOPE_OVERHEAD + entries.size() * (MAX_OVERFLOW_MARKER_LENGTH + 1);
        if (limit < worstCase) {
            throw new IllegalArgumentException("batch size limit " + limit + " cannot hold " + entries.size()
                    + " overflow markers (needs at least " + worstCase + ")");
        }
        StringBuilder out = new StringBuilder("{\"results\":[");
        int used = ENVELOPE_OVERHEAD;
        int overflowed = 0;
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            int separator = index == 0 ? 0 : 1;
            int reserveForRest = (entries.size() - index - 1) * (MAX_OVERFLOW_MARKER_LENGTH + 1);
            String encoded = entry.json();
            if (used + separator + encoded.length() + reserveForRest > limit) {
                encoded = overflowEntry(entry.action());
                overflowed++;
            }
            if (separator == 1) {
                out.append(',');
            }
            out.append(encoded);
            used += separator + encoded.length();
        }
        out.append("]}");
        if (overflowed > 0) {
            LOGGER.warn("{} of {} batched Web UI entries did not fit in the {}-char downstream limit and were "
                    + "replaced with {}; the page will refetch them individually",
                    overflowed, entries.size(), limit, WebUiErrorCodes.RESPONSE_TOO_LARGE);
        }
        return out.toString();
    }

    /** 一条已编好的条目。留着 action 名是为了溢出时还能编出那条的标记。 */
    record Entry(String action, String json) {
    }
}
