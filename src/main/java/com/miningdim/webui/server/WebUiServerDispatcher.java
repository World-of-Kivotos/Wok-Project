package com.miningdim.webui.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.network.MiningNetwork;
import com.miningdim.network.S2CWebUiResponse;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web UI 服务端派发器 (Web UI bridge 契约第 6 节)。客户端 CEF 内 JS 经 cefQuery 发起的 action 在此按名查表派发,
 * 服务端唯一写入方 (架构铁律 1): action handler 拿到经服务端校验的 sender 与解析后的 payload, 返回 resultJson 回执。
 *
 * action 注册表由各子系统 (WebUiServerSubsystem 及后续业务子系统) 在 register 期经 {@link #register} 填充;
 * 用 ConcurrentHashMap 容忍多子系统注册期写入与网络线程读取的可见性 (注册期一次性写, 运行期只读)。
 *
 * 异常纪律 (CLAUDE.md C9 / 契约第 6 节): {@link #dispatchAndRespond} 是最外层 Gateway 边界, 是本子系统
 * 唯一允许 try-catch 的位置。action handler 内坏输入/业务错误自然抛出, 在此统一捕获 -> 回执 success=false +
 * {"error":"..."} 并记日志; 不重抛打断网络线程。handler 内部严禁吞异常。
 */
public final class WebUiServerDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/webui");

    /** action 名 -> 处理器。注册期写, 运行期 (网络线程) 读; ConcurrentHashMap 保证发布可见性。 */
    private static final Map<String, WebUiAction> ACTIONS = new ConcurrentHashMap<>();

    /** 复用单一 Gson 实例构造 resultJson (无定制配置需求, 线程安全可静态共享)。 */
    private static final Gson GSON = new Gson();

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
        try {
            WebUiAction handler = ACTIONS.get(action);
            if (handler == null) {
                throw new IllegalArgumentException("unknown Web UI action: " + action);
            }
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();
            String resultJson = handler.handle(sender, payload);
            MiningNetwork.sendWebUiResponse(sender, new S2CWebUiResponse(requestId, true, resultJson));
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

    /** 构造 {"error":"<message>"} 失败回执 (经 Gson 转义, 防 message 内引号破坏 JSON)。 */
    private static String errorJson(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message == null ? "unknown error" : message);
        return GSON.toJson(obj);
    }
}
