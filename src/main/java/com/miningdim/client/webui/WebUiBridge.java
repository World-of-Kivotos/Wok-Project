package com.miningdim.client.webui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.caseopening.CaseSounds;
import com.miningdim.network.C2SWebUiRequest;
import com.miningdim.network.MiningNetwork;
import com.miningdim.webui.WebUiPageUrl;

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
 *
 * onQuery/onScreenClosed 返回给前端的 failure code 表:
 *   -1  信封非法 (缺 action / JSON 解析失败) 或本地动作 (client.*) 自身失败;
 *   -2  服务端 30 秒内未响应, 请求超时;
 *   -3  页面不可信: 发起方不是顶层帧, 或其文档 URL 与登记的允许页面不匹配 (子帧/iframe/被导航到别处);
 *   -4  界面已关闭, 请求根本没有发出去 (screenOpen=false 时的本地短路), 前端可安全忽略/退避轮询;
 *   -5  界面在请求发出后、响应回来前被关闭, 请求可能已经在服务端落账, 前端应提示"操作可能已完成,
 *       请刷新确认" 而不是当作单纯失败处理。
 *
 * 复核修正: 上一句"前端 errorText.ts 据此选择提示文案"与实际链路不符, 已删除 —— webui/src/lib/errorText.ts
 * 的 businessErrorText 只按服务端业务失败信封 (JSON 里的 errorCode 字符串) 查表, 而本类这五个码是 CEF
 * cefQuery 的宿主级失败码 (数字), 走的是 webui/src/lib/bridge.ts 的 toCallError: 非 0 码一律
 * {@code business = null}, callErrorText 在 business 为 null 时直接 {@code return error.message} ——
 * 也就是说, 这里 callback.failure 第二参传的字符串会被玩家原样看到, 不会再经过任何码表翻译。
 * 因此这五个码的 message 必须是可以直接给玩家看的中文句子, 不能写成给日志看的英文诊断串。
 *
 * 已知遗留 (跨车道缺口, 本类无法独自补齐): webui/src/lib/bridge.ts 的 WebUiCallError code 注释表目前只列到
 * -3, webui/src/lib/errorText.ts 完全没有按宿主数字码分支的入口; -4/-5 若要做到"前端保留上一次数据 + 退避
 * 轮询"而不是把整个查询状态清成 error/null (webui/src/mock/useMockWorld.ts 的 useMockAction 对任何 reject
 * 都会这样清), 需要 webui/ 前端车道配套改造, 不在本 Java 文件的改动范围内。
 */
