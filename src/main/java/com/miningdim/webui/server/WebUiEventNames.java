package com.miningdim.webui.server;

/**
 * 服务端推送事件名 ({@code S2CWebUiEvent} 的 eventName) 的唯一登记处 (WebUI_ServerPush_DesignSpec 第三章:
 * 不许各子系统自由拼字符串)。
 *
 * 命名一律 {@code 域.事件} 小驼峰, 与 action 名同风格。前端对应表是 {@code webui/src/lib/server-events.ts} 的
 * SERVER_EVENTS, 两边逐条相等由 {@code pnpm check:contract} 核对 —— 事件名对不上没有任何编译期或运行期报错,
 * 只表现为"推送永远不到"。
 *
 * 红线 (同规格第五章): 任何功能都不能依赖推送到达才能工作, 前端对每条事件都要有轮询或手动兜底。
 */
public final class WebUiEventNames {

    /**
     * 登录门: 这名玩家的判定刚由拒绝翻为放行 (AccessHub /login 生效)。载荷为空对象。
     * 平板收到后撤掉登录提示并重拉数据; 兜底是提示挂着期间的退避探测。
     */
    public static final String LOGIN_CONFIRMED = "auth.loginConfirmed";

    private WebUiEventNames() {
    }
}
