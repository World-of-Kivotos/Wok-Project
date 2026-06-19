package com.miningdim.client.webui;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端 S2C 接收静态入口 (共享契约 5)。S2CWebUiResponse / S2CWebUiEvent 的服务端 handle 经
 * DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...) 引用本类的全限定名静态方法; 本类只在 Dist.CLIENT classload,
 * 故服务端 GameTest 进程不会触链 MCEF。
 *
 * 本类只做"转交": 持有当前活动 {@link WebUiBridge} 静态引用 (由 {@link WebUiClient} 在 MCEF 初始化后设置),
 * 把响应/事件交给该桥实例。桥未就绪 (MCEF 未初始化 / 未打开界面) 时静默忽略, 不抛 (网络线程不应被打断)。
 */
public final class WebUiClientReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/webui");

    // 当前活动桥; volatile: 网络线程读, 客户端主线程 (WebUiClient init) 写。
    @Nullable
    private static volatile WebUiBridge currentBridge;

    private WebUiClientReceiver() {
    }

    /** 由 WebUiClient 在 MCEF 初始化成功、注册路由后设置当前桥。 */
    public static void setCurrentBridge(@Nullable WebUiBridge bridge) {
        currentBridge = bridge;
    }

    /** S2C 响应入口: 转交当前桥按 requestId 回填 JS callback。 */
    public static void onResponse(long requestId, boolean success, String resultJson) {
        WebUiBridge bridge = currentBridge;
        if (bridge == null) {
            LOGGER.debug("收到 WebUI 响应但桥未就绪, requestId={}", requestId);
            return;
        }
        bridge.onResponse(requestId, success, resultJson);
    }

    /** S2C 事件入口: 转交当前桥派发给 JS 监听。 */
    public static void onEvent(String eventName, String dataJson) {
        WebUiBridge bridge = currentBridge;
        if (bridge == null) {
            LOGGER.debug("收到 WebUI 事件但桥未就绪, event={}", eventName);
            return;
        }
        bridge.onEvent(eventName, dataJson);
    }
}