public final class WebUiBridge extends CefMessageRouterHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/webui");

    private static final Gson GSON = new Gson();
    private static final long CALLBACK_TIMEOUT_SECONDS = 30L;
    private static final Set<String> CASE_SOUND_CUES = Set.of(
            "unlock", "open", "tick", "reveal_blue", "reveal_purple", "reveal_pink",
            "reveal_red", "reveal_gold");
    private static final ScheduledExecutorService CALLBACK_TIMEOUTS =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "miningdim-webui-callback-timeout");
                thread.setDaemon(true);
                return thread;
            });

    // requestId 自增源: 客户端进程内唯一, 跨 C2S/S2C 关联; 对 JS 不可见。
    private final AtomicLong requestIdGen = new AtomicLong(0L);

    // 在途请求: requestId -> JS 回调。CefQueryCallback 可异步持有, 待 S2C 回来再调 success/failure。
    private final ConcurrentHashMap<Long, PendingQuery> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> requestIdByCefQueryId = new ConcurrentHashMap<>();

    // 当前活动浏览器 (onEvent 注入 JS 用); 由 WebUiClient 在打开界面时 setBrowser, 关闭时置空。
    @Nullable
    private volatile WebBrowser browser;
    @Nullable
    private volatile String allowedPageUrl;
    // 界面是否处于打开状态 (F043): 只跟随 WebUiClient.openScreen/onScreenClosed, 与 allowedPageUrl
    // 是否非空是两条独立的门 —— 页面可信与界面在不在屏幕上是两件不同的事。
    private volatile boolean screenOpen;
    // 上一次因页面不可信被拒时记录的实际文档 URL (onQuery 拒绝日志去重用); 见 onQuery 内注释。
    @Nullable
    private volatile String lastRejectedPageUrl;

    public void setBrowser(@Nullable WebBrowser browser) {
        this.browser = browser;
    }

    /**
     * 登记宿主本次明确加载的页面 URL, 只有它能调 cefQuery (由 {@link #onQuery} 做精确匹配)。
     *
     * 允许两类来源:
     *  - {@code data:text/html} —— jar 内置页 (开箱等含扣费动作的页面走这条, 保持最强保证不变);
     *  - {@code http://} / {@code https://} —— 远端托管的前端 (架构文档第二章第 2 条的路线 A / 第十章的路线 B)。
     *
     * 放宽的只是"页面从哪来", **没有放宽"哪个页面可信"** —— onQuery 仍要求发起方是顶层帧且其 URL 精确等于本值。
     * 挡住子帧/iframe/导航后页面的是那道精确匹配, 与 scheme 无关, 对 http 页面同样成立。信任判定点在宿主
     * {@link WebUiClient}: 它只会加载 jar 内置页或运维配置的前端地址, 二者之外的 URL 永远不会传到这里。
     *
     * 登记值经 {@link WebUiPageUrl#normalize} 归一化后存入; onQuery 侧对 CEF 实时回读的文档 URL 也过
     * 同一套归一化再比对 (见 {@link WebUiPageUrl#matchesNormalized}), 尾斜杠/大小写/默认端口/百分号
     * 编码差异不再构成拒绝。
     */
    public void setAllowedPage(String pageUrl) {
        if (!pageUrl.startsWith("data:text/html")
                && !pageUrl.startsWith("http://")
                && !pageUrl.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "WebUI page must be an in-mod HTML data URI or an http(s) front-end URL");
        }
        this.allowedPageUrl = WebUiPageUrl.normalize(pageUrl);
    }

    /** 界面已打开 (由 {@link WebUiClient#openScreen} 在 {@code mc.setScreen} 之前调用)。 */
    public void onScreenOpened() {
        this.screenOpen = true;
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
        String allowed = allowedPageUrl;
        if (allowed == null || cefBrowser == null || frame == null || !frame.isMain()
                || !WebUiPageUrl.matchesNormalized(allowed, cefBrowser.getURL())) {
            // 诊断日志: 归一化能盖住尾斜杠/大小写/默认端口这类字面差异, 但盖不住 301/302 或 HSTS
            // 升级 (那是真的换了文档)。部署当天必须能一眼看出配的是什么、浏览器实际停在哪, 而不是
            // 只看到一个 -3。去重按 (实际 URL) 键: 前端每 3 秒轮询一次, 不去重就是刷屏。
            if (cefBrowser != null) {
                String actual = cefBrowser.getURL();
                if (!Objects.equals(actual, lastRejectedPageUrl)) {
                    LOGGER.warn("WebUI 页面授权失败: 配置/登记页面为 {}, 浏览器实际停在 {}", allowed, actual);
                    lastRejectedPageUrl = actual;
                }
            }
            callback.failure(-3, "WebUI query rejected: untrusted page or subframe");
            return true;
        }
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

        // F043 关屏门: 界面关闭后请求根本不该发出去。-4 与 -3 是两个独立的失败码——
        // -3 语义是"页面被塞进 iframe 或改过 location"(真安全问题), -4 语义是"界面已关闭,
        // 请求原地短路", 前端据此保留上一次的好数据并退避轮询; 共用一个码会让排障指向一个不存在
        // 的安全问题。这里不能干脆放行: SPA 在关屏后原样存活并继续按原节奏轮询, 放行就等于每个
        // 关掉平板的玩家每 3 秒真打一次服务端主线程。只给 client.i18n 开口子, 因为它是纯本地、
        // 零副作用的翻译键解析; client.playCaseSound 会在界面不可见时凭空放音效, 必须一起挡。
        if (!screenOpen && !"client.i18n".equals(action)) {
            // 中文文案, 不是英文诊断串: business=null 时这句话会被玩家原样看到 (见上方类注释)。
            callback.failure(-4, "界面已关闭，请求未发送");
            return true;
        }

        // 客户端本地动作 (client.* 前缀): 不走服务端权威往返, 就地在客户端解析回调。
        // 目前只有 client.i18n —— 把翻译键解析成当前语言显示名 (专用服务器不加载 lang, 故中文名必须客户端解析)。
        if (action.startsWith("client.")) {
            handleClientLocal(action, payloadJson, callback);
            return true;
        }

        long requestId = requestIdGen.incrementAndGet();
        pending.put(requestId, new PendingQuery(queryId, callback));
        requestIdByCefQueryId.put(queryId, requestId);
        CALLBACK_TIMEOUTS.schedule(() -> Minecraft.getInstance().execute(() -> expireRequest(requestId)),
                CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            // 客户端无需 canReceive 守卫 (C2S 走自身连接); 直接发到服务端。
            MiningNetwork.CHANNEL.sendToServer(new C2SWebUiRequest(requestId, action, payloadJson));
        } catch (RuntimeException sendError) {
            removePending(requestId);
            callback.failure(-1, "failed to send request: " + sendError.getMessage());
        }
        return true;
    }

    /**
     * 客户端本地动作 (无服务端往返)。client.i18n: {keys:[翻译键...]} -> {names:{键:当前语言显示名}}, 经 MC 客户端 I18n
     * 解析 (专用服务器不加载 lang, 中文名只能客户端出)。在 CEF 线程同步回调 (与 onQuery 的 failure 同纪律)。
     */
    private void handleClientLocal(String action, String payloadJson, CefQueryCallback callback) {
        if ("client.playCaseSound".equals(action)) {
            handleCaseSound(payloadJson, callback);
            return;
        }
        if ("client.closePanel".equals(action)) {
            WebUiClient.requestClose();
            callback.success("{\"closed\":true}");
            return;
        }
        if ("client.textFocus".equals(action)) {
            handleTextFocus(payloadJson, callback);
            return;
        }
        if (!"client.i18n".equals(action)) {
            callback.failure(-1, "unknown client-local action: " + action);
            return;
        }
        try {
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();
            JsonArray keys = payload.has("keys") && payload.get("keys").isJsonArray()
                    ? payload.getAsJsonArray("keys")
                    : new JsonArray();
            JsonObject names = new JsonObject();
            for (JsonElement k : keys) {
                String key = k.getAsString();
                // I18n.get 取当前语言显示名; 缺翻译时原版回退键本身 (不抛, 前端再兜底)。
                names.addProperty(key, I18n.get(key));
            }
            JsonObject result = new JsonObject();
            result.add("names", names);
            callback.success(GSON.toJson(result));
        } catch (RuntimeException e) {
            callback.failure(-1, "client.i18n failed: " + e.getMessage());
        }
    }

    /**
     * 页面上报"当前焦点是不是可编辑元素"。
     *
     * 只在页面侧判定得了: CEF 与 MCEF 都不暴露焦点节点的可编辑性 (javap 实测), 而打开键 (默认 G) 兼作
     * 关闭键时必须知道玩家是在搜索框里打字还是在翻页面。字段缺失按"没有焦点"处理 —— 那是默认可关的一侧,
     * 与页面尚未接上报时的行为一致。
     */
    private void handleTextFocus(String payloadJson, CefQueryCallback callback) {
        try {
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();
            boolean focused = payload.has("focused") && payload.get("focused").getAsBoolean();
            WebUiClient.setTextInputFocused(focused);
            callback.success("{\"ok\":true}");
        } catch (RuntimeException e) {
            callback.failure(-1, "client.textFocus failed: " + e.getMessage());
        }
    }

    private void handleCaseSound(String payloadJson, CefQueryCallback callback) {
        try {
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();
            String cue = payload.get("cue").getAsString();
            if (!CASE_SOUND_CUES.contains(cue)) {
                callback.failure(-1, "unknown case sound cue: " + cue);
                return;
            }
            Minecraft.getInstance().execute(() -> CaseSounds.playClient(cue));
            callback.success("{\"played\":true}");
        } catch (RuntimeException e) {
            callback.failure(-1, "client.playCaseSound failed: " + e.getMessage());
        }
    }

    /** CEF 取消查询 (页面卸载/导航): 丢弃在途 callback, 不再回调, 防内存泄漏。 */
    @Override
    public void onQueryCanceled(CefBrowser cefBrowser, CefFrame frame, long queryId) {
        Long requestId = requestIdByCefQueryId.remove(queryId);
        if (requestId != null) {
            pending.remove(requestId);
        }
    }

    /**
     * 服务端 S2C 响应回调 (经 WebUiClientReceiver 转交)。按 requestId 取出 JS callback 并回填结果。
     * 找不到 requestId (重复响应 / 已取消) 时静默忽略, 不抛 (网络层重放不应崩客户端)。
     */
    public void onResponse(long requestId, boolean success, String resultJson) {
        PendingQuery query = removePending(requestId);
        if (query == null) {
            LOGGER.debug("收到无对应在途请求的响应, requestId={} (可能已取消)", requestId);
            return;
        }
        if (success) {
            query.callback().success(resultJson);
        } else {
            // 失败码用 0 占位 (业务错误细节在 resultJson 的 error 字段, 经 JS onFailure 第二参获取)。
            query.callback().failure(0, resultJson);
        }
    }

    /**
     * 页面退出后逐个回失败在途 CEF callback, 并停止仍在播放的开箱 UI 音效。
     *
     * F043: 这里不再清空 {@code allowedPageUrl} —— 授权只跟随宿主加载了哪个页面 (setAllowedPage),
     * 不跟随 Screen 开关。SPA 关屏后在后台继续存活并轮询, 若把授权也撤销, 每次轮询都会被 onQuery
     * 判成"页面不可信"而不是"界面已关闭", 前端据此清空了本该保留的数据。改为置 {@code screenOpen=false},
     * 由 onQuery 顶部的 F043 关屏门统一短路。
     *
     * F044: 在途请求逐个回 -5 而不是整批静默丢弃。-5 与短路用的 -4 是两个码: -4 表示请求压根没出门,
     * 可以安全忽略; -5 表示请求已经发到服务端、可能已经落账 (玩家点了 market.buy 后立刻 ESC, 钱是
     * 真扣了), 前端必须提示"操作可能已完成, 请刷新确认"而不是简单说失败。线程模型与 expireRequest
     * 一致: 本方法经 WebUiScreen.cleanup -> WebUiClient.onScreenClosed 在客户端主线程调用, 不引入
     * 新线程模型。
     */
    public void onScreenClosed() {
        screenOpen = false;
        List<Long> inFlightRequestIds = new ArrayList<>(pending.keySet());
        for (Long requestId : inFlightRequestIds) {
            PendingQuery query = removePending(requestId);
            if (query != null) {
                // 中文文案, 不是英文诊断串 (见上方类注释): 请求已经发到服务端, 落账与否未知,
                // 措辞必须明确提示玩家自行确认, 不能读起来像一次单纯的失败。
                query.callback().failure(-5, "界面已关闭，本次操作是否已生效尚不确定，请刷新确认");
            }
        }
        // 兜底残留 (理论上 removePending 已清空两个 map, 这里防御性收尾)。
        pending.clear();
        requestIdByCefQueryId.clear();
        CaseSounds.stopClient();
    }

    private void expireRequest(long requestId) {
        PendingQuery query = removePending(requestId);
        if (query != null) {
            query.callback().failure(-2, "request timed out");
        }
    }

    @Nullable
    private PendingQuery removePending(long requestId) {
        PendingQuery query = pending.remove(requestId);
        if (query != null) {
            requestIdByCefQueryId.remove(query.cefQueryId(), requestId);
        }
        return query;
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

    private record PendingQuery(long cefQueryId, CefQueryCallback callback) {
    }
}
