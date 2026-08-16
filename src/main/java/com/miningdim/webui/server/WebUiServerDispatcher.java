package com.miningdim.webui.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.network.MiningNetwork;
import com.miningdim.network.S2CWebUiResponse;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web UI 服务端派发器 (Web UI bridge 契约第 6 节)。客户端 CEF 内 JS 经 cefQuery 发起的 action 在此按名查表派发,
 * 服务端唯一写入方 (架构铁律 1): action handler 拿到经服务端校验的 sender 与解析后的 payload, 返回 resultJson 回执。
 *
 * action 注册表由各子系统 (WebUiServerSubsystem 及后续业务子系统) 在 register 期经 {@link #register} 填充;
 * 用 ConcurrentHashMap 容忍多子系统注册期写入与网络线程读取的可见性 (注册期一次性写, 运行期只读)。
 *
 * 异常纪律 (CLAUDE.md C9 / 契约第 6 节): {@link #dispatchAndRespond} 是最外层 Gateway 边界, 是本子系统
 * 唯一允许 try-catch 的位置。action handler 内异常自然抛出, 在此统一捕获 -> 回执 success=false。预期业务拒绝
 * 用 {@link WebUiBusinessException} 返回稳定 errorCode 且不打印堆栈；其余异常保留 WARN 现场。handler 内部严禁吞异常。
 */
public final class WebUiServerDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/webui");

    /** action 名 -> 处理器。注册期写, 运行期 (网络线程) 读; ConcurrentHashMap 保证发布可见性。 */
    private static final Map<String, WebUiAction> ACTIONS = new ConcurrentHashMap<>();

    /** 复用单一 Gson 实例构造 resultJson (无定制配置需求, 线程安全可静态共享)。 */
    private static final Gson GSON = new Gson();

    /**
     * 回执超出下行上限时的替代回执 (见 {@link #respond})。
     *
     * 预先算好而不是现造: 这条回执正是"造不出合法回执"时的退路, 它自己必须无条件编得出去 —— 现造就又多了一次
     * 依赖运行期入参的机会。message 是常量、params 为空, 故长度恒为几十字符。
     */
    private static final String RESPONSE_TOO_LARGE_JSON = businessErrorJson(new WebUiBusinessException(
            WebUiErrorCodes.RESPONSE_TOO_LARGE, "server response exceeded the downstream size limit", false));

    /**
     * 判重命中时的替代回执 (F045)。与 {@link #RESPONSE_TOO_LARGE_JSON} 同规格预先算好: 它是"拒绝"路径本身,
     * 必须无条件编得出去, 不该在判重短路那一刻现造。
     *
     * retrySameOpeningId 必须是 true (复核修正, 原实现误写 false): 判重命中恰恰证明这个 requestId 对应的
     * handler 已经真实执行过一次 —— 对 case.open 而言, {@code CaseOpeningService.open} 的 javadoc 明写
     * "Safe to replay across reconnects/restarts", 即同一 openingId 的重放是幂等续跑, 不会二次扣费。
     * 若这里回 false, 前端 (case-opening.html 的 shouldRetrySameOpening) 会按 false 语义丢弃 pendingOpeningId
     * 并在下次点击时铸造一个全新 openingId —— 那是一次独立的新开箱请求, 若原始那次其实已经成功扣费+发货,
     * 玩家就会被二次收费。回 true 才是把"这次执行结果未知"正确翻译成"继续用同一个幂等键安全续跑"，
     * 与页面自身对所有未识别错误的默认策略 (同文件 1396-1397 行 "未知错误无法证明服务端没有完成扣款与入库；
     * 默认复用流水号避免重复开箱") 一致。
     */
    static final String DUPLICATE_REQUEST_JSON = businessErrorJson(new WebUiBusinessException(
            WebUiErrorCodes.DUPLICATE_REQUEST, "这次请求已经处理过了，请勿重复提交", true));

    /**
     * 限流命中时的替代回执 (F008)。与 {@link #RESPONSE_TOO_LARGE_JSON} 同规格预先算好, 理由相同: 它是"拒绝"
     * 路径本身, 必须无条件编得出去。
     */
    static final String TOO_MANY_REQUESTS_JSON = businessErrorJson(new WebUiBusinessException(
            WebUiErrorCodes.TOO_MANY_REQUESTS, "操作太频繁了，请稍后再试", false));

    /**
     * 每玩家令牌桶 (F008): 突发 120、每秒补充 30。
     *
     * 原值 (40 / 10) 的注释自己写着"真要做容量规划应量化冷启动实际并发拉取数, 而不是在此处再猜一个数"。
     * 现在量过了, 所以换成实测值:
     *
     *  - 冷启动一次性拉取 <b>11 条</b>: 外壳 4 条 (player.isOp / player.prefs.get / player.profile /
     *    system.serverStatus) + 首页 7 条 (economy.status / economy.today / hub.panels / marriage.state /
     *    mining.myStatus / mining.overview / player.profile);
     *  - 开发态 React StrictMode 会把每个 effect 跑两遍, 于是同一批实际是 <b>22 条</b>;
     *  - 再叠上开面板后立刻翻一次页 (另一页的那批) 与两条轮询, 40 的突发头寸当场见底 —— 真机实测就是
     *    hub.panels 与 mining.overview 一起回 TOO_MANY_REQUESTS。
     *
     * 120 给了实测冷启动约五倍余量, 30/秒对稳态轮询 (miningStatus 3 秒 + marriageState 10 秒, 合计约
     * 0.43 次/秒) 仍是七十倍以上。它依旧是防失控的护栏而不是容量规划: 正常游玩摸不到, 脚本刷仍会被挡。
     */
    private static final WebUiRateLimiter RATE_LIMITER = new WebUiRateLimiter(120, 30.0);

    /**
     * 每玩家保留的最近已处理 requestId 上限 (滑动窗口容量)。market.buy/place/cancel 这类改资金/库存的副作用
     * action 都经本派发器, 一个 requestId 只能被处理一次 (契约第八章红线 6 / 5.3 防重放)。超窗后逐出最旧的
     * requestId 以防单玩家内存无界增长; 客户端桥接层 requestId 是 AtomicLong 单调自增 (C2SWebUiRequest 第 16 行),
     * 正常客户端永不复用旧 id, 故被逐出的旧 id 不会再被合法请求触达, 重放攻击需在同玩家其后再发满 256 个不同
     * 请求才能把目标 id 挤出窗口, 攻击面被压到最近 256 请求内, 足覆盖任何真实双击/重复提交场景。
     */
    private static final int MAX_TRACKED_REQUEST_IDS_PER_PLAYER = 256;

    /**
     * 每玩家最近已处理的 requestId 滑动窗口 (防重复提交/重放, 契约第八章红线 6)。key = sender UUID,
     * value = 有界 {@link LinkedHashSet} (按插入序, 超 {@link #MAX_TRACKED_REQUEST_IDS_PER_PLAYER} 逐出最旧)。
     *
     * 派发主路径在服务器主线程 (C2SWebUiRequest.handle 经 enqueueWork 切回主线程), 故同玩家两次 dispatch 无真实
     * 并发; 但玩家登出清理 (PlayerLoggedOutEvent) 与登出竞态下的迟到包可能跨线程触达本表, 故 outer map 用
     * ConcurrentHashMap, 对单个玩家窗口 (LinkedHashSet 非线程安全) 的读改在 synchronized(window) 内完成保证原子。
     */
    private static final Map<UUID, Set<Long>> PROCESSED_REQUEST_IDS = new ConcurrentHashMap<>();

    private WebUiServerDispatcher() {
    }

    /**
     * action 处理器 (契约第 6 节)。入参为服务端校验过的 sender 与解析后的 payload (JsonObject),
     * 返回 resultJson 字符串。坏输入 (缺字段/类型错) 自然抛, 由 {@link #dispatchAndRespond} 的 Gateway 兜底,
     * 严禁在实现内 try-catch 生吞。
     */
    @FunctionalInterface
    public interface WebUiAction {
        String handle(ServerPlayer sender, JsonObject payload);
    }

    /**
     * 注册一个 action 处理器 (子系统 register 期调用)。重复注册同名 action 是装配缺陷 (两个子系统抢同一 action 名),
     * 按 C9 自然抛 IllegalStateException 暴露, 不静默覆盖。
     */
    public static void register(String action, WebUiAction handler) {
        WebUiAction prev = ACTIONS.putIfAbsent(action, handler);
        if (prev != null) {
            throw new IllegalStateException("duplicate Web UI action registration: " + action);
        }
    }

    /**
     * 派发并回执 (契约第 6 节, 最外层 Gateway 边界)。查 action -> 解析 payloadJson -> handle 得 resultJson
     * -> 经 {@link MiningNetwork#sendWebUiResponse} 回 success=true。任何环节 (未知 action / payload 解析失败 /
     * handler 业务异常) 抛出时, 在此统一捕获 -> 回执 success=false + {"error":"<message>"} 并记日志, 不重抛。
     *
     * 唯一允许 try-catch 的边界: 网络线程不应因业务错误中断, 故在此收口为回执。其余各层 (packet handle /
     * action handler) 一律让异常自然冒泡到此。
     */
    public static void dispatchAndRespond(ServerPlayer sender, long requestId, String action, String payloadJson) {
        // 限流门 (F008), 在判重之前: 被限流的请求不该把自己的 requestId 烧进防重放窗口, 否则客户端拿同一 id
        // 重试只会得到 duplicate_request 而不是重新获得执行机会。DEBUG 而非 WARN: 这条失败任何人都能无限触发,
        // 正是本类要防的日志放大器 (与判重/未知 action 同纪律)。
        if (!RATE_LIMITER.tryAcquire(sender.getUUID(), System.nanoTime())) {
            LOGGER.debug("Web UI action '{}' throttled for player {} (requestId={})",
                    action, sender.getName().getString(), requestId);
            respond(sender, requestId, false, TOO_MANY_REQUESTS_JSON);
            return;
        }
        // 防重放/防重复提交 (契约第八章红线 6 / 5.3): 在执行任何 handler 副作用前先登记 requestId。命中已处理窗口即
        // 短路回 success=false + errorCode DUPLICATE_REQUEST, 不再触达 handler。登记前置 (而非业务成功后) 保证即便
        // handler 中途抛异常, 同 requestId 的重试也无法二次执行其改资金/库存副作用 —— 重试必须换新 requestId。
        // 用带 errorCode 的 DUPLICATE_REQUEST_JSON 而非裸 errorJson: 裸 errorJson 没有 errorCode, 前端只能把
        // 英文机器串原样呈给玩家 (webui/src/lib/errorText.ts 顶部已写明未收录的码回退服务端原文)。
        if (!markRequestProcessed(sender.getUUID(), requestId)) {
            respond(sender, requestId, false, DUPLICATE_REQUEST_JSON);
            return;
        }
        try {
            WebUiAction handler = ACTIONS.get(action);
            if (handler == null) {
                throw new WebUiBusinessException(WebUiErrorCodes.UNKNOWN_ACTION,
                        "unknown Web UI action: " + action, false);
            }
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();
            String resultJson = handler.handle(sender, payload);
            respond(sender, requestId, true, resultJson);
        } catch (WebUiBusinessException e) {
            // Expected player-facing rejection: stable machine code, explicit retry policy, no WARN stack-log DoS.
            LOGGER.debug("Web UI action '{}' rejected for player {} (requestId={}, errorCode={}): {}",
                    action, sender.getName().getString(), requestId, e.errorCode(), e.getMessage());
            respond(sender, requestId, false, businessErrorJson(e));
        } catch (Exception e) {
            // Gateway 兜底: 业务错误转为客户端可解析的失败回执, 并记日志保留现场; 不重抛打断网络线程。
            LOGGER.warn("Web UI action '{}' failed for player {} (requestId={})",
                    action, sender.getName().getString(), requestId, e);
            respond(sender, requestId, false, errorJson(e.getMessage()));
        }
    }

    /**
     * 下行回执的唯一收口, 兼体积守卫。
     *
     * 为什么守在这里而不是各个造 JSON 的地方: 入站 {@code C2SWebUiRequest.decode} 的两个 readUtf 上限同为
     * {@link FriendlyByteBuf#MAX_STRING_LENGTH}, 于是 action 名与 payloadJson 都是客户端可控的超长标量 ——
     * 未知 action 拼出的 "unknown Web UI action: " + action, 以及 Gson 对非对象 payload 抛的
     * "Not a JSON Object: " + 元素全文, 都会把异常 message 撑过上限。而这两条路径的回执是在上面
     * {@code catch} 块<b>内部</b>发出的, 那一下 EncoderException 不再有任何 catch 兜住: requestId 已被防重放
     * 窗口烧掉 (同 id 重试只得 duplicate_request), 前端 Promise 永不 settle, 界面就挂死在 loading。
     *
     * 成功路径同样经此 —— 聚合类 action 的回执随数据长大, 迟早撞同一堵墙, 而那时症状与病因隔得更远。
     *
     * 超限时换成定长回执而不是静默丢弃: 丢弃与"编不出去"对前端是同一种表现 (永不 settle), 换一条能编出去的
     * 失败回执才能让 Promise 落地并让玩家看见原因。
     */
    private static void respond(ServerPlayer sender, long requestId, boolean success, String resultJson) {
        if (resultJson.length() <= FriendlyByteBuf.MAX_STRING_LENGTH) {
            MiningNetwork.sendWebUiResponse(sender, new S2CWebUiResponse(requestId, success, resultJson));
            return;
        }
        LOGGER.warn("Web UI response for player {} (requestId={}) exceeded the {}-char downstream limit ({} chars); "
                        + "replaced with {}", sender.getName().getString(), requestId,
                FriendlyByteBuf.MAX_STRING_LENGTH, resultJson.length(), WebUiErrorCodes.RESPONSE_TOO_LARGE);
        MiningNetwork.sendWebUiResponse(sender, new S2CWebUiResponse(requestId, false, RESPONSE_TOO_LARGE_JSON));
    }

    /**
     * 查注册表取 action 处理器 (诊断 / GameTest 用; 未注册返回 null)。运行期派发走 {@link #dispatchAndRespond}
     * 的 Gateway 路径, 本方法供进程内直接定位已注册处理器 (服务端纯逻辑校验), 不经网络层。
     */
    public static WebUiAction resolve(String action) {
        return ACTIONS.get(action);
    }

    /**
     * 全部已注册 action 名 (字典序)。供 system.handshake 下发给页面做契约比对 —— 远端托管路线下浏览器可能缓存
     * 旧构建, 调用已删除的 action 只会逐个静默失败 (架构文档 10.6), 页面拿到本清单即可在启动时一次性自检。
     *
     * 排序保证同一 action 集合的输出稳定, 让前端能直接比对而不必自行归一化。
     */
    public static List<String> registeredActions() {
        List<String> names = new ArrayList<>(ACTIONS.keySet());
        Collections.sort(names);
        return names;
    }

    /**
     * 登记一次 requestId 到该玩家的滑动窗口 (防重放核心)。首次见到返回 true (放行执行 handler); 已在窗口内返回
     * false (重复请求, 调用方短路)。窗口满 {@link #MAX_TRACKED_REQUEST_IDS_PER_PLAYER} 时按插入序逐出最旧 id。
     *
     * 对单玩家窗口的 contains + add + 逐出在 synchronized(window) 内成原子, 防登出清理或登出竞态迟到包并发改坏
     * LinkedHashSet 的内部结构。窗口对象本身经 computeIfAbsent 在 ConcurrentHashMap 上原子获取/创建。
     */
    private static boolean markRequestProcessed(UUID sender, long requestId) {
        Set<Long> window = PROCESSED_REQUEST_IDS.computeIfAbsent(sender, k -> new LinkedHashSet<>());
        synchronized (window) {
            if (!window.add(requestId)) {
                return false;
            }
            if (window.size() > MAX_TRACKED_REQUEST_IDS_PER_PLAYER) {
                java.util.Iterator<Long> it = window.iterator();
                it.next();
                it.remove();
            }
            return true;
        }
    }

    /**
     * 清除某玩家的 requestId 滑动窗口与令牌桶 (玩家登出时由 {@link WebUiServerSubsystem} 挂的
     * PlayerLoggedOutEvent 调用)。防止离线玩家的窗口/令牌桶长期驻留造成内存泄漏; 同一玩家重连后从空窗口与满桶
     * 重新开始 (新会话 requestId 仍单调自增, 不复用)。
     */
    public static void clearPlayer(UUID sender) {
        PROCESSED_REQUEST_IDS.remove(sender);
        RATE_LIMITER.clear(sender);
    }

    /** 构造 {"error":"<message>"} 失败回执 (经 Gson 转义, 防 message 内引号破坏 JSON)。 */
    private static String errorJson(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message == null ? "unknown error" : message);
        return GSON.toJson(obj);
    }

    /**
     * 构造业务拒绝回执 {"error","errorCode","retrySameOpeningId"} + 可选 {"params"}。
     *
     * params 为空时整键不写 (而非发空对象): 存量 case.* 的回执形状逐字节不变, 前端对"没有占位符实参"
     * 与"占位符实参形状不对"才能区分处理 —— 前者是正常形态, 后者是契约破裂。
     */
    static String businessErrorJson(WebUiBusinessException error) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", error.getMessage());
        obj.addProperty("errorCode", error.errorCode());
        obj.addProperty("retrySameOpeningId", error.retrySameOpeningId());
        Map<String, String> params = error.params();
        if (!params.isEmpty()) {
            JsonObject paramsJson = new JsonObject();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                paramsJson.addProperty(entry.getKey(), entry.getValue());
            }
            obj.add("params", paramsJson);
        }
        return GSON.toJson(obj);
    }
}
