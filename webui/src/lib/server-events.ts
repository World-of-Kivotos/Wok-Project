/**
 * 服务端推送事件名 (S2CWebUiEvent 的 eventName) 的前端登记表。
 *
 * 与 Java 侧 com.miningdim.webui.server.WebUiEventNames 逐条相等, 由 pnpm check:contract 核对: 两边分处两种
 * 语言, 事件名对不上既没有编译错误也没有运行期报错, 只表现为"推送永远不到"。订阅一律经这里取名字, 不在组件里
 * 手写字符串。
 *
 * 红线 (WebUI_ServerPush_DesignSpec 第五章): 任何功能都不能依赖推送到达才能工作, 每条事件都要有轮询或手动兜底。
 */
export const SERVER_EVENTS = {
  /** 登录门: 本玩家的判定刚由拒绝翻为放行 (AccessHub /login 落地)。载荷为空对象。 */
  loginConfirmed: 'auth.loginConfirmed',
} as const
