package com.miningdim.webui.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.network.MiningNetwork;
import com.miningdim.network.S2CWebUiResponse;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
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
        // 防重放/防重复提交 (契约第八章红线 6 / 5.3): 在执行任何 handler 副作用前先登记 requestId。命中已处理窗口即
        // 短路回 success=false {"error":"duplicate_request"}, 不再触达 handler。登记前置 (而非业务成功后) 保证即便
        // handler 中途抛异常, 同 requestId 的重试也无法二次执行其改资金/库存副作用 —— 重试必须换新 requestId。
        if (!markRequestProcessed(sender.getUUID(), requestId)) {
            MiningNetwork.sendWebUiResponse(sender,
                    new S2CWebUiResponse(requestId, false, errorJson("duplicate_request")));
            return;
        }
        try {
            WebUiAction handler = ACTIONS.get(action);
            if (handler == null) {
                throw new IllegalArgumentException("unknown Web UI action: " + action);
            }
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();
            String resultJson = handler.handle(sender, payload);
            MiningNetwork.sendWebUiResponse(sender, new S2CWebUiResponse(requestId, true, resultJson));
        } catch (WebUiBusinessException e) {
            // Expected player-facing rejection: stable machine code, explicit retry policy, no WARN stack-log DoS.
            LOGGER.debug("Web UI action '{}' rejected for player {} (requestId={}, errorCode={}): {}",
                    action, sender.getName().getString(), requestId, e.errorCode(), e.getMessage());
            MiningNetwork.sendWebUiResponse(sender,
                    new S2CWebUiResponse(requestId, false, businessErrorJson(e)));
        } catch (Exception e) {
            // Gateway 兜底: 业务错误转为客户端可解析的失败回执, 并记日志保留现场; 不重抛打断网络线程。
            LOGGER.warn("Web UI action '{}' failed for player {} (requestId={})",
                    action, sender.getName().getString(), requestId, e);
            MiningNetwork.sendWebUiResponse(sender,
                    new S2CWebUiResponse(requestId, false, errorJson(e.getMessage())));
        }
    }

    /**
     * 查注册表取 action 处理器 (诊断 / GameTest 用; 未注册返回 null)。运行期派发走 {@link #dispatchAndRespond}
     * 的 Gateway 路径, 本方法供进程内直接定位已注册处理器 (服务端纯逻辑校验), 不经网络层。
     */
    public static WebUiAction resolve(String action) {
        return ACTIONS.get(action);
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
     * 清除某玩家的 requestId 滑动窗口 (玩家登出时由 {@link WebUiServerSubsystem} 挂的 PlayerLoggedOutEvent 调用)。
     * 防止离线玩家的窗口长期驻留造成内存泄漏; 同一玩家重连后从空窗口开始 (新会话 requestId 仍单调自增, 不复用)。
     */
    public static void clearPlayer(UUID sender) {
        PROCESSED_REQUEST_IDS.remove(sender);
    }

    /** 构造 {"error":"<message>"} 失败回执 (经 Gson 转义, 防 message 内引号破坏 JSON)。 */
    private static String errorJson(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message == null ? "unknown error" : message);
        return GSON.toJson(obj);
    }

    static String businessErrorJson(WebUiBusinessException error) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", error.getMessage());
        obj.addProperty("errorCode", error.errorCode());
        obj.addProperty("retrySameOpeningId", error.retrySameOpeningId());
        return GSON.toJson(obj);
    }
}
