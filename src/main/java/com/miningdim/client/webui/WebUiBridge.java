package com.miningdim.client.webui;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.network.C2SWebUiRequest;
import com.miningdim.network.MiningNetwork;

/**
 * cefQuery 桥核心 (客户端宿主侧, 仅 Dist.CLIENT classload)。一端接 JS 的 window.miningdimQuery,
 * 一端接服务端的 S2C 响应/事件; 用 requestId 关联两侧。
 *
 * 流向:
 *   JS  -> onQuery(request={action,payload}) -> 生成 requestId -> pending.put(requestId, callback)
 *       -> CHANNEL.sendToServer(C2SWebUiRequest)  // 服务端权威, 客户端只发意图
 *   服务端 -> WebUiClientReceiver.onResponse -> 本类 onResponse -> pending.remove(requestId).success/failure
 *   服务端 -> WebUiClientReceiver.onEvent    -> 本类 onEvent    -> browser.executeJavaScript 派发给 JS 监听
 *
 * requestId 仅 Java 客户端可见 (AtomicLong 自增), 不进 JS 信封; JS 侧靠 cefQuery 自带 callback 关联 (共享契约 3)。
 *
 * 服务端权威 (架构铁律 1): 本桥不写任何世界状态, 只转发意图与渲染结果。
 */
public final class WebUiBridge extends CefMessageRouterHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/webui");

    private static final Gson GSON = new Gson();

    // requestId 自增源: 客户端进程内唯一, 跨 C2S/S2C 关联; 对 JS 不可见。
    private final AtomicLong requestIdGen = new AtomicLong(0L);

    // 在途请求: requestId -> JS 回调。CefQueryCallback 可异步持有, 待 S2C 回来再调 success/failure。
    private final ConcurrentHashMap<Long, CefQueryCallback> pending = new ConcurrentHashMap<>();

    // 当前活动浏览器 (onEvent 注入 JS 用); 由 WebUiClient 在打开界面时 setBrowser, 关闭时置空。
    @Nullable
    private volatile WebBrowser browser;

    public void setBrowser(@Nullable WebBrowser browser) {
        this.browser = browser;
    }

    /**
     * JS -> Java 入口 (CEF 消息路由回调)。解析 {action,payload} 信封, 生成 requestId, 登记 callback, 发 C2S。
     * 返回 true 表示本路由已接管该 query (CEF 据此等待异步 success/failure)。
     *
     * 坏输入 (request 非法 JSON / 缺 action) 直接 callback.failure 并返回 true 接管; 不向服务端发垃圾包,
     * 也不向 CEF 返回 false (false 会让其它路由继续尝试, 这里我们是唯一品牌路由)。
     */
    @Override
    public boolean onQuery(CefBrowser cefBrowser, CefFrame frame, long queryId,
                           String request, boolean persistent, CefQueryCallback callback) {
        JsonObject envelope;
        try {
            envelope = JsonParser.parseString(request).getAsJsonObject();
        } catch (RuntimeException parseError) {
            // 信封非法是前端 bug, 不是服务端业务错误: 就地以 failure 回 JS, 不发网络包。
            callback.failure(-1, "invalid request envelope: " + parseError.getMessage());
            return true;
        }

        if (!envelope.has("action") || envelope.get("action").isJsonNull()) {
            callback.failure(-1, "missing action in request envelope");
            return true;
        }
        String action = envelope.get("action").getAsString();
        // payload 缺省为空对象; 序列化回字符串经网络下发服务端 (服务端再 Gson 解析)。
        String payloadJson = envelope.has("payload") && !envelope.get("payload").isJsonNull()
                ? GSON.toJson(envelope.get("payload"))
                : "{}";

        long requestId = requestIdGen.incrementAndGet();
        pending.put(requestId, callback);
        // 客户端无需 canReceive 守卫 (C2S 走自身连接); 直接发到服务端。
        MiningNetwork.CHANNEL.sendToServer(new C2SWebUiRequest(requestId, action, payloadJson));
        return true;
    }

    /** CEF 取消查询 (页面卸载/导航): 丢弃在途 callback, 不再回调, 防内存泄漏。 */
    @Override
    public void onQueryCanceled(CefBrowser cefBrowser, CefFrame frame, long queryId) {
        // queryId 是 CEF 侧 id, 与本桥 requestId 不同维度; CEF 内部已作废该 callback, 这里无需按 requestId 清理。
        // 在途 requestId 若对应的页面被销毁, 其 callback 调用会被 CEF 安全忽略, 故无须主动遍历清。
    }

    /**
     * 服务端 S2C 响应回调 (经 WebUiClientReceiver 转交)。按 requestId 取出 JS callback 并回填结果。
     * 找不到 requestId (重复响应 / 已取消) 时静默忽略, 不抛 (网络层重放不应崩客户端)。
     */
    public void onResponse(long requestId, boolean success, String resultJson) {
        CefQueryCallback callback = pending.remove(requestId);
        if (callback == null) {
            LOGGER.debug("收到无对应在途请求的响应, requestId={} (可能已取消)", requestId);
            return;
        }
        if (success) {
            callback.success(resultJson);
        } else {
            // 失败码用 0 占位 (业务错误细节在 resultJson 的 error 字段, 经 JS onFailure 第二参获取)。
            callback.failure(0, resultJson);
        }
    }

    /**
     * 服务端推送事件 (经 WebUiClientReceiver 转交)。把事件派发给 JS 侧监听器。
     *
     * 安全注入 (共享契约 5): dataJson 是服务端构造的可信 JSON; eventName 走受控常量 (服务端侧约束),
     * 此处把两者作为参数传给页面预置的 window.miningdimOnEvent 分发函数, 不手写危险字符串拼接,
     * 由 Gson 转义保证 JS 字面量安全 (即便 dataJson 含引号/反斜杠也不破坏脚本结构)。
     */
    public void onEvent(String eventName, String dataJson) {
        WebBrowser b = browser;
        if (b == null) {
            LOGGER.debug("收到事件但无活动浏览器, event={}", eventName);
            return;
        }
        // GSON.toJson(String) 产出带引号且完整转义的 JS 字符串字面量, 避免注入与语法破坏。
        String nameLiteral = GSON.toJson(eventName);
        String dataLiteral = GSON.toJson(dataJson);
        // 约定: 前端预置 window.miningdimOnEvent(name, dataJsonString) 作为统一事件入口;
        // 不存在该函数时静默 no-op (用 typeof 守卫), 不报错。
        String script = "if (typeof window.miningdimOnEvent === 'function') {"
                + " window.miningdimOnEvent(" + nameLiteral + ", " + dataLiteral + "); }";
        b.executeJavaScript(script);
    }
}
